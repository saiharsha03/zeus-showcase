package dev.phonerecall.mnemosyne

import dev.phonerecall.vault.Box
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Calypso in backups, only when "Include Calypso" is on (docs/MNEMOSYNE.md).
 *
 * A Calypso item is sealed to Calypso's Keystore RSA key, which needs his fingerprint for every single use and can't
 * leave the phone, so a backup can't carry it. Instead each item's own AES key is escrowed: wrapped (RSA-OAEP) to an
 * escrow public key whose private half is stored only encrypted under a key derived from the recovery phrase. The
 * phone can add items to the escrow with no prompt, but can't open them without the phrase, so on the phone Calypso
 * stays fingerprint-only. An item's key is in hand at two moments: when it is sealed in the app, and when he opens it
 * with his fingerprint; [Box.keyTap] escrows it then. Drops from Iris and Atlas join the escrow the first time he
 * opens them (or with "Add older items", one fingerprint each). On a new phone the phrase opens the escrow key and
 * every escrowed item is wrapped again to the new phone's Calypso key; the item's content (iv, ct) is unchanged.
 */
object Escrow {
    class Pair(val publicDer: ByteArray, val sealedPrivate: ByteArray)

    fun newPair(escrowKey: ByteArray, random: SecureRandom = SecureRandom()): Pair {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(3072, random) }.generateKeyPair()
        return Pair(kp.public.encoded, sealPrivate(kp.private.encoded, escrowKey, random))
    }

    private fun sealPrivate(pkcs8: ByteArray, escrowKey: ByteArray, random: SecureRandom): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(escrowKey, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD("zeus/mnemosyne/v1 escrow".toByteArray())
        return iv + c.doFinal(pkcs8)
    }

    /** Throws when the phrase is wrong or the blob was changed. */
    fun openPrivate(sealed: ByteArray, escrowKey: ByteArray): PrivateKey {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(escrowKey, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        c.updateAAD("zeus/mnemosyne/v1 escrow".toByteArray())
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(c.doFinal(sealed, 12, sealed.size - 12)))
    }

    fun publicKey(der: ByteArray): PublicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))

    fun wrap(pub: PublicKey, itemKey: ByteArray): ByteArray =
        Cipher.getInstance(Box.RSA).apply { init(Cipher.ENCRYPT_MODE, pub, Box.OAEP) }.doFinal(itemKey)

    fun unwrap(priv: PrivateKey, wrapped: ByteArray): ByteArray =
        Cipher.getInstance(Box.RSA).apply { init(Cipher.DECRYPT_MODE, priv, Box.OAEP) }.doFinal(wrapped)

    /** On the new phone: the item key from the escrow, wrapped again to this phone's Calypso key. */
    fun rewrap(priv: PrivateKey, escrowed: ByteArray, newCalypso: PublicKey): ByteArray {
        val k = unwrap(priv, escrowed)
        try { return wrap(newCalypso, k) } finally { k.fill(0) }
    }
}
