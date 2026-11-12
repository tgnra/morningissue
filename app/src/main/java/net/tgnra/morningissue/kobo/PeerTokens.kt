package net.tgnra.morningissue.kobo

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import net.tgnra.morningissue.data.db.SyncPeerEntity

/**
 * The bearer tokens paired Kobos sync with. A token is 32 random bytes in base64url; the phone
 * keeps only its SHA-256 (`sync_peer.tokenHash`), so a copy of the database cannot be used to
 * sync.
 */
object PeerTokens {
    private val random = SecureRandom()

    fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** The SHA-256 of [token]'s UTF-8 bytes, in lowercase hex: what `sync_peer.tokenHash` holds. */
    fun hash(token: String): String = digest(token).joinToString("") { "%02x".format(it) }

    /** The token of an `Authorization: Bearer <token>` header, or null for anything else. */
    fun bearer(header: String?): String? {
        val parts = header?.trim()?.split(' ', limit = 2) ?: return null
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        return parts[1].trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The peer whose token is [token], or null. Every peer is compared, each in constant time
     * ([MessageDigest.isEqual] on the hashes), so how long the check takes says nothing about
     * how close a guess came or which peer matched.
     */
    fun match(token: String?, peers: List<SyncPeerEntity>): SyncPeerEntity? {
        if (token == null) return null
        val presented = digest(token)
        var found: SyncPeerEntity? = null
        for (peer in peers) {
            val stored = peer.tokenHash?.let(::unhex) ?: continue
            if (MessageDigest.isEqual(presented, stored) && found == null) found = peer
        }
        return found
    }

    private fun digest(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())

    private fun unhex(text: String): ByteArray? {
        if (text.length != 64) return null
        return ByteArray(32) { i ->
            val high = Character.digit(text[2 * i], 16)
            val low = Character.digit(text[2 * i + 1], 16)
            if (high < 0 || low < 0) return null
            (high * 16 + low).toByte()
        }
    }
}
