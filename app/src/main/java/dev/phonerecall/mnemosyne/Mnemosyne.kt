package dev.phonerecall.mnemosyne

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fasterxml.jackson.databind.JsonNode
import dev.phonerecall.brain.KeystoreSealer
import dev.phonerecall.brain.Sealer
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.Gateway
import dev.phonerecall.lemnos.GatewayException
import dev.phonerecall.vault.Box
import dev.phonerecall.vault.KeystoreBoxKeys
import dev.phonerecall.vault.Unwrapper
import dev.phonerecall.vault.VaultCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A backup on Lemnos, as the list shows it. */
data class RemoteBackup(val id: String, val kind: String, val size: Long, val created: Long)

data class LastRun(val at: Long, val ok: Boolean, val size: Long, val id: String?, val error: String?, val kind: String)

/** What the Mnemosyne page shows. */
data class MnState(
    val setUp: Boolean = false,
    val paired: Boolean = false,
    val last: LastRun? = null,
    val running: String? = null,
    val calypso: Boolean = false,
    val calypsoTotal: Int = 0,
    val calypsoEscrowed: Int = 0,
    val remote: List<RemoteBackup>? = null,
    val remoteError: String? = null,
    val restored: Restored? = null,
    val nextRun: Long? = null,
)

/** After a restore: what came across, and what he still has to do by hand. */
data class Restored(val at: Long, val backupId: String, val prefs: Int, val dbs: Int, val files: Int, val calypso: Int, val calypsoLost: Int, val failed: List<String>)

/** The Keystore keys Zeus seals stores with ("zeus.aegis.*"): every one is tried when a value is opened for a backup. */
class KeystoreBank : SealerBank {
    private val cache = HashMap<String, Sealer>()
    override fun aliases(): List<String> = runCatching {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList().filter { it.startsWith("zeus.aegis.") }.sorted()
    }.getOrDefault(emptyList())
    // Each alias seals the way its owner does: Calypso's vault stays Keystore-direct, the rest use envelopes.
    override fun sealer(alias: String): Sealer = synchronized(cache) { cache.getOrPut(alias) { KeystoreSealer(alias, envelope = alias != dev.phonerecall.vault.VaultCore.ALIAS) } }
}

/**
 * Mnemosyne, Zeus's encrypted backup (docs/MNEMOSYNE.md). Nightly, or on "Back up now": every store's logical export,
 * opened with its own Keystore key here, zipped, encrypted under the recovery phrase's backup key ([Zbk]) and sent to
 * Lemnos over mTLS. Lemnos only ever holds ciphertext. The phrase is kept on the phone only wrapped to a Keystore key
 * that needs his fingerprint (to show it again); the backup key is kept wrapped to one that doesn't (for the nightly job).
 */
object Mnemosyne {
    private const val PREFS = "mnemosyne"
    private const val KEY_ALIAS = "zeus.mnemosyne.key"          // the backup key, no prompt (nightly)
    private const val PHRASE_ALIAS = "zeus.mnemosyne.phrase"    // the phrase, RSA, fingerprint for every use
    private const val WORK = "mnemosyne.nightly"
    private val running = Mutex()
    private val _state = MutableStateFlow(MnState())
    val state: StateFlow<MnState> = _state.asStateFlow()

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    fun isSetUp(c: Context) = sp(c).getBoolean("on", false) && sp(c).contains("key")

    /** When the current phrase was set (the fresh start asks for a new one). */
    fun setUpAt(c: Context): Long? = sp(c).getLong("since", 0L).takeIf { it > 0 }

    // ---- set-up ----

    /** Step 1: a fresh phrase, in memory only until [finishSetup]. */
    fun newPhrase(): ByteArray = Phrase.newEntropy()

    /**
     * Step 2, after he wrote it down and passed the check: keep the backup key (no prompt) and the phrase (fingerprint),
     * make Calypso's escrow pair, schedule the nightly job. A restore calls this too, with the phrase he typed.
     */
    fun finishSetup(c: Context, entropy: ByteArray) {
        val key = Keys.backupKey(entropy)
        val pair = Escrow.newPair(Keys.escrowKey(entropy))
        val phraseCt = Escrow.wrap(KeystoreBoxKeys(PHRASE_ALIAS).publicKey(), entropy)
        // A new phrase is a new escrow pair: items wrapped for the old one are escrowed again from Calypso's page.
        val old = sp(c).all.keys.filter { it.startsWith("esc.item.") }
        sp(c).edit().apply { old.forEach { remove(it) } }
            .putBoolean("on", true)
            .putString("key", b64(KeystoreSealer(KEY_ALIAS).sealBytes(key)))
            .putString("phrase", b64(phraseCt))
            .putString("esc.pub", b64(pair.publicDer)).putString("esc.priv", b64(pair.sealedPrivate))
            .putLong("since", System.currentTimeMillis())
            .commit()
        key.fill(0)
        schedule(c)
        refresh(c)
    }

    /** "Show recovery phrase": a decrypt cipher on the phrase key, for BiometricPrompt to authorise. */
    fun phraseUnwrapper(): Unwrapper = KeystoreBoxKeys(PHRASE_ALIAS).unwrapper()

    /** After the prompt: the 24 words. Nothing is cached. */
    fun phraseWords(c: Context, u: Unwrapper): List<String> {
        val e = u.unwrap(unb64(sp(c).getString("phrase", null) ?: error("not set up")))
        try { return Phrase.encode(e) } finally { e.fill(0) }
    }

    private fun backupKey(c: Context): ByteArray =
        sp(c).getString("key", null)?.let { KeystoreSealer(KEY_ALIAS).openBytes(unb64(it)) } ?: throw IllegalStateException("Mnemosyne's key can't be opened. Set backups up again.")

    // ---- Calypso ----

    /** Turns Calypso's inclusion on or off (the page asks for his fingerprint first). Off drops the escrow. */
    fun setCalypso(c: Context, on: Boolean) {
        val ed = sp(c).edit().putBoolean("calypso", on)
        if (!on) sp(c).all.keys.filter { it.startsWith("esc.item.") }.forEach { ed.remove(it) }
        ed.commit()
        installTap(c)
        refresh(c)
    }

    /** At process start and on every change: Calypso's key tap is set exactly while inclusion is on. */
    fun installTap(c: Context) {
        val app = c.applicationContext
        val pub = sp(app).getString("esc.pub", null)?.let { runCatching { Escrow.publicKey(unb64(it)) }.getOrNull() }
        Box.keyTap = if (isSetUp(app) && sp(app).getBoolean("calypso", false) && pub != null) { id, key ->
            sp(app).edit().putString("esc.item.$id", b64(Escrow.wrap(pub, key))).apply()
            _state.update { it.copy(calypsoEscrowed = it.calypsoEscrowed + if (it.calypsoEscrowed < it.calypsoTotal) 1 else 0) }
        } else null
    }

    private fun escrowed(c: Context): Map<String, ByteArray> =
        sp(c).all.filterKeys { it.startsWith("esc.item.") }.mapKeys { it.key.removePrefix("esc.item.") }.mapValues { unb64(it.value as String) }

    /** Items not yet in the escrow: open each once (one fingerprint each) and the tap adds it. */
    fun unescrowed(c: Context): List<String> {
        val have = escrowed(c).keys
        return VaultCore.get(c).calypso.items().map { it.id }.filter { it !in have }
    }

    /** On the new phone, from the staged restore: the old escrow pair and items, and inclusion stays on. */
    internal fun adoptEscrow(c: Context, o: JsonNode) {
        val ed = sp(c).edit().putString("esc.pub", o.path("public").asText()).putString("esc.priv", o.path("private").asText()).putBoolean("calypso", true)
        o.path("items").fields().forEach { (id, v) -> ed.putString("esc.item.$id", v.asText()) }
        ed.commit()
    }

    // ---- backup ----

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    fun newId(now: Long = System.currentTimeMillis(), random: SecureRandom = SecureRandom()): String =
        LocalDateTime.ofEpochSecond(now / 1000, 0, ZoneOffset.UTC).format(stamp) + "-" + ByteArray(4).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private val http by lazy { Gateway.client.newBuilder().writeTimeout(5, TimeUnit.MINUTES).readTimeout(5, TimeUnit.MINUTES).build() }

    private fun fail(r: okhttp3.Response): Nothing {
        val detail = runCatching { JSONObject(r.body?.string() ?: "").optString("detail") }.getOrNull().orEmpty()
        r.close()
        throw GatewayException(r.code, detail.ifEmpty { "Lemnos answered ${r.code}" })
    }

    /** Builds, encrypts and uploads one backup. Only one runs at a time. */
    suspend fun run(c: Context, kind: String): LastRun = withContext(Dispatchers.IO) {
        val app = c.applicationContext
        running.withLock {
            _state.update { it.copy(running = "Gathering") }
            val now = System.currentTimeMillis()
            val id = newId(now)
            val dir = File(app.noBackupFilesDir, "mnemosyne-out").apply { deleteRecursively(); mkdirs() }
            val out = File(dir, "$id.zbk")
            val result = try {
                if (!isSetUp(app)) throw IllegalStateException("Backups aren't set up.")
                if (!DeviceKeys.paired(app)) throw IllegalStateException("Lemnos isn't paired.")
                val key = backupKey(app)
                val cal = if (sp(app).getBoolean("calypso", false)) {
                    val ids = VaultCore.get(app).calypso.items().map { it.id }.toSet()
                    val esc = escrowed(app)
                    sp(app).edit().apply { esc.keys.filter { it !in ids }.forEach { remove("esc.item.$it") } }.apply()   // deleted items
                    CalypsoEscrow(Escrow.Pair(unb64(sp(app).getString("esc.pub", "")!!), unb64(sp(app).getString("esc.priv", "")!!)), esc.filterKeys { it in ids })
                } else null
                val manifest = mapOf(
                    "id" to id, "created" to now, "kind" to kind, "app" to dev.phonerecall.BuildConfig.VERSION_NAME,
                    "app_code" to dev.phonerecall.BuildConfig.VERSION_CODE, "device" to android.os.Build.MODEL,
                )
                val report = Zbk.writer(out.outputStream().buffered(1 shl 16), key, id, now).use { w ->
                    Exporter(Layout(app), KeystoreBank(), cal).export(w, manifest)
                }
                key.fill(0)
                _state.update { it.copy(running = "Sending") }
                val req = Request.Builder().url("${Gateway.BASE}/api/backup/$id?kind=$kind").put(out.asRequestBody("application/octet-stream".toMediaType())).build()
                http.newCall(req).execute().use { r -> if (!r.isSuccessful) fail(r) }
                val bad = report.parts.filter { it.error != null }.joinToString { it.name }
                LastRun(now, true, out.length(), id, if (bad.isNotEmpty()) "Some stores couldn't be read: $bad" else null, kind)
            } catch (e: Exception) {
                LastRun(now, false, 0, null, e.message ?: e.javaClass.simpleName, kind)
            } finally {
                dir.deleteRecursively()
            }
            sp(app).edit().putString("last", JSONObject().put("at", result.at).put("ok", result.ok).put("size", result.size).put("id", result.id)
                .put("error", result.error).put("kind", result.kind).toString()).commit()
            _state.update { it.copy(running = null) }
            refresh(app)
            result
        }
    }

    fun list(): List<RemoteBackup> {
        val r = http.newCall(Request.Builder().url("${Gateway.BASE}/api/backup").build()).execute()
        if (!r.isSuccessful) fail(r)
        val a = r.use { JSONObject(it.body!!.string()).getJSONArray("backups") }
        return (0 until a.length()).map { a.getJSONObject(it) }.map { RemoteBackup(it.getString("id"), it.optString("kind"), it.optLong("size"), it.optLong("created") * 1000) }
    }

    suspend fun loadRemote(c: Context) = withContext(Dispatchers.IO) {
        if (!DeviceKeys.paired(c)) return@withContext _state.update { it.copy(remote = null, remoteError = "Pair Lemnos first.") }
        try { val l = list(); _state.update { it.copy(remote = l, remoteError = null) } } catch (e: Exception) { _state.update { it.copy(remoteError = e.message ?: "Lemnos didn't answer.") } }
    }

    // ---- restore ----

    /**
     * Downloads [id], checks the phrase against it, verifies every chunk and entry, then stages every store sealed under
     * this phone's keys. Nothing live changes until [restart]. Throws [Phrase.Invalid], [Zbk.WrongKey] or [Zbk.Tampered].
     */
    suspend fun restore(c: Context, id: String, words: String, step: (String) -> Unit): Restored = withContext(Dispatchers.IO) {
        val app = c.applicationContext
        val entropy = Phrase.decode(words)
        val key = Keys.backupKey(entropy)
        val dir = File(app.noBackupFilesDir, "mnemosyne-in").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, "backup.zbk")
        try {
            step("Downloading")
            http.newCall(Request.Builder().url("${Gateway.BASE}/api/backup/$id").build()).execute().use { r ->
                if (!r.isSuccessful) fail(r)
                f.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            }
            f.inputStream().use { Zbk.reader(it, key) }      // the phrase is checked before anything else is read
            step("Checking")
            val open = { Zbk.reader(f.inputStream().buffered(1 shl 16), key).second }
            val restorer = Restorer(Layout(app), KeystoreBank(), { KeystoreBoxKeys().publicKey() }, Keys.escrowKey(entropy))
            val v = restorer.verify(open)
            step("Sealing on this phone")
            val rep = restorer.stage(open, v)
            finishSetup(app, entropy)
            val done = Restored(System.currentTimeMillis(), id, rep.prefs, rep.dbs, rep.files, rep.calypso, rep.calypsoLost, rep.failed)
            sp(app).edit().putString("restored", JSONObject().put("at", done.at).put("id", id).put("prefs", done.prefs).put("dbs", done.dbs).put("files", done.files)
                .put("calypso", done.calypso).put("calypso_lost", done.calypsoLost).put("failed", org.json.JSONArray(done.failed)).toString())
                .putBoolean("restored.show", true).commit()
            done
        } finally {
            key.fill(0); entropy.fill(0)
            dir.deleteRecursively()
        }
    }

    /** Applies the staged restore: the process restarts and [RestoreGate] moves the stores in before anything opens them. */
    fun restart(c: Context) {
        val i = c.packageManager.getLaunchIntentForPackage(c.packageName) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra(dev.phonerecall.MainActivity.EXTRA_DEST, dev.phonerecall.Dest.SETTINGS.name)
        c.startActivity(i)
        Runtime.getRuntime().exit(0)
    }

    /** Settings opens Mnemosyne by itself once after a restore, to show what's left to do. */
    fun takeShowRestored(c: Context): Boolean = sp(c).getBoolean("restored.show", false).also { if (it) sp(c).edit().remove("restored.show").apply() }

    fun dismissRestored(c: Context) { sp(c).edit().remove("restored").apply(); refresh(c) }

    // ---- nightly ----

    fun schedule(c: Context, now: LocalDateTime = LocalDateTime.now(), policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP) {
        val next = now.toLocalDate().atTime(3, 0).let { if (it.isAfter(now)) it else it.plusDays(1) }
        // Daily by default; weekly when Horologion says so.
        val req = PeriodicWorkRequestBuilder<MnemosyneWorker>(dev.phonerecall.horologion.PhoneJobs.minutes(c, WORK), TimeUnit.MINUTES)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiredNetworkType(NetworkType.UNMETERED).setRequiresDeviceIdle(true).build())
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, policy, req)
    }

    /** The next 3 AM: when the job becomes eligible (it then waits for charging, Wi-Fi and idle). */
    fun nextRun(now: LocalDateTime = LocalDateTime.now()): LocalDateTime = now.toLocalDate().atTime(3, 0).let { if (it.isAfter(now)) it else it.plusDays(1) }

    fun refresh(c: Context) {
        val app = c.applicationContext
        val last = sp(app).getString("last", null)?.let { s ->
            runCatching { JSONObject(s).let { LastRun(it.getLong("at"), it.getBoolean("ok"), it.optLong("size"), it.optString("id").takeIf { x -> x.isNotEmpty() && x != "null" }, it.optString("error").takeIf { x -> x.isNotEmpty() && x != "null" }, it.optString("kind")) } }.getOrNull()
        }
        val restored = sp(app).getString("restored", null)?.let { s ->
            runCatching {
                JSONObject(s).let { o ->
                    Restored(o.getLong("at"), o.getString("id"), o.optInt("prefs"), o.optInt("dbs"), o.optInt("files"), o.optInt("calypso"), o.optInt("calypso_lost"),
                        o.optJSONArray("failed")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty())
                }
            }.getOrNull()
        }
        val items = runCatching { VaultCore.get(app).calypso.items().map { it.id } }.getOrDefault(emptyList())
        val esc = escrowed(app).keys
        _state.update {
            it.copy(
                setUp = isSetUp(app), paired = runCatching { DeviceKeys.paired(app) }.getOrDefault(false), last = last, calypso = sp(app).getBoolean("calypso", false),
                calypsoTotal = items.size, calypsoEscrowed = items.count { id -> id in esc }, restored = restored,
                nextRun = if (isSetUp(app)) nextRun().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() else null,
            )
        }
    }
}

class MnemosyneWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result {
        if (!Mnemosyne.isSetUp(applicationContext)) return Result.success()
        val r = Mnemosyne.run(applicationContext, "nightly")
        return if (r.ok || runAttemptCount >= 2) Result.success() else Result.retry()
    }
}

/**
 * Runs first in every Zeus process (initOrder high, before any receiver, service or activity): moves a staged restore
 * into place while no store has a file open, and sets Calypso's escrow tap. Never queried.
 */
class RestoreGate : ContentProvider() {
    override fun onCreate(): Boolean {
        val c = context ?: return true
        val restored = runCatching { Stage.apply(Layout(c)).also { if (it) android.util.Log.i("Mnemosyne", "restore applied") } }.onFailure { android.util.Log.e("Mnemosyne", "restore failed", it) }.getOrDefault(false)
        // The fresh start: once per version, before any store opens (a restore just moved in is never wiped).
        runCatching { dev.phonerecall.fresh.FreshStart.gate(c, restored) }.onFailure { android.util.Log.e("Zeus", "fresh start failed", it) }
        runCatching { Mnemosyne.installTap(c) }
        return true
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
}
