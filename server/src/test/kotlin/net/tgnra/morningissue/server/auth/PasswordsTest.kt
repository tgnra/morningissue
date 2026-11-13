package net.tgnra.morningissue.server.auth

import java.time.Duration
import net.tgnra.morningissue.sync.MutableClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordsTest {
    private val passwords = Passwords(iterations = 1_000)

    @Test
    fun hashesVerifyAndCarryTheirParameters() {
        val hash = passwords.hash("correct horse battery")
        assertTrue(hash.startsWith("pbkdf2-sha256$1000$"))
        assertTrue(passwords.verify("correct horse battery", hash))
        assertFalse(passwords.verify("correct horse batterz", hash))
        // Another instance with a different count still reads it.
        assertTrue(Passwords(iterations = 5).verify("correct horse battery", hash))
        assertTrue(passwords.hash("x") != passwords.hash("x"))
    }

    @Test
    fun theProductionCountIsTheDesignsAndMalformedHashesFail() {
        assertEquals(600_000, Passwords.DEFAULT_ITERATIONS)
        assertFalse(passwords.verify("x", ""))
        assertFalse(passwords.verify("x", "plain"))
        assertFalse(passwords.verify("x", "pbkdf2-sha256\$0\$AAAA\$AAAA"))
        assertFalse(passwords.verify("x", "pbkdf2-sha256$10\$!!!\$AAAA"))
    }

    @Test
    fun generatedPasswordsAreTwentyCharactersFromThirtyTwoSymbols() {
        assertEquals(32, Passwords.ALPHABET.toSet().size)
        repeat(50) {
            val p = passwords.generate()
            assertEquals(20, p.length)
            assertTrue(p.all { it in Passwords.ALPHABET })
        }
        assertTrue((1..20).map { passwords.generate() }.toSet().size == 20)
    }

    @Test
    fun theRateLimitAllowsFiveFailuresPerNameInFifteenMinutes() {
        val clock = MutableClock()
        val limiter = LoginRateLimiter(clock)
        repeat(5) {
            assertTrue(limiter.allowed("tania"))
            limiter.failed("tania")
        }
        assertFalse(limiter.allowed("tania"))
        assertFalse(limiter.allowed("TANIA"))
        assertTrue(limiter.allowed("sam"))
        assertEquals(Duration.ofMinutes(15), limiter.retryAfter("tania"))
        clock.now = clock.now.plus(Duration.ofMinutes(14))
        assertFalse(limiter.allowed("tania"))
        clock.now = clock.now.plus(Duration.ofMinutes(1))
        assertTrue(limiter.allowed("tania"))
        assertNull(limiter.retryAfter("tania"))

        limiter.failed("sam")
        limiter.succeeded("sam")
        repeat(4) { limiter.failed("sam") }
        assertTrue(limiter.allowed("sam"))
    }
}
