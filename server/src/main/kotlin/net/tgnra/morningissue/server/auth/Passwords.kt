package net.tgnra.morningissue.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password hashing with PBKDF2-HMAC-SHA256 from the JDK. A stored hash reads
 * `pbkdf2-sha256$<iterations>$<b64 salt>$<b64 hash>`, so the iteration count can rise later and
 * old hashes still verify.
 *
 * [iterations] is 600,000 in production (OWASP's figure for PBKDF2-SHA256); tests use fewer.
 */
class Passwords(private val iterations: Int = DEFAULT_ITERATIONS, private val random: SecureRandom = SecureRandom()) {
    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(password, salt, iterations)
        return listOf(SCHEME, iterations.toString(), B64.encodeToString(salt), B64.encodeToString(derived)).joinToString("$")
    }

    /** Whether [password] matches [stored]; false for a hash this version cannot read. */
    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != SCHEME) return false
        val rounds = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = runCatching { B64D.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { B64D.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(password, salt, rounds), expected)
    }

    /**
     * Spends the time a [verify] would, for a user name that does not exist, so the answer's
     * timing does not tell which names do.
     */
    fun burn(password: String) {
        derive(password, DUMMY_SALT, iterations)
    }

    /** A new first password: [GENERATED_LENGTH] characters from a 32-symbol alphabet (100 bits). */
    fun generate(): String = buildString {
        repeat(GENERATED_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    private fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, HASH_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val DEFAULT_ITERATIONS = 600_000
        const val GENERATED_LENGTH = 20

        /** Lower-case letters without `l` and `o`, and the digits 2 to 9: easy to read and type on a phone. */
        const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
        private const val SCHEME = "pbkdf2-sha256"
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
        private val DUMMY_SALT = ByteArray(SALT_BYTES)
        private val B64 = Base64.getEncoder().withoutPadding()
        private val B64D = Base64.getDecoder()
    }
}
