package dev.phonerecall.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import dev.phonerecall.brain.KeystoreSealer
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.Gateway

/** BiometricPrompt: strong (Class 3) biometrics, or the device PIN as the fallback. A crypto prompt authorises that one cipher. */
class AndroidBiometric(private val context: Context) : Biometric {
    override fun authenticate(title: String, subtitle: String, crypto: Unwrapper?, onOk: () -> Unit, onFail: (String) -> Unit) {
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title.take(60))
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail(errString.toString())
        }
        val cipher = (crypto as? CipherUnwrapper)?.cipher
        if (cipher != null) prompt.authenticate(BiometricPrompt.CryptoObject(cipher), CancellationSignal(), context.mainExecutor, cb)
        else prompt.authenticate(CancellationSignal(), context.mainExecutor, cb)
    }
}

/** Copies marked sensitive (no clipboard preview) and clears itself after 30 s if it is still ours. */
object SecureClipboard {
    fun copy(context: Context, label: String, text: String, clearMs: Long = 30_000L) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val tag = "Zeus: $label"
        cm.setPrimaryClip(ClipData.newPlainText(tag, text).apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        })
        Handler(Looper.getMainLooper()).postDelayed({
            if (cm.primaryClip?.description?.label == tag) cm.clearPrimaryClip()
        }, clearMs)
    }
}

/**
 * One vault per process: Calypso, Aegis and their tools. The activity, Delphi and Athena's mind use this
 * instance. Agents get [tools] only. Call the IO methods off the main thread.
 */
class VaultCore private constructor(private val app: Context) {
    // Calypso keeps the Keystore-direct format: its sealing stays exactly as it was.
    val db = VaultDb(app, KeystoreSealer(ALIAS, envelope = false))
    val calypso = Calypso(db, KeystoreBoxKeys())
    val aegis = Aegis(db)
    val tools = VaultTools(calypso.drop, aegis)
    private val prefs = app.getSharedPreferences("vault", Context.MODE_PRIVATE)

    /**
     * With Lemnos paired: publish Calypso's public key (so Iris and Atlas can seal to it) and take in what they
     * dropped. The gateway only ever holds ciphertext. Returns how many arrived.
     */
    fun syncDrops(): Int {
        if (!DeviceKeys.paired(app)) return 0
        val pem = calypso.publicPem()
        if (prefs.getInt("pem.sent", 0) != pem.hashCode()) {
            Gateway.calypsoKey(pem)
            prefs.edit().putInt("pem.sent", pem.hashCode()).apply()
        }
        val drops = Gateway.calypsoDrops().mapNotNull { Envelope.parse(it) }
        val got = drops.count { calypso.accept(it) }
        if (drops.isNotEmpty()) Gateway.calypsoAck(drops.map { it.id })
        return got
    }

    companion object {
        /** Calypso's store key (no fingerprint; the shelves' own keys are separate). */
        const val ALIAS = "zeus.aegis.vault"
        @Volatile private var instance: VaultCore? = null
        fun get(context: Context): VaultCore = instance ?: synchronized(this) {
            instance ?: VaultCore(context.applicationContext).also { instance = it }
        }
    }
}
