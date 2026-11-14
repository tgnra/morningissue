package net.tgnra.morningissue.relay

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Stands in for the Android Keystore: AES-GCM with a key held in memory. [lost] makes every
 * unwrap fail, as after the app's data is restored onto another phone.
 */
class FakeKeyWrapper : KeyWrapper {
    private val key = RelayCrypto.randomBytes(32)
    var lost = false

    override fun wrap(plain: ByteArray): ByteArray {
        val iv = RelayCrypto.randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (lost) throw GeneralSecurityException("lost")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, wrapped, 0, 12))
        return cipher.doFinal(wrapped, 12, wrapped.size - 12)
    }
}
