package net.tgnra.morningissue.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.util.Locale
import net.tgnra.morningissue.data.archive.ArchiveFormatException
import net.tgnra.morningissue.data.archive.ArchiveProgress
import net.tgnra.morningissue.data.archive.ArchiveStatus
import net.tgnra.morningissue.data.archive.ExportReport
import net.tgnra.morningissue.data.archive.ImportReport
import net.tgnra.morningissue.data.storage.StorageUsage
import net.tgnra.morningissue.data.storage.TrimReport
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.model.FlaggedFeed
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.ui.settings.StorageBudget
import net.tgnra.morningissue.ui.settings.archiveMessage
import net.tgnra.morningissue.ui.sources.Desk
import net.tgnra.morningissue.ui.sources.DeskAction
import net.tgnra.morningissue.ui.sources.SourceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The pure parts of the Subscriptions desk, the Quiet group, the budget control and the archive messages. */
@RunWith(AndroidJUnit4::class)
class HealthAndStorageUiTest {
    private val now: Instant = Instant.parse("2026-11-10T06:30:00Z")

    private fun feed(
        id: Long,
        title: String = "Feed $id",
        flag: HealthFlag? = null,
        dismissedUntil: Instant? = null,
        lastPostDaysAgo: Long? = 1,
        synced: Boolean = true,
    ) = Feed(
        id = id, url = "https://example.com/$id", siteUrl = null, title = title, iconUrl = null, tier = CadenceTier.WEEKLY,
        medianIntervalHours = null, burstiness = null, affinity = 0.5, sliderStep = SliderStep.AUTO, tags = emptyList(),
        notify = false, muteUntil = null, lastSyncAt = now.takeIf { synced }, addedAt = now.minus(Duration.ofDays(500)),
        healthFlag = flag, flagDismissedUntil = dismissedUntil, lastPostAt = lastPostDaysAgo?.let { now.minus(Duration.ofDays(it)) },
    )

    // --- Subscriptions desk ------------------------------------------------------------------

    @Test
    fun everyFlagHasItsFixesAndNoneUnsubscribesWithoutBeingOffered() {
        assertEquals(listOf(DeskAction.RETRY, DeskAction.UNSUBSCRIBE, DeskAction.DISMISS), Desk.actions(HealthFlag.BROKEN))
        assertEquals(listOf(DeskAction.ACCEPT, DeskAction.UNSUBSCRIBE), Desk.actions(HealthFlag.MOVED))
        assertEquals(listOf(DeskAction.UNSUBSCRIBE, DeskAction.SHOW_LESS, DeskAction.DISMISS), Desk.actions(HealthFlag.IGNORED))
        assertEquals(listOf(DeskAction.SHOW_LESS, DeskAction.UNSUBSCRIBE, DeskAction.DISMISS), Desk.actions(HealthFlag.NOISY))
        assertEquals("Favourite only: there are no notifications to offer", listOf(DeskAction.FAVOURITE, DeskAction.DISMISS), Desk.actions(HealthFlag.LOVED))
        assertEquals("nothing to do about a quiet feed", listOf(DeskAction.DISMISS), Desk.actions(HealthFlag.QUIET))
        // Every flag can be put away, by dismissing it or (Moved) accepting it.
        for (flag in HealthFlag.entries) {
            assertTrue("$flag", Desk.actions(flag).any { it == DeskAction.DISMISS || it == DeskAction.ACCEPT })
        }
    }

    @Test
    fun flaggedFeedsAreGroupedByFlagMostUrgentFirst() {
        val flagged = listOf(
            FlaggedFeed(feed(1), HealthFlag.QUIET),
            FlaggedFeed(feed(2), HealthFlag.LOVED),
            FlaggedFeed(feed(3), HealthFlag.BROKEN),
            FlaggedFeed(feed(4), HealthFlag.QUIET),
        )
        val groups = Desk.groups(flagged)
        assertEquals(listOf(HealthFlag.BROKEN, HealthFlag.LOVED, HealthFlag.QUIET), groups.map { it.first })
        assertEquals(listOf(1L, 4L), groups.last().second.map { it.feed.id })
        assertTrue("all healthy: nothing to show", Desk.groups(emptyList()).isEmpty())
    }

    @Test
    fun onlyUndismissedFlagsThatAskForSomethingCount() {
        val feeds = listOf(
            feed(1, flag = HealthFlag.BROKEN),
            feed(2, flag = HealthFlag.QUIET),
            feed(3, flag = HealthFlag.NOISY, dismissedUntil = now.plusSeconds(60)),
            feed(4, flag = HealthFlag.IGNORED, dismissedUntil = now.minusSeconds(60)),
            feed(5),
        )
        assertEquals(2, Desk.needingAttention(feeds, now))
        assertNull(feeds[2].visibleHealthFlag(now))
        assertEquals(HealthFlag.IGNORED, feeds[3].visibleHealthFlag(now))
    }

    // --- Quiet group in Sources --------------------------------------------------------------

    @Test
    fun quietFeedsMoveToAGroupAtTheBottomOfSources() {
        val feeds = listOf(
            feed(1, "Alpha", lastPostDaysAgo = 400),
            feed(2, "Beta"),
            feed(3, "Gamma", lastPostDaysAgo = 90),
            feed(4, "Delta", lastPostDaysAgo = 89),
            // Never fetched: not known to be quiet.
            feed(5, "Epsilon", lastPostDaysAgo = null, synced = false),
        )
        val rows = SourceRow.of(feeds, now)
        assertEquals(
            listOf("Beta", "Delta", "Epsilon", "QUIET 2", "Alpha", "Gamma"),
            rows.map { row ->
                when (row) {
                    is SourceRow.Source -> row.feed.title
                    is SourceRow.QuietHeading -> "QUIET ${row.count}"
                }
            },
        )
        assertEquals("keys are unique", rows.size, rows.map { it.key }.toSet().size)
        assertFalse("no heading without quiet feeds", SourceRow.of(feeds.filter { it.id in setOf(2L, 4L) }, now).any { it is SourceRow.QuietHeading })
    }

    // --- Storage budget control --------------------------------------------------------------

    @Test
    fun theBudgetMovesInDiscreteStepsWithinTheStoredRange() {
        assertEquals(AppSettings.MIN_STORAGE_BUDGET_MB, StorageBudget.STEPS.first())
        assertEquals(AppSettings.MAX_STORAGE_BUDGET_MB, StorageBudget.STEPS.last())
        assertTrue("the default is a step", AppSettings.DEFAULT_STORAGE_BUDGET_MB in StorageBudget.STEPS)
        assertEquals(StorageBudget.STEPS.sorted(), StorageBudget.STEPS)

        assertEquals(250, StorageBudget.lower(500))
        assertEquals(1000, StorageBudget.higher(500))
        assertNull(StorageBudget.lower(50))
        assertNull(StorageBudget.higher(4000))
        // A stored value between steps (set some other way) sits on the nearest.
        assertEquals(500, StorageBudget.higher(300))
        assertEquals(100, StorageBudget.lower(300))

        assertEquals("50 MB", StorageBudget.label(50))
        assertEquals("1 GB", StorageBudget.label(1000))
        assertEquals("4 GB", StorageBudget.label(4000))
    }

    @Test
    fun sizesAndUsageAreWrittenPlainly() {
        val mb = 1024L * 1024L
        assertEquals("under 1 MB", StorageBudget.size(300_000, "under 1 MB", Locale.UK))
        assertEquals("132 MB", StorageBudget.size(132 * mb, "under 1 MB", Locale.UK))
        assertEquals("1.4 GB", StorageBudget.size(1400 * mb, "under 1 MB", Locale.UK))
        assertEquals(0.25f, StorageBudget.fraction(StorageUsage(125 * mb, 500 * mb)))
        assertEquals("over budget is a full bar", 1f, StorageBudget.fraction(StorageUsage(900 * mb, 500 * mb)))
        assertTrue(StorageUsage(900 * mb, 500 * mb).overBudget)
        assertFalse(StorageUsage(500 * mb, 500 * mb).overBudget)
    }

    // --- Archive messages --------------------------------------------------------------------

    @Test
    fun archiveProgressAndResultsAreSpelledOut() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        fun message(status: ArchiveStatus) = archiveMessage(status, resources)

        assertNull(message(ArchiveStatus.Idle))
        assertEquals("Importing…", message(ArchiveStatus.Running(importing = true, progress = null)))
        assertEquals("Importing… 400 of 1,550 posts", message(ArchiveStatus.Running(true, ArchiveProgress(400, 1550))))
        assertEquals("Exporting… 1,200 of 1,550 posts", message(ArchiveStatus.Running(false, ArchiveProgress(1200, 1550))))

        assertEquals(
            "Imported 1,240 posts, skipped 310.",
            message(ArchiveStatus.Imported(ImportReport(postsImported = 1240, postsSkipped = 310))),
        )
        assertEquals(
            "Imported 1 post, skipped 0. Put back the text of 85 posts. Imported 12 editions, skipped 3. " +
                "Subscribed again to 2 feeds that were in the file.",
            message(ArchiveStatus.Imported(ImportReport(1, 0, textsRestored = 85, editionsImported = 12, editionsSkipped = 3, feedsAdded = 2))),
        )
        assertEquals("Exported 31 editions and 1,240 posts.", message(ArchiveStatus.Exported(ExportReport(9, 1240, 31), freed = null)))
        assertEquals(
            "Exported 1 edition and 1 post. Freed 120 MB by trimming 1,100 articles.",
            message(ArchiveStatus.Exported(ExportReport(1, 1, 1), TrimReport(1100, 120L * 1024 * 1024))),
        )

        assertEquals("That file isn’t a Morning Issue archive.", message(ArchiveStatus.Failed(true, ArchiveFormatException.Reason.NOT_AN_ARCHIVE)))
        assertTrue(message(ArchiveStatus.Failed(true, ArchiveFormatException.Reason.NEWER_VERSION))!!.contains("newer version"))
        assertEquals("The import stopped: the file couldn’t be read.", message(ArchiveStatus.Failed(importing = true, reason = null)))
        assertEquals("The export couldn’t be written.", message(ArchiveStatus.Failed(importing = false, reason = null)))
    }
}
