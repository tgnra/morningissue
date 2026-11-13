package net.tgnra.morningissue.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import net.tgnra.morningissue.relay.KoboStage
import net.tgnra.morningissue.relay.RelayProblem
import net.tgnra.morningissue.relay.RelayProgress
import net.tgnra.morningissue.relay.RelayResult
import net.tgnra.morningissue.relay.RelaySession
import net.tgnra.morningissue.relay.SessionStage
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.settings.resultLine
import net.tgnra.morningissue.ui.settings.sessionLine
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

    private fun synced(early: Boolean = false, synced: Int = 2, kobos: Int = 2, events: Int = 0, dropped: Int = 0, left: Int = 0) =
        RelayResult.Synced(now, early, synced, kobos, batches = 1, events = events, dropped = dropped, left = left)

    @Test
    fun aResultIsSaidPlainly() {
        assertEquals("Synced with all 2 Kobos. Brought back 12 changes from the Kobos.", line(synced(events = 12)))
        assertEquals("Synced with the Kobo. Nothing new from the Kobos.", line(synced(synced = 1, kobos = 1)))
        assertEquals(
            "Finished with 1 of 2 Kobos synced. Brought back 1 change from the Kobos. " +
                "1 batch could not be read and was discarded. 2 batches could not be downloaded; the Kobo sends them again next time.",
            line(synced(early = true, synced = 1, events = 1, dropped = 1, left = 2)),
        )
        assertEquals("Kobo Libra 2 is paired.", line(RelayResult.Paired(now, "Kobo Libra 2")))
        assertEquals("Nothing to sync: no Kobo is paired.", line(RelayResult.NoDevices(now)))
        assertEquals("Sync cancelled. Nothing was brought back, and the relay was cleared.", line(RelayResult.Cancelled(now, byLeaving = false)))
        assertEquals(
            "Sync cancelled because the app was left. Nothing was brought back, and the relay was cleared.",
            line(RelayResult.Cancelled(now, byLeaving = true)),
        )
        // The relay is the build's, so a problem with it points at the build or the connection, not at a field.
        assertEquals(
            "Sync failed. The relay could not be reached. Check the connection.",
            line(RelayResult.Failed(now, RelayProblem.NETWORK)),
        )
        assertEquals(
            "Sync failed. The relay asks for a registration token, and the one built into this app is missing or wrong.",
            line(RelayResult.Failed(now, RelayProblem.REGISTRATION_REFUSED, 403)),
        )
    }

    @Test
    fun anOpenSessionSaysWhereItIs() {
        val kobos = mapOf("b7e2" to KoboStage.COLLECTED, "c9f1" to KoboStage.WAITING)
        assertEquals("Preparing…", sessionLine(RelaySession(), resources, context))
        assertEquals("Preparing images 3/10", sessionLine(RelaySession(progress = RelayProgress.PreparingImages(3, 10)), resources, context))
        assertEquals("Publishing…", sessionLine(RelaySession(SessionStage.PUBLISHING), resources, context))
        assertEquals("Waiting for the Kobos: 1 of 2 synced. Sync each one now.", sessionLine(RelaySession(SessionStage.WAITING, kobos = kobos), resources, context))
        assertEquals("Waiting for the Kobo. Sync it now.", sessionLine(RelaySession(SessionStage.WAITING, kobos = mapOf("b7e2" to KoboStage.WAITING)), resources, context))
        assertEquals("Cancelling and clearing the relay…", sessionLine(RelaySession(SessionStage.CANCELLING), resources, context))
    }

    @Test
    fun aSyncTimeTodayIsJustTheTime() {
        assertEquals("07:02", PaperFormat.syncTime(Instant.parse("2026-11-11T07:02:00Z"), now, ZoneOffset.UTC, true, Locale.UK))
        assertEquals("Tue 10 Nov, 21:40", PaperFormat.syncTime(Instant.parse("2026-11-10T21:40:00Z"), now, ZoneOffset.UTC, true, Locale.UK))
    }
}
