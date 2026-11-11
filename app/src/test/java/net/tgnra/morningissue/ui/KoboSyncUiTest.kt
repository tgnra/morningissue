package net.tgnra.morningissue.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.Inet4Address
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import net.tgnra.morningissue.kobo.KoboSyncStatus
import net.tgnra.morningissue.kobo.LanAddress
import net.tgnra.morningissue.kobo.SyncActivity
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.settings.koboStatusLine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The Sync screen's wording. */
@RunWith(AndroidJUnit4::class)
class KoboSyncUiTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources
    private val now = Instant.parse("2026-11-11T07:05:00Z")
    private val wifi = LanAddress("wlan0", InetAddress.getByName("192.168.1.20") as Inet4Address, null)

    private fun line(status: KoboSyncStatus) = koboStatusLine(status, resources, now, ZoneOffset.UTC, use24Hour = true)

    @Test
    fun theStatusLineSaysWhatIsHappening() {
        val listening = KoboSyncStatus(KoboSyncStatus.State.LISTENING, wifi)
        assertEquals("Waiting for a Kobo.", line(listening))
        assertEquals("Starting…", line(KoboSyncStatus(KoboSyncStatus.State.STARTING, wifi)))
        assertEquals("Connect this phone to Wi-Fi, the same network as the Kobo.", line(listening.copy(address = null)))
        assertEquals(
            "Port 47824 is in use by another app, so a Kobo cannot reach Morning Issue. Close that app and open this screen again.",
            line(listening.copy(state = KoboSyncStatus.State.PORT_IN_USE)),
        )
        val synced = SyncActivity("b7e2", "Kobo Libra 2", Instant.parse("2026-11-11T07:02:00Z"), eventsMerged = 12)
        assertEquals("Kobo Libra 2 synced at 07:02, with 12 changes from its reader.", line(listening.copy(last = synced)))
        assertEquals("Kobo Libra 2 synced at 07:02, with 1 change from its reader.", line(listening.copy(last = synced.copy(eventsMerged = 1))))
        assertEquals("Kobo Libra 2 synced at 07:02.", line(listening.copy(last = synced.copy(eventsMerged = null))))
    }

    @Test
    fun aSyncTimeTodayIsJustTheTime() {
        assertEquals("07:02", PaperFormat.syncTime(Instant.parse("2026-11-11T07:02:00Z"), now, ZoneOffset.UTC, true, Locale.UK))
        assertEquals("Tue 10 Nov, 21:40", PaperFormat.syncTime(Instant.parse("2026-11-10T21:40:00Z"), now, ZoneOffset.UTC, true, Locale.UK))
    }
}
