package net.tgnra.morningissue.relay

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps a paired device's keys at rest. [wrap]'s output is what `sync_peer` stores, in
 * base64url; [unwrap] gives the key back, or throws [GeneralSecurityException] when it cannot
 * (the wrapping key is gone, as after the app's data is restored onto another phone).
 */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray

    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * [KeyWrapper] with an AES-256-GCM key in the Android Keystore, made on first use. The key
 * never leaves the Keystore, so a copy of the database alone cannot sign or decrypt anything.
 * Output is the 12-byte IV followed by the ciphertext and tag.
 */
class AndroidKeystoreWrapper(private val alias: String = ALIAS) : KeyWrapper {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (wrapped.size <= IV_BYTES) throw GeneralSecurityException("too short")
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val key = store.getKey(alias, null) as? SecretKey ?: throw GeneralSecurityException("no wrapping key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, IV_BYTES))
        return cipher.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "morningissue-relay-keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
