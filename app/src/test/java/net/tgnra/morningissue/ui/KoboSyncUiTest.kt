package net.tgnra.morningissue.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import net.tgnra.morningissue.api.DeviceDto
import net.tgnra.morningissue.api.DeviceKind
import net.tgnra.morningissue.client.KoboDevices
import net.tgnra.morningissue.client.PairResult
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.settings.pairLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Kobo screen's wording. */
@RunWith(AndroidJUnit4::class)
class KoboSyncUiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resources = context.resources
    private val now = Instant.parse("2026-11-11T07:05:00Z")

    @Test
    fun aPairingIsSaidPlainly() {
        val kobo = DeviceDto("kobo-1", DeviceKind.KOBO, "Libra 2", width = 1264, createdAt = now)
        assertEquals("Libra 2 is paired. Sync it from the Kobo.", pairLine(PairResult.Paired(kobo), resources))
        assertEquals("That is not a Morning Issue pairing code.", pairLine(PairResult.NotACode, resources))
        assertEquals("The server could not be reached. Check the connection.", pairLine(PairResult.Unreachable, resources))
    }

    @Test
    fun onlyAPairingCodeIsSentToTheServer() {
        assertTrue(KoboDevices.isPairingCode("morningissue:pair?v=2&s=abc&d=kobo-1&n=Libra&w=1264"))
        assertFalse(KoboDevices.isPairingCode("morningissue:pair?"))
        assertFalse(KoboDevices.isPairingCode("https://example.com/"))
    }

    @Test
    fun aSyncTimeTodayIsJustTheTime() {
        assertEquals("07:02", PaperFormat.syncTime(Instant.parse("2026-11-11T07:02:00Z"), now, ZoneOffset.UTC, true, Locale.UK))
        assertEquals("Tue 10 Nov, 21:40", PaperFormat.syncTime(Instant.parse("2026-11-10T21:40:00Z"), now, ZoneOffset.UTC, true, Locale.UK))
    }
}
