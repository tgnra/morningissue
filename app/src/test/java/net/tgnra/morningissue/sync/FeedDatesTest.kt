package net.tgnra.morningissue.sync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedDatesTest {
    private fun assertParses(expected: String, text: String) = assertEquals(text, Instant.parse(expected), FeedDates.parse(text))

    @Test
    fun rfc822Variants() {
        assertParses("2006-01-02T22:04:05Z", "Mon, 02 Jan 2006 15:04:05 -0700")
        assertParses("2006-01-02T15:04:05Z", "Mon, 02 Jan 2006 15:04:05 GMT")
        assertParses("2006-01-02T15:04:05Z", "02 Jan 2006 15:04:05 +0000")
        assertParses("2006-01-02T15:04:00Z", "Mon, 2 Jan 2006 15:04 UT")
        assertParses("2006-01-02T20:04:05Z", "Mon, 02 Jan 06 15:04:05 EST")
        assertParses("2006-01-02T22:04:05Z", "Monday, 02 January 2006 15:04:05 PDT")
        assertParses("2006-01-02T14:04:05Z", "Mon, 02 Jan 2006 15:04:05 +01:00")
        assertParses("2006-01-02T15:04:05Z", "Mon, 02 Jan 2006 15:04:05")
        assertParses("2006-01-02T15:04:05Z", "Mon,02 Jan 2006 15:04:05 Z")
        assertParses("2006-01-02T00:00:00Z", "2 Jan 2006")
        assertParses("1999-12-31T23:59:59Z", "Fri, 31 Dec 99 23:59:59 GMT")
        // A wrong weekday is ignored rather than fatal.
        assertParses("2006-01-02T15:04:05Z", "Fri, 02 Jan 2006 15:04:05 GMT")
        assertParses("2006-01-02T15:04:05Z", "  Mon, 02 Jan 2006 15:04:05 +0000 (UTC)  ")
    }

    @Test
    fun iso8601Variants() {
        assertParses("2006-01-02T15:04:05Z", "2006-01-02T15:04:05Z")
        assertParses("2006-01-02T22:04:05Z", "2006-01-02T15:04:05-07:00")
        assertParses("2006-01-02T22:04:05Z", "2006-01-02T15:04:05-0700")
        assertParses("2006-01-02T15:04:05Z", "2006-01-02T15:04:05.123456Z")
        assertParses("2006-01-02T14:04:00Z", "2006-01-02T15:04+01:00")
        assertParses("2006-01-02T15:04:05Z", "2006-01-02 15:04:05")
        assertParses("2006-01-02T15:04:05Z", "2006-01-02T15:04:05")
        assertParses("2006-01-02T00:00:00Z", "2006-01-02")
        assertParses("2006-01-02T15:04:05Z", "2006-1-2T15:04:05Z")
    }

    @Test
    fun monthFirst() {
        assertParses("2006-01-02T00:00:00Z", "January 2, 2006")
        assertParses("2006-01-02T15:04:00Z", "Jan 2nd, 2006 15:04 GMT")
    }

    @Test
    fun unreadableDatesAreNull() {
        assertNull(FeedDates.parse(null))
        assertNull(FeedDates.parse(""))
        assertNull(FeedDates.parse("   "))
        assertNull(FeedDates.parse("yesterday"))
        assertNull(FeedDates.parse("not a date at all"))
        assertNull(FeedDates.parse("Mon, 32 Jan 2006 15:04:05 GMT"))
        assertNull(FeedDates.parse("2006-13-02T15:04:05Z"))
        assertNull(FeedDates.parse("Mon, 02 Foo 2006 15:04:05 GMT"))
        // Placeholders that servers emit for "no date".
        assertNull(FeedDates.parse("0001-01-01T00:00:00Z"))
        assertNull(FeedDates.parse("Thu, 01 Jan 1970 00:00:00 GMT"))
    }
}
