package net.tgnra.morningissue.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import net.tgnra.morningissue.relay.RelayProblem
import net.tgnra.morningissue.relay.RelayResult
import net.tgnra.morningissue.relay.RelayTask
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.settings.resultLine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The Sync with Kobo screen's wording. */
@RunWith(AndroidJUnit4::class)
class KoboSyncUiTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resources = context.resources
    private val now = Instant.parse("2026-11-11T07:05:00Z")

    private fun line(result: RelayResult) = resultLine(result, resources, context)

    @Test
    fun aResultIsSaidPlainly() {
        assertEquals("Brought back 12 changes from the Kobos.", line(RelayResult.Collected(now, batches = 2, events = 12, dropped = 0, left = 0)))
        assertEquals("Nothing new from the Kobos.", line(RelayResult.Collected(now, batches = 0, events = 0, dropped = 0, left = 0)))
        assertEquals("Kobo Libra 2 is paired.", line(RelayResult.Paired(now, "Kobo Libra 2", toldRelay = true, problem = null)))
        assertEquals("Nothing to publish: no Kobo is paired.", line(RelayResult.NoDevices(now)))
        // The relay is the build's, so a problem with it points at the build or the connection, not at a field.
        assertEquals(
            "Publish failed. The relay could not be reached. Check the connection.",
            line(RelayResult.Failed(now, RelayTask.PUBLISH, RelayProblem.NETWORK)),
        )
        assertEquals(
            "Done failed. The relay asks for a registration token, and the one built into this app is missing or wrong.",
            line(RelayResult.Failed(now, RelayTask.DONE, RelayProblem.REGISTRATION_REFUSED, 403)),
        )
    }

    @Test
    fun aSyncTimeTodayIsJustTheTime() {
        assertEquals("07:02", PaperFormat.syncTime(Instant.parse("2026-11-11T07:02:00Z"), now, ZoneOffset.UTC, true, Locale.UK))
        assertEquals("Tue 10 Nov, 21:40", PaperFormat.syncTime(Instant.parse("2026-11-10T21:40:00Z"), now, ZoneOffset.UTC, true, Locale.UK))
    }
}
