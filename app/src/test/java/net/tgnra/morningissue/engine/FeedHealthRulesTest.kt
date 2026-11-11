package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.model.SliderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedHealthRulesTest {
    private val now: Instant = Instant.parse("2026-11-10T06:30:00Z")
    private val config = RankingConfig.Default

    private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    /** A feed with nothing wrong: added a year ago, fetched an hour ago, posted yesterday. */
    private fun healthy() = FeedHealthInput(
        addedAt = daysAgo(365),
        lastSyncAt = now.minusSeconds(3600),
        lastPostAt = daysAgo(1),
    )

    private fun flag(input: FeedHealthInput) = FeedHealthRules.flag(input, now, config)

    @Test
    fun aHealthyFeedCarriesNoFlag() {
        assertNull(flag(healthy()))
        assertEquals(HealthVerdict(null, null), FeedHealthRules.evaluate(healthy(), now, config))
    }

    // --- Broken and repair -------------------------------------------------------------------

    @Test
    fun failingMeansErrorsSevenDaysRunning() {
        val failing = healthy().copy(errorStreak = 40, errorSince = daysAgo(7))
        assertTrue(FeedHealthRules.isFailing(failing, now, config))
        assertFalse("six days is not yet a week", FeedHealthRules.isFailing(failing.copy(errorSince = daysAgo(6)), now, config))
        assertFalse(
            "two failures a week apart are a phone that was off, not a broken feed",
            FeedHealthRules.isFailing(failing.copy(errorStreak = 2), now, config),
        )
        assertTrue(FeedHealthRules.isFailing(failing.copy(errorStreak = 3), now, config))
        assertFalse("a success ends the run", FeedHealthRules.isFailing(failing.copy(errorStreak = 0, errorSince = null), now, config))
    }

    @Test
    fun goneIsFailingAtOnce() {
        val gone = healthy().copy(errorStreak = 1, errorSince = now, lastHttpStatus = 410)
        assertTrue(FeedHealthRules.isFailing(gone, now, config))
        assertFalse("another error status is not", FeedHealthRules.isFailing(gone.copy(lastHttpStatus = 404), now, config))
        assertFalse("410 from before a later success", FeedHealthRules.isFailing(gone.copy(errorStreak = 0), now, config))
    }

    @Test
    fun brokenIsOnlyFlaggedOnceRepairHasBeenTried() {
        val failing = healthy().copy(errorStreak = 40, errorSince = daysAgo(8))
        assertTrue(FeedHealthRules.repairDue(failing, now, config))
        assertNull("not flagged before the repair has had its go", flag(failing))

        val tried = failing.copy(lastRepairAt = now)
        assertEquals(HealthFlag.BROKEN, flag(tried))
        assertFalse("just tried", FeedHealthRules.repairDue(tried, now, config))
    }

    @Test
    fun repairIsRateLimited() {
        val failing = healthy().copy(errorStreak = 40, errorSince = daysAgo(30))
        assertFalse(FeedHealthRules.repairDue(failing.copy(lastRepairAt = daysAgo(6)), now, config))
        assertTrue(FeedHealthRules.repairDue(failing.copy(lastRepairAt = daysAgo(7)), now, config))
        assertFalse("a working feed is never repaired", FeedHealthRules.repairDue(healthy(), now, config))
        // A repair from an earlier run of errors says nothing about this one.
        val earlier = failing.copy(errorSince = daysAgo(8), lastRepairAt = daysAgo(9))
        assertTrue(FeedHealthRules.repairDue(earlier, now, config))
        assertNull(flag(earlier))
    }

    // --- Moved -------------------------------------------------------------------------------

    @Test
    fun movedStandsUntilItIsAccepted() {
        val moved = healthy().copy(previousFlag = HealthFlag.MOVED)
        assertEquals(HealthFlag.MOVED, flag(moved))
        // A dismissal left over from some other flag does not hide the new address.
        val verdict = FeedHealthRules.evaluate(moved.copy(dismissedUntil = now.plusSeconds(86_400)), now, config)
        assertEquals(HealthVerdict(HealthFlag.MOVED, null), verdict)
        assertEquals(HealthFlag.MOVED, verdict.visibleFlag(now))
    }

    // --- Quiet -------------------------------------------------------------------------------

    @Test
    fun quietMeansNoPostsInNinetyDays() {
        assertNull(flag(healthy().copy(lastPostAt = daysAgo(89))))
        assertEquals(HealthFlag.QUIET, flag(healthy().copy(lastPostAt = daysAgo(90))))
        assertEquals("a feed that never posted counts from when it was added", HealthFlag.QUIET, flag(healthy().copy(lastPostAt = null)))
        assertNull("added recently, no posts yet", flag(healthy().copy(lastPostAt = null, addedAt = daysAgo(10))))
        assertNull("never fetched: unknown, not quiet", flag(healthy().copy(lastPostAt = null, lastSyncAt = null)))
    }

    @Test
    fun quietFeedsAreCheckedLessOftenInSteps() {
        fun hours(quietDays: Long) = FeedHealthRules.checkInterval(Duration.ofDays(quietDays), config).toHours()
        assertEquals(0, hours(0))
        assertEquals(0, hours(89))
        assertEquals(6, hours(90))
        assertEquals(6, hours(179))
        assertEquals(24, hours(180))
        assertEquals(72, hours(365))
        assertEquals(72, hours(729))
        assertEquals("weekly at the slowest", 168, hours(730))
        assertEquals(168, hours(5000))
    }

    @Test
    fun dueForCheckFollowsTheStepAndRecoversWithANewPost() {
        val added = daysAgo(1000)
        fun due(lastPostDaysAgo: Long, lastSyncHoursAgo: Long) = FeedHealthRules.dueForCheck(
            lastSyncAt = now.minus(Duration.ofHours(lastSyncHoursAgo)),
            lastPostAt = daysAgo(lastPostDaysAgo),
            addedAt = added,
            now = now,
            config = config,
        )
        assertTrue("an active feed is fetched by every sync", due(lastPostDaysAgo = 2, lastSyncHoursAgo = 0))
        assertFalse(due(lastPostDaysAgo = 100, lastSyncHoursAgo = 5))
        assertTrue(due(lastPostDaysAgo = 100, lastSyncHoursAgo = 6))
        assertFalse(due(lastPostDaysAgo = 200, lastSyncHoursAgo = 23))
        assertTrue(due(lastPostDaysAgo = 200, lastSyncHoursAgo = 24))
        assertFalse(due(lastPostDaysAgo = 800, lastSyncHoursAgo = 167))
        assertTrue(due(lastPostDaysAgo = 800, lastSyncHoursAgo = 168))
        // It posted again: back to every sync, however recently it was checked.
        assertTrue(due(lastPostDaysAgo = 0, lastSyncHoursAgo = 1))
        assertTrue("never fetched", FeedHealthRules.dueForCheck(null, null, added, now, config))
    }

    // --- Ignored -----------------------------------------------------------------------------

    @Test
    fun ignoredMeansTenEditionsAndNoOpens() {
        val ignored = healthy().copy(ignoredRuns = FeedRunStats(editions = 10, stories = 14, opened = 0))
        assertEquals(HealthFlag.IGNORED, flag(ignored))
        assertNull(flag(ignored.copy(ignoredRuns = FeedRunStats(editions = 9, stories = 14))))
        assertNull("one open is enough", flag(ignored.copy(ignoredRuns = FeedRunStats(editions = 30, stories = 40, opened = 1))))
        assertNull("already turned down", flag(ignored.copy(sliderStep = SliderStep.LESS)))
        assertNull(flag(ignored.copy(sliderStep = SliderStep.RARELY)))
        assertEquals("a favourite nobody opens is still worth a word", HealthFlag.IGNORED, flag(ignored.copy(sliderStep = SliderStep.FAVOURITE)))
    }

    // --- Noisy -------------------------------------------------------------------------------

    @Test
    fun noisyMeansThirtyPostsAWeekAndUnderTwoPercentRead() {
        // Four weeks: 120 posts is exactly 30 a week.
        val noisy = healthy().copy(recentPosts = 120, recentPostsOpened = 2)
        assertEquals(HealthFlag.NOISY, flag(noisy))
        assertNull("29.75 a week", flag(noisy.copy(recentPosts = 119, recentPostsOpened = 0)))
        assertNull("2.5% read", flag(noisy.copy(recentPostsOpened = 3)))
        assertNull("exactly 2% is not under 2%", flag(noisy.copy(recentPosts = 200, recentPostsOpened = 4)))
        assertNull("already turned down", flag(noisy.copy(sliderStep = SliderStep.LESS)))
        assertNull("newer than the window: that is its back catalogue", flag(noisy.copy(addedAt = daysAgo(27))))
        assertEquals(HealthFlag.NOISY, flag(noisy.copy(addedAt = daysAgo(28))))
    }

    // --- Loved -------------------------------------------------------------------------------

    @Test
    fun lovedNeedsAffinityExposureReadsAndLikesWithTheSliderOnAuto() {
        val loved = healthy().copy(affinity = 0.7, lovedRuns = FeedRunStats(editions = 8, stories = 10, opened = 8, read = 5, liked = 1))
        assertEquals(HealthFlag.LOVED, flag(loved))
        assertNull("affinity", flag(loved.copy(affinity = 0.69)))
        assertNull("too few stories to judge", flag(loved.copy(lovedRuns = FeedRunStats(stories = 7, read = 7, liked = 7))))
        assertNull("read rate under a half", flag(loved.copy(lovedRuns = FeedRunStats(stories = 10, read = 4, liked = 3))))
        assertNull("no Likes", flag(loved.copy(lovedRuns = FeedRunStats(stories = 10, read = 9, liked = 0))))
        assertNull("Like rate under a tenth", flag(loved.copy(lovedRuns = FeedRunStats(stories = 20, read = 18, liked = 1))))
        for (step in SliderStep.entries.filter { it != SliderStep.AUTO }) {
            assertNull("the slider has been set by hand: $step", flag(loved.copy(sliderStep = step)))
        }
    }

    // --- One flag at a time ------------------------------------------------------------------

    @Test
    fun theMostUrgentFlagWins() {
        val everything = healthy().copy(
            affinity = 0.9,
            lastPostAt = daysAgo(200),
            errorStreak = 50,
            errorSince = daysAgo(10),
            lastRepairAt = daysAgo(1),
            previousFlag = HealthFlag.MOVED,
            ignoredRuns = FeedRunStats(editions = 12, stories = 12),
            lovedRuns = FeedRunStats(stories = 10, read = 9, liked = 5),
            recentPosts = 500,
        )
        assertEquals(HealthFlag.entries.toSet(), FeedHealthRules.flags(everything, now, config))
        assertEquals(HealthFlag.BROKEN, flag(everything))
        val notBroken = everything.copy(errorStreak = 0, errorSince = null)
        assertEquals(HealthFlag.MOVED, flag(notBroken))
        val notMoved = notBroken.copy(previousFlag = null)
        assertEquals(HealthFlag.NOISY, flag(notMoved))
        val notNoisy = notMoved.copy(recentPosts = 0)
        assertEquals(HealthFlag.IGNORED, flag(notNoisy))
        val notIgnored = notNoisy.copy(ignoredRuns = FeedRunStats())
        assertEquals(HealthFlag.LOVED, flag(notIgnored))
        assertEquals(HealthFlag.QUIET, flag(notIgnored.copy(lovedRuns = FeedRunStats())))
    }

    // --- Dismissal ---------------------------------------------------------------------------

    @Test
    fun dismissingHidesAFlagForSixMonths() {
        val until = FeedHealthRules.dismissUntil(now, config)
        assertEquals(183, Duration.between(now, until).toDays())

        val ignored = healthy().copy(
            ignoredRuns = FeedRunStats(editions = 12, stories = 12),
            previousFlag = HealthFlag.IGNORED,
            dismissedUntil = until,
        )
        // Still ignored, still dismissed: the stored flag stays as the record of what was dismissed.
        val verdict = FeedHealthRules.evaluate(ignored, now.plusSeconds(3600), config)
        assertEquals(HealthVerdict(HealthFlag.IGNORED, until), verdict)
        assertNull(verdict.visibleFlag(now.plusSeconds(3600)))

        // One second before the six months are up it is hidden; at that moment it is back.
        assertNull(FeedHealthRules.visibleFlag(HealthFlag.IGNORED, until, until.minusSeconds(1)))
        assertEquals(HealthFlag.IGNORED, FeedHealthRules.visibleFlag(HealthFlag.IGNORED, until, until))
        val after = FeedHealthRules.evaluate(ignored, until.plusSeconds(1), config)
        assertEquals(HealthVerdict(HealthFlag.IGNORED, null), after)
    }

    @Test
    fun aDismissalCoversTheFlagThatWasDismissedAndNoOther() {
        val until = FeedHealthRules.dismissUntil(now, config)
        val dismissedIgnored = healthy().copy(previousFlag = HealthFlag.IGNORED, dismissedUntil = until)

        // The rule no longer holds: the dismissal is remembered in case it comes back.
        assertEquals(HealthVerdict(HealthFlag.IGNORED, until), FeedHealthRules.evaluate(dismissedIgnored, now, config))

        // The feed breaks: that is news, and is shown.
        val broken = dismissedIgnored.copy(errorStreak = 30, errorSince = daysAgo(9), lastRepairAt = daysAgo(1))
        val verdict = FeedHealthRules.evaluate(broken, now, config)
        assertEquals(HealthVerdict(HealthFlag.BROKEN, null), verdict)
        assertEquals(HealthFlag.BROKEN, verdict.visibleFlag(now))
    }

    @Test
    fun onlyGoneQuietNeedsNoAttention() {
        assertEquals(listOf(HealthFlag.QUIET), HealthFlag.entries.filterNot(FeedHealthRules::needsAttention))
    }

    // --- The monthly notice ------------------------------------------------------------------

    @Test
    fun theDeskNoticeRunsInOneEditionAMonth() {
        val today = LocalDate.of(2026, 11, 10)
        fun decide(editionId: Long, date: LocalDate = today, latest: Boolean = true, needing: Int = 2, claim: DeskNoticeRule.Claim? = null) =
            DeskNoticeRule.decide(editionId, date, latest, today, needing, claim)

        assertEquals("nothing needs attention", DeskNoticeRule.Decision.HIDE, decide(7, needing = 0))
        assertEquals("the first edition opened this month takes it", DeskNoticeRule.Decision.CLAIM, decide(7))
        assertEquals("a back issue never does", DeskNoticeRule.Decision.HIDE, decide(6, latest = false))
        assertEquals(
            "nor an edition dated last month",
            DeskNoticeRule.Decision.HIDE,
            decide(6, date = LocalDate.of(2026, 10, 31)),
        )

        val claim = DeskNoticeRule.Claim(YearMonth.of(2026, 11), 7)
        assertEquals(DeskNoticeRule.Decision.SHOW, decide(7, claim = claim))
        assertEquals("it keeps it when it is no longer the latest", DeskNoticeRule.Decision.SHOW, decide(7, latest = false, claim = claim))
        assertEquals("tomorrow's edition does not get one", DeskNoticeRule.Decision.HIDE, decide(8, claim = claim))
        assertEquals("fixed since: gone", DeskNoticeRule.Decision.HIDE, decide(7, needing = 0, claim = claim))

        // Next month the claim is stale and the first edition opened takes the notice.
        val december = LocalDate.of(2026, 12, 1)
        assertEquals(DeskNoticeRule.Decision.CLAIM, DeskNoticeRule.decide(30, december, true, december, 1, claim))
        assertEquals("last month's edition has lost it", DeskNoticeRule.Decision.HIDE, DeskNoticeRule.decide(7, today, false, december, 1, claim))
    }
}
