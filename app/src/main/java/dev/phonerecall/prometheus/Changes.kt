package dev.phonerecall.prometheus

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.app.PendingIntent
import androidx.compose.runtime.Immutable
import dev.phonerecall.lemnos.Gateway
import dev.phonerecall.lemnos.GatewayException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/*
 * App-change proposals (code). The phone only writes the request and, later, installs a build he chose.
 *
 * Where the build happens: on the owner's laptop, not the VPS. Zeus's source and the key that signs it live
 * there, so a request is queued on Lemnos (signed with his fingerprint) and waits for the laptop session to
 * pick it up when he runs `tools/prometheus.py` by hand. The laptop builds and tests in a fresh worktree and
 * publishes the patch, the test results and the APK back to Lemnos. Lemnos never holds the source or the
 * signing key, so nothing on the VPS can make an APK this phone would accept: [Signer] compares the APK's
 * signing certificate with the installed app's before the Install button appears, and Android checks it again.
 * No agent can install: the phone downloads over mTLS and hands the file to the system installer, where he
 * confirms in Android's own dialog.
 */

enum class ChangeStatus(val word: String) {
    QUEUED("Queued"), BUILDING("Building"), READY("Update ready"), FAILED("Build failed"), CANCELLED("Cancelled"), INSTALLED("Installed");
    companion object { fun of(s: String?): ChangeStatus = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: QUEUED }
}

@Immutable
data class ChangeRequest(
    val id: String,
    val problem: String,
    val status: ChangeStatus,
    val created: Long,
    val updated: Long,
    val summary: String = "",
    val filesChanged: Int = 0,
    val insertions: Int = 0,
    val deletions: Int = 0,
    val testsPassed: Int = 0,
    val testsFailed: Int = 0,
    val testsLine: String = "",
    val versionName: String? = null,
    val versionCode: Long? = null,
    val apkSha256: String? = null,
    val apkBytes: Long = 0,
    val error: String? = null,
) {
    val installable: Boolean get() = status == ChangeStatus.READY && apkSha256 != null && testsFailed == 0
}

/** Lemnos's Prometheus routes (docs/ZEUS-GATEWAY.md, "Prometheus"). Blocking; call on IO. */
object ChangeNet {
    private val JSON = "application/json".toMediaType()

    private fun call(req: Request): okhttp3.Response {
        val r = Gateway.client.newCall(req).execute()
        if (!r.isSuccessful) {
            val detail = runCatching { JSONObject(r.body?.string() ?: "").optString("detail") }.getOrNull().orEmpty()
            r.close()
            throw GatewayException(r.code, detail.ifEmpty { "Lemnos answered ${r.code}" })
        }
        return r
    }

    /** The approval token is signed on the phone (kind prometheus.request, executor zeus-gateway, payload {id, problem}). */
    fun submit(id: String, problem: String, approval: String): ChangeRequest {
        val body = JSONObject().put("id", id).put("problem", problem).put("approval", approval).toString().toRequestBody(JSON)
        return call(Request.Builder().url("${Gateway.BASE}/api/prometheus/requests").post(body).build()).use { parse(JSONObject(it.body!!.string())) }
    }

    fun list(): List<ChangeRequest> = call(Request.Builder().url("${Gateway.BASE}/api/prometheus/requests").build()).use { r ->
        val a = JSONArray(r.body!!.string())
        (0 until a.length()).map { parse(a.getJSONObject(it)) }
    }

    fun cancel(id: String) { call(Request.Builder().url("${Gateway.BASE}/api/prometheus/requests/$id/cancel").post("{}".toRequestBody(JSON)).build()).close() }

    /** Streams the APK into [out], checking its SHA-256 against what Lemnos listed. Returns the byte count. */
    fun download(c: ChangeRequest, out: File, limit: Long = 200L * 1024 * 1024): Long {
        val want = c.apkSha256 ?: throw IllegalStateException("No build to download")
        val md = MessageDigest.getInstance("SHA-256")
        var n = 0L
        call(Request.Builder().url("${Gateway.BASE}/api/prometheus/requests/${c.id}/apk").build()).use { r ->
            out.outputStream().use { o ->
                val input = r.body!!.byteStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val k = input.read(buf); if (k < 0) break
                    n += k; if (n > limit) throw IllegalStateException("The APK is larger than ${limit / 1024 / 1024} MB")
                    md.update(buf, 0, k); o.write(buf, 0, k)
                }
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (!got.equals(want, ignoreCase = true)) { out.delete(); throw IllegalStateException("The download doesn't match the build Lemnos listed") }
        return n
    }

    fun parse(o: JSONObject): ChangeRequest {
        val b = o.optJSONObject("build") ?: JSONObject()
        val t = b.optJSONObject("tests") ?: JSONObject()
        val d = b.optJSONObject("diff") ?: JSONObject()
        fun s(x: JSONObject, k: String) = if (x.isNull(k) || !x.has(k)) null else x.optString(k)
        return ChangeRequest(
            id = o.optString("id"), problem = o.optString("problem"), status = ChangeStatus.of(o.optString("status")),
            created = (o.optDouble("created", 0.0) * 1000).toLong(), updated = (o.optDouble("updated", 0.0) * 1000).toLong(),
            summary = b.optString("summary").take(2000), filesChanged = d.optInt("files"), insertions = d.optInt("insertions"), deletions = d.optInt("deletions"),
            testsPassed = t.optInt("passed"), testsFailed = t.optInt("failed"), testsLine = t.optString("line").take(300),
            versionName = s(b, "version_name"), versionCode = if (b.has("version_code")) b.optLong("version_code") else null,
            apkSha256 = s(b, "apk_sha256"), apkBytes = b.optLong("apk_bytes"), error = s(o, "error"),
        )
    }
}

/** The signing check before any Install button. Pure part first, Android part below. */
object Signer {
    sealed class Verdict {
        data object Ok : Verdict()
        data class Bad(val reason: String) : Verdict()
    }

    /**
     * Same package, the same non-empty set of signing certificates (SHA-256), and not an older version.
     * A build signed by any other key, or with an extra signer, is refused.
     */
    fun verdict(ownPackage: String, installed: Set<String>, apkPackage: String?, apk: Set<String>, installedVersion: Long, apkVersion: Long?): Verdict = when {
        apkPackage == null -> Verdict.Bad("Not an APK Android can read")
        apkPackage != ownPackage -> Verdict.Bad("This build is for $apkPackage, not Zeus")
        installed.isEmpty() || apk.isEmpty() -> Verdict.Bad("No signing certificate to compare")
        installed != apk -> Verdict.Bad("Signed with a different key than the app on this phone")
        apkVersion == null || apkVersion < installedVersion -> Verdict.Bad("Older than the installed version")
        else -> Verdict.Ok
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun check(context: Context, apk: File): Verdict {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val own = pm.getPackageInfo(context.packageName, flags)
        val other = pm.getPackageArchiveInfo(apk.path, flags)
        fun certs(info: android.content.pm.PackageInfo?): Set<String> {
            val si = info?.signingInfo ?: return emptySet()
            val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory?.takeLast(1)?.toTypedArray()
            return sigs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
        }
        return verdict(context.packageName, certs(own), other?.packageName, certs(other), own.longVersionCode, other?.longVersionCode)
    }
}

/**
 * Hands a checked APK to Android's package installer. The app has no install privilege, so Android always
 * shows its own confirmation; [InstallReceiver] opens that dialog. Nothing installs silently.
 */
object Installer {
    const val ACTION = "dev.phonerecall.prometheus.INSTALL_STATUS"

    fun install(context: Context, apk: File, changeId: String) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val sid = pi.createSession(params)
        pi.openSession(sid).use { s ->
            s.openWrite("zeus.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val status = Intent(ACTION).setPackage(context.packageName).putExtra("change", changeId)
            val p = PendingIntent.getBroadcast(context, sid, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(p.intentSender)
        }
    }
}

/** The installer's status: open Android's confirmation when it asks for one, log the outcome. */
class InstallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Installer.ACTION) return
        val change = intent.getStringExtra("change").orEmpty()
        when (val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                PrometheusCore.get(context).logInstall(change, "asked Android to confirm")
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> PrometheusCore.get(context).logInstall(change, "installed")
            else -> PrometheusCore.get(context).logInstall(change, "not installed (status $st): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}")
        }
    }
}
