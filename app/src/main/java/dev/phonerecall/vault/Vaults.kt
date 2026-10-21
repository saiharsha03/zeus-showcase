package dev.phonerecall.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Who is asking. Every agent read or deposit is logged under one of these names. */
enum class Agent(val word: String, val athena: Boolean) {
    OWNER("The owner", false),
    ZEUS("Zeus", false),
    DELPHI("Athena (phone)", true),
    ATHENA("Athena", true),
    CIRCLE("Athena's circle", false),
    IRIS("Iris", false),
    ATLAS("Atlas", false);

    companion object {
        /** A depositor named by the gateway drop. Unknown names stay as written, capped. */
        fun word(from: String): String = entries.firstOrNull { it.name.equals(from, true) || it.word.equals(from, true) }?.word ?: from.take(24)
    }
}

/**
 * The only thing a depositor gets: put something in, get an opaque id back. No list, no read, no delete.
 * Agents, tools and the gateway see Calypso through this and nothing else.
 */
fun interface CalypsoDrop {
    fun deposit(from: Agent, kind: ItemKind, label: String, data: ByteArray, name: String?): String
}

/**
 * The fingerprint check. On the phone it is BiometricPrompt (strong biometrics or the device PIN); tests
 * pass a fake. `crypto` is the Keystore cipher the prompt must authorise, or null for a plain check.
 */
interface Biometric {
    fun authenticate(title: String, subtitle: String, crypto: Unwrapper?, onOk: () -> Unit, onFail: (String) -> Unit)
}

/**
 * Calypso, the owner's side. Deposits need nothing; opening, moving and deleting need a fresh fingerprint,
 * and opening can't happen without one: the unwrapping key only works for the prompt it was handed to.
 */
class Calypso(private val db: VaultDb, private val keys: BoxKeys, private val clock: () -> Long = System::currentTimeMillis) {

    /** For depositors: a plain lambda, so nobody holding it can reach the rest of this class. */
    val drop: CalypsoDrop = CalypsoDrop { from, kind, label, data, name -> deposit(from, kind, label, data, name) }

    private fun deposit(from: Agent, kind: ItemKind, label: String, data: ByteArray, name: String?): String {
        val e = Box.seal(keys.publicKey(), kind, label, from.word, data, name, clock())
        db.putSealed(e)
        db.log(clock(), CALYPSO, from.word, "deposited", e.label)
        return e.id
    }

    /** A drop that arrived through Lemnos, already sealed by the sender. Bad or duplicate envelopes are skipped. */
    fun accept(e: Envelope): Boolean {
        if (e.wk.isEmpty() || e.iv.size != 12 || e.ct.size < 18 || e.ct.size > Box.MAX_BYTES + 512) return false
        val ok = db.putSealed(e)
        if (ok) db.log(clock(), CALYPSO, Agent.word(e.from), "deposited", e.label, "through Lemnos")
        return ok
    }

    fun publicPem(): String = Box.pem(keys.publicKey())

    fun items(): List<SealedItem> = db.sealedItems()

    /** Asks for his fingerprint, bound to this one item's key, then opens it. Nothing is cached. */
    fun open(id: String, bio: Biometric, onOk: (SealedItem, Opened) -> Unit, onFail: (String) -> Unit) {
        val e = db.envelope(id) ?: return onFail("That item is gone.")
        val u = try { keys.unwrapper() } catch (ex: Exception) { return onFail("Calypso's key can't be used: ${ex.javaClass.simpleName}") }
        bio.authenticate("Open ${e.label}", "Only you can open what's in Calypso", u, {
            try {
                val o = Box.open(e, u)
                db.log(clock(), CALYPSO, Agent.OWNER.word, "opened", e.label)
                onOk(SealedItem(e.id, e.kind, e.label, e.from, e.at, e.ct.size), o)
            } catch (ex: Exception) { onFail("It didn't open (${ex.javaClass.simpleName}).") }
        }, onFail)
    }

    /** After an open: he copied it, saved the file, and so on. */
    fun note(label: String, action: String) = db.log(clock(), CALYPSO, Agent.OWNER.word, action, label)

    /** Moves an item to Aegis, where agents may use it. His fingerprint opens it first; it then leaves Calypso. */
    fun moveToAegis(id: String, aegis: Aegis, name: String, scope: AegisScope, bio: Biometric, onDone: () -> Unit, onFail: (String) -> Unit) =
        open(id, bio, { item, o ->
            aegis.put(name, o.text, item.label, scope, via = "moved from Calypso")
            db.deleteSealed(id)
            db.log(clock(), CALYPSO, Agent.OWNER.word, "moved to Aegis", item.label)
            onDone()
        }, onFail)

    /** Deleting is his too, behind a plain fingerprint check. */
    fun delete(id: String, bio: Biometric, onDone: () -> Unit, onFail: (String) -> Unit) {
        val label = db.sealedItems().firstOrNull { it.id == id }?.label ?: return onFail("That item is gone.")
        bio.authenticate("Delete $label", "It can't be brought back", null, {
            db.deleteSealed(id)
            db.log(clock(), CALYPSO, Agent.OWNER.word, "deleted", label)
            onDone()
        }, onFail)
    }

    fun log(): List<VaultLogRow> = db.logRows(CALYPSO)

    companion object { const val CALYPSO = "calypso" }
}

enum class AegisScope(val wire: String, val word: String) {
    ATHENA("athena", "Athena only"),
    AGENTS("agents", "Athena and workers");

    companion object { fun of(wire: String?): AegisScope = entries.firstOrNull { it.wire == wire } ?: ATHENA }
}

sealed interface AegisResult {
    data class Value(val name: String, val value: String) : AegisResult
    data class Refused(val reason: String) : AegisResult
}

/** What agents may do with Aegis: list names, read one with a reason. Both logged. No writes. */
interface AegisReads {
    fun agentList(who: Agent): List<AegisItem>
    fun agentRead(name: String, who: Agent, why: String): AegisResult
}

/**
 * Aegis: private from people, usable by agents. Values are sealed with a Keystore key that needs no
 * fingerprint, so Athena can read one in the background; the screen that shows them needs his
 * fingerprint. Every agent read (and every refusal) lands in the log he sees.
 */
class Aegis(private val db: VaultDb, private val clock: () -> Long = System::currentTimeMillis) : AegisReads {

    fun put(name: String, value: String, note: String, scope: AegisScope, via: String = "") {
        val n = name.trim().take(60)
        require(n.isNotEmpty() && value.isNotEmpty()) { "name and value are needed" }
        val existed = db.aegis(n) != null
        db.putAegis(n, value, note.trim().take(160), scope, clock())
        db.log(clock(), AEGIS, Agent.OWNER.word, if (existed) "changed" else "added", n, via)
    }

    fun items(): List<AegisItem> = db.aegisItems()

    /** For his screen, behind the gate. Logged. */
    fun reveal(name: String): String? = db.aegisValue(name)?.also { db.log(clock(), AEGIS, Agent.OWNER.word, "viewed", name) }

    fun revoke(name: String, revoked: Boolean) {
        db.setAegis(name, revoked = revoked)
        db.log(clock(), AEGIS, Agent.OWNER.word, if (revoked) "revoked agents" else "allowed agents", name)
    }

    fun scope(name: String, scope: AegisScope) {
        db.setAegis(name, scope = scope)
        db.log(clock(), AEGIS, Agent.OWNER.word, "scope: ${scope.word}", name)
    }

    fun remove(name: String) { if (db.deleteAegis(name)) db.log(clock(), AEGIS, Agent.OWNER.word, "deleted", name) }

    private fun allowed(item: AegisItem, who: Agent) = !item.revoked && (who.athena || item.scope == AegisScope.AGENTS)

    override fun agentList(who: Agent): List<AegisItem> = db.aegisItems().filter { allowed(it, who) }

    override fun agentRead(name: String, who: Agent, why: String): AegisResult {
        val reason = why.trim().ifEmpty { "no reason given" }
        val item = db.aegis(name.trim())
        val refusal = when {
            item == null -> "There is no Aegis item called $name."
            item.revoked -> "The owner has turned agent use off for $name."
            !allowed(item, who) -> "$name is for Athena only."
            else -> null
        }
        if (refusal != null) {
            db.log(clock(), AEGIS, who.word, "refused", name.take(60), reason)
            return AegisResult.Refused(refusal)
        }
        val v = db.aegisValue(item!!.name) ?: return AegisResult.Refused("$name can't be opened on this phone.")
        db.used(item.name)
        db.log(clock(), AEGIS, who.word, "used", item.name, reason)
        return AegisResult.Value(item.name, v)
    }

    fun log(): List<VaultLogRow> = db.logRows(AEGIS)

    /** Back into Calypso (sealed to the box key), and out of Aegis. The owner-only: the Aegis screen is behind the gate. */
    fun moveToCalypso(name: String, drop: CalypsoDrop): Boolean {
        val v = db.aegisValue(name) ?: return false
        drop.deposit(Agent.OWNER, ItemKind.SECRET, name, v.toByteArray(), null)
        db.deleteAegis(name)
        db.log(clock(), AEGIS, Agent.OWNER.word, "moved to Calypso", name)
        return true
    }

    companion object { const val AEGIS = "aegis" }
}

/**
 * The lock on a fingerprint screen (Calypso, Aegis, Moneta). Opens only on a successful prompt; locks
 * again when the screen goes off, or after 60 s in the background.
 */
class VaultGate(private val clock: () -> Long = System::currentTimeMillis, private val relockMs: Long = 60_000L) {
    var unlocked by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    private var stoppedAt = 0L

    fun unlock(bio: Biometric, title: String, subtitle: String, onOpen: () -> Unit = {}) {
        failure = null
        bio.authenticate(title, subtitle, null, { unlocked = true; onOpen() }, { failure = it; unlocked = false })
    }

    fun stopped(screenOff: Boolean) { stoppedAt = clock(); if (screenOff) lock() }

    fun started() { if (stoppedAt > 0 && clock() - stoppedAt > relockMs) lock() }

    fun lock() { unlocked = false }
}
