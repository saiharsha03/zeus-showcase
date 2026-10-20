package dev.phonerecall.vault

import dev.phonerecall.brain.FakeSealer
import org.robolectric.RuntimeEnvironment
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import javax.crypto.Cipher

/** Opens wrapped keys with a software private key, but only after the fake prompt authorised this one object. */
class FakeUnwrapper(private val priv: PrivateKey) : Unwrapper {
    var authorized = false
    override fun unwrap(wrapped: ByteArray): ByteArray {
        check(authorized) { "Keystore: user not authenticated" }
        return Cipher.getInstance(Box.RSA).apply { init(Cipher.DECRYPT_MODE, priv, Box.OAEP) }.doFinal(wrapped)
    }
}

/** Calypso's keypair in software. Every unwrapper starts locked, like a Keystore key with auth timeout 0. */
class FakeKeys(val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()) : BoxKeys {
    val made = ArrayList<FakeUnwrapper>()
    override fun publicKey(): PublicKey = pair.public
    override fun unwrapper(): Unwrapper = FakeUnwrapper(pair.private).also { made += it }
}

/** BiometricPrompt stand-in: succeeds or fails as told, and authorises the crypto object only on success. */
class FakeBiometric(var succeed: Boolean = true) : Biometric {
    val prompts = ArrayList<String>()
    override fun authenticate(title: String, subtitle: String, crypto: Unwrapper?, onOk: () -> Unit, onFail: (String) -> Unit) {
        prompts += title
        if (succeed) { (crypto as? FakeUnwrapper)?.authorized = true; onOk() } else onFail("Fingerprint not recognised")
    }
}

class VaultRig(var now: Long = 1_792_000_000_000L) {
    val db = VaultDb(RuntimeEnvironment.getApplication(), FakeSealer(), name = null)
    val keys = FakeKeys()
    val calypso = Calypso(db, keys) { now }
    val aegis = Aegis(db) { now }
    val tools = VaultTools(calypso.drop, aegis)
    val bio = FakeBiometric()
}
