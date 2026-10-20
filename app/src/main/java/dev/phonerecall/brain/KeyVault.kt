package dev.phonerecall.brain

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Calypso's keys. The Anthropic key is encrypted with an AES-256-GCM key that lives in Android
 * Keystore (StrongBox when the phone has it) and never leaves it. The Keystore key does not require
 * user authentication, so the brain can use the API key with no prompt; seeing or changing it is
 * behind the fingerprint in the Calypso screen. Only ciphertext is stored, in app-private prefs that
 * are excluded from backup. Call on IO.
 */
class KeyVault(context: Context) {
    private val sp = context.getSharedPreferences("calypso", Context.MODE_PRIVATE)
    @Volatile private var cached: String? = null

    private fun secretKey(): SecretKey = ensureKey()

    fun has(): Boolean = sp.contains(CIPHER)

    /** The key in full, for the brain's requests only. Never shown. */
    fun read(): String? {
        cached?.let { return it }
        val ct = sp.getString(CIPHER, null) ?: return null
        val iv = sp.getString(IV, null) ?: return null
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8).also { cached = it }
        } catch (_: Exception) { null }
    }

    /** Add or rotate. */
    fun save(key: String) {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = c.doFinal(key.trim().toByteArray(Charsets.UTF_8))
        sp.edit()
            .putString(CIPHER, Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putLong(SAVED, System.currentTimeMillis())
            .commit()
        cached = key.trim()
    }

    fun delete() {
        sp.edit().remove(CIPHER).remove(IV).remove(SAVED).commit()
        cached = null
    }

    fun savedAt(): Long? = sp.getLong(SAVED, 0L).takeIf { it > 0 }

    companion object {
        /** The Calypso AES key, made on first use (StrongBox when the phone has it). */
        fun ensureKey(): SecretKey {
            val ks = KeyStore.getInstance(STORE).apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            fun make(strongBox: Boolean): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setIsStrongBoxBacked(strongBox)
                        .build(),
                )
            }.generateKey()
            return try { make(true) } catch (_: Exception) { make(false) }
        }

        private const val STORE = "AndroidKeyStore"
        private const val ALIAS = "zeus.calypso.keys"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val CIPHER = "anthropic.ct"
        private const val IV = "anthropic.iv"
        private const val SAVED = "anthropic.saved"

        /** Never the key in full: the prefix and the last four, with the middle hidden. */
        fun mask(key: String, reveal: Boolean = false): String {
            val k = key.trim()
            if (k.length < 12) return "•".repeat(8)
            val prefix = if (reveal) k.take(k.indexOf('-', 7).takeIf { it in 7..14 }?.plus(1) ?: 7) else ""
            return prefix + "•".repeat(if (reveal) 12 else 8) + k.takeLast(4)
        }
    }
}

/**
 * Another sealed key in Calypso, under the same Keystore key as the Anthropic one (phase 3: the Hetzner API
 * token). Stored as ciphertext only; read only by the code that uses it, never shown in full. Call on IO.
 */
class SealedSecret(context: Context, private val slot: String) {
    private val sp = context.getSharedPreferences("calypso", Context.MODE_PRIVATE)

    private fun key(): SecretKey = KeyVault.ensureKey()

    fun has(): Boolean = sp.contains("$slot.ct")

    fun read(): String? {
        val ct = sp.getString("$slot.ct", null) ?: return null
        val iv = sp.getString("$slot.iv", null) ?: return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    fun save(value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        sp.edit().putString("$slot.ct", Base64.encodeToString(ct, Base64.NO_WRAP)).putString("$slot.iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putLong("$slot.saved", System.currentTimeMillis()).commit()
    }

    fun delete() { sp.edit().remove("$slot.ct").remove("$slot.iv").remove("$slot.saved").commit() }
    fun savedAt(): Long? = sp.getLong("$slot.saved", 0L).takeIf { it > 0 }
}

/** Brain settings in Themis. Auto-approve starts empty, the cap at $100, Delphi's model on Auto (Haiku, escalating to Sonnet). */
data class BrainSettings(
    val model: BrainModel = BrainModel.DEFAULT,
    /** Delphi on Auto: Haiku 5.5 for quick turns, Sonnet 5.5 when the turn needs more. The picker overrides. */
    val auto: Boolean = true,
    val spend: SpendState = SpendState(SpendState.thisMonth().toString(), 0),
    val autoApprove: Set<ProposalKind> = emptySet(),
    val keySet: Boolean = false,
    val blocked: Int = 0,
)

class BrainPrefs(context: Context) {
    private val sp = context.getSharedPreferences("brain", Context.MODE_PRIVATE)

    fun load(): BrainSettings = BrainSettings(
        model = BrainModel.of(sp.getString("model", null)) ?: BrainModel.DEFAULT,
        auto = sp.getBoolean("model.auto", true),
        spend = SpendState(
            sp.getString("spend.month", SpendState.thisMonth().toString())!!, sp.getLong("spend.micros", 0L),
            // Phase 2c moved the cap to $100 for all of Athena: an older stored $20 is lifted once.
            if (sp.getBoolean("cap.2c", false)) sp.getInt("cap", SpendState.DEFAULT_CAP_USD) else SpendState.DEFAULT_CAP_USD,
            sp.getLong("spend.mind", 0L), sp.getLong("spend.active", 0L), sp.getLong("spend.advisor", 0L),
        ),
        // Only eligible kinds are ever read back, whatever was stored.
        autoApprove = sp.getStringSet("auto", emptySet()).orEmpty().mapNotNull { ProposalKind.of(it) }.filter { it in ProposalKind.autoCandidates }.toSet(),
        blocked = sp.getInt("blocked", 0),
    )

    fun saveModel(m: BrainModel) = sp.edit().putString("model", m.id).putBoolean("model.auto", false).apply()
    fun saveAuto() = sp.edit().putBoolean("model.auto", true).apply()
    fun saveSpend(s: SpendState) = sp.edit().putString("spend.month", s.month).putLong("spend.micros", s.spentMicros).putInt("cap", s.capUsd)
        .putLong("spend.mind", s.mindMicros).putLong("spend.active", s.activeSeconds).putLong("spend.advisor", s.advisorMicros).putBoolean("cap.2c", true).commit()
    fun saveAuto(kinds: Set<ProposalKind>) = sp.edit().putStringSet("auto", kinds.map { it.wire }.toSet()).apply()
    fun saveBlocked(n: Int) = sp.edit().putInt("blocked", n).apply()
}
