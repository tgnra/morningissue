package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceTest {
    private val now: Instant = Instant.parse("2026-11-10T06:30:00Z")

    /** [count] posts, the newest [newestAge] old, each [gap] before the next. */
    private fun every(gap: Duration, count: Int, newestAge: Duration = Duration.ZERO): List<Instant> =
        (0 until count).map { now.minus(newestAge).minus(gap.multipliedBy(it.toLong())) }

    @Test
    fun tiersFollowV1Definitions() {
        // Firehose: more than 8 a day.
        assertEquals(CadenceTier.FIREHOSE, Cadence.compute(every(Duration.ofMinutes(30), 40), now).tier)
        assertEquals(CadenceTier.FIREHOSE, Cadence.compute(every(Duration.ofMinutes(170), 30), now).tier)
        // Daily: 1 to 8 a day. Exactly 8 a day is still Daily.
        assertEquals(CadenceTier.DAILY, Cadence.compute(every(Duration.ofHours(3), 30), now).tier)
        assertEquals(CadenceTier.DAILY, Cadence.compute(every(Duration.ofHours(24), 30), now).tier)
        // Weekly: 1 to 6 a week.
        assertEquals(CadenceTier.WEEKLY, Cadence.compute(every(Duration.ofHours(25), 30), now).tier)
        assertEquals(CadenceTier.WEEKLY, Cadence.compute(every(Duration.ofDays(7), 30), now).tier)
        // Rare: fewer than 1 a week.
        assertEquals(CadenceTier.RARE, Cadence.compute(every(Duration.ofDays(8), 30), now).tier)
        assertEquals(CadenceTier.RARE, Cadence.compute(every(Duration.ofDays(30), 12), now).tier)
    }

    @Test
    fun tierBoundaries() {
        assertEquals(CadenceTier.FIREHOSE, Cadence.tierFor(2.9))
        assertEquals(CadenceTier.DAILY, Cadence.tierFor(3.0))
        assertEquals(CadenceTier.DAILY, Cadence.tierFor(24.0))
        assertEquals(CadenceTier.WEEKLY, Cadence.tierFor(24.1))
        assertEquals(CadenceTier.WEEKLY, Cadence.tierFor(168.0))
        assertEquals(CadenceTier.RARE, Cadence.tierFor(168.1))
        assertEquals(CadenceTier.FIREHOSE, Cadence.tierFor(0.0))
    }

    @Test
    fun medianIntervalAndPostsPerDay() {
        val stats = Cadence.compute(every(Duration.ofHours(12), 30).shuffled(), now)
        assertEquals(12.0, stats.medianIntervalHours!!, 1e-9)
        assertEquals(2.0, stats.postsPerDay!!, 1e-9)
        assertEquals(1.0, stats.burstiness!!, 1e-9)
        assertEquals(30, stats.sampleSize)
    }

    @Test
    fun medianIgnoresOneLongHolidayGap() {
        // Daily posts, a three-week break, then daily again.
        val posts = every(Duration.ofDays(1), 10) + every(Duration.ofDays(1), 10, newestAge = Duration.ofDays(30))
        val stats = Cadence.compute(posts, now)
        assertEquals(24.0, stats.medianIntervalHours!!, 1e-9)
        assertEquals(CadenceTier.DAILY, stats.tier)
    }

    @Test
    fun burstinessIsHighForFeedsThatDumpPosts() {
        // Five posts ten minutes apart, once a week.
        val bursts = (0 until 6).flatMap { week -> every(Duration.ofMinutes(10), 5, newestAge = Duration.ofDays(7L * week)) }
        val bursty = Cadence.compute(bursts, now)
        val steady = Cadence.compute(every(Duration.ofHours(33), 30), now)
        assertTrue("bursty ${bursty.burstiness}", bursty.burstiness!! > 100.0)
        assertEquals(1.0, steady.burstiness!!, 1e-9)
    }

    @Test
    fun newFeedsWithFewerThanFiveItemsDefaultToWeekly() {
        for (count in 0..4) {
            val stats = Cadence.compute(every(Duration.ofMinutes(5), count), now)
            assertEquals(CadenceTier.WEEKLY, stats.tier)
            assertNull(stats.medianIntervalHours)
            assertNull(stats.burstiness)
        }
        assertEquals(CadenceTier.FIREHOSE, Cadence.compute(every(Duration.ofMinutes(5), 5), now).tier)
    }

    @Test
    fun postsSharingATimestampCountOnce() {
        // A feed without dates: every post of the first sync carries the fetch time.
        val stats = Cadence.compute(List(40) { now }, now)
        assertEquals(1, stats.sampleSize)
        assertEquals(CadenceTier.WEEKLY, stats.tier)
        assertNull(stats.medianIntervalHours)
    }

    @Test
    fun sampleIsTheLast30PostsOr90DaysWhicheverCoversMore() {
        // Slow feed: only 3 posts in 90 days, so the last 30 posts are used.
        val slow = Cadence.compute(every(Duration.ofDays(40), 50), now)
        assertEquals(30, slow.sampleSize)
        assertEquals(CadenceTier.RARE, slow.tier)

        // Busy feed: 90 days hold far more than 30 posts, so the whole window is used.
        val recentlyHourly = every(Duration.ofHours(1), 30)
        val earlierDaily = every(Duration.ofDays(1), 80, newestAge = Duration.ofDays(2))
        val busy = Cadence.compute(recentlyHourly + earlierDaily, now)
        assertEquals(110, busy.sampleSize)
        // The last 30 alone would say Firehose; the 90-day view says Daily.
        assertEquals(CadenceTier.DAILY, busy.tier)

        // Posts older than both the window and the last 30 are left out.
        val old = every(Duration.ofDays(1), 200)
        assertEquals(91, Cadence.compute(old, now).sampleSize)
    }
}
