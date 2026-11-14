package net.tgnra.morningissue.sync

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Lenient parsing of the dates feeds really carry: RFC 822 with its many variants (two-digit
 * years, missing seconds, named zones, full day and month names), ISO 8601 / RFC 3339 (space
 * instead of `T`, offsets without a colon, date only), and "January 2, 2006". A missing zone
 * means UTC. Returns null for anything else so the caller can fall back to the fetch time.
 */
object FeedDates {
    fun parse(text: String?): Instant? {
        val s = text?.trim().orEmpty()
        if (s.isEmpty()) return null
        return (iso(s) ?: rfc822(s) ?: monthFirst(s))?.takeIf { !it.isBefore(EARLIEST) }
    }

    private fun iso(s: String): Instant? {
        val m = ISO.find(s) ?: return null
        if (m.range.first != 0) return null
        val g = m.groupValues
        return build(g[1], g[2].toInt(), g[3], g[4], g[5], g[6], offset(g[7], g[8], g[9], g[10]))
    }

    private fun rfc822(s: String): Instant? {
        val g = RFC822.find(s)?.groupValues ?: return null
        val month = MONTHS[g[2].take(3).lowercase()] ?: return null
        return build(g[3], month, g[1], g[4], g[5], g[6], offset("", g[7], g[8], g[9]))
    }

    private fun monthFirst(s: String): Instant? {
        val g = MONTH_FIRST.find(s)?.groupValues ?: return null
        val month = MONTHS[g[1].take(3).lowercase()] ?: return null
        return build(g[3], month, g[2], g[4], g[5], g[6], offset("", g[7], g[8], g[9]))
    }

    private fun build(
        year: String,
        month: Int,
        day: String,
        hour: String,
        minute: String,
        second: String,
        offset: ZoneOffset?,
    ): Instant? {
        offset ?: return null
        var y = year.toInt()
        if (year.length <= 2) y += if (y < 70) 2000 else 1900
        return runCatching {
            LocalDateTime.of(y, month, day.toInt(), hour.toIntOrNull() ?: 0, minute.toIntOrNull() ?: 0, second.toIntOrNull() ?: 0)
                .toInstant(offset)
        }.getOrNull()
    }

    /** Null only when a numeric offset is out of range. Unknown zone names count as UTC. */
    private fun offset(zulu: String, hours: String, minutes: String, name: String): ZoneOffset? = when {
        zulu.isNotEmpty() -> ZoneOffset.UTC
        hours.isNotEmpty() -> {
            val sign = if (hours.startsWith("-")) -1 else 1
            val seconds = sign * (hours.drop(1).toInt() * 3600 + (minutes.toIntOrNull() ?: 0) * 60)
            runCatching { ZoneOffset.ofTotalSeconds(seconds) }.getOrNull()
        }
        else -> ZoneOffset.ofTotalSeconds(((ZONES[name.uppercase()] ?: 0.0) * 3600).toInt())
    }

    /** Guards against epoch-zero and `0001-01-01` placeholders. */
    private val EARLIEST: Instant = Instant.parse("1971-01-01T00:00:00Z")

    private const val TIME = """(?:[\sT,]+(?:at\s+)?(\d{1,2}):(\d{2})(?::(\d{2}))?(?:[.,]\d+)?)?"""
    private const val ZONE = """\s*(?:([+-]\d{2}):?(\d{2})?|([A-Za-z]{1,5}))?"""

    private val ISO = Regex(
        """(\d{4})-(\d{1,2})-(\d{1,2})""" + TIME + """\s*(?:([Zz])(?![A-Za-z])|([+-]\d{2}):?(\d{2})?|([A-Za-z]{1,5}))?""",
    )
    private val RFC822 = Regex("""(\d{1,2})\s+([A-Za-z]{3,9})\.?,?\s+(\d{4}|\d{2})""" + TIME + ZONE)
    private val MONTH_FIRST = Regex("""([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})""" + TIME + ZONE)

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        .withIndex().associate { it.value to it.index + 1 }

    private val ZONES = mapOf(
        "UT" to 0.0, "UTC" to 0.0, "GMT" to 0.0, "Z" to 0.0, "WET" to 0.0,
        "EST" to -5.0, "EDT" to -4.0, "CST" to -6.0, "CDT" to -5.0,
        "MST" to -7.0, "MDT" to -6.0, "PST" to -8.0, "PDT" to -7.0,
        "BST" to 1.0, "IST" to 5.5, "CET" to 1.0, "CEST" to 2.0, "EET" to 2.0, "EEST" to 3.0,
        "JST" to 9.0, "AEST" to 10.0, "AEDT" to 11.0,
    )
}
