package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The evening structure, Welcome back, the freshness floor and the release clock. */
class EditionEveningAndComebackTest {
    private val release: Instant = Instant.parse("2026-11-10T18:00:00Z")
    private val config = RankingConfig.Default
    private val builder = EditionBuilder()
    private val scorer = Scorer()
    private var nextArticleId = 1L

    private val dailies = (1..8L).map { FeedProfile(it, "Daily $it", CadenceTier.DAILY, medianIntervalHours = 24.0, tags = listOf("News")) }
    private val wire = FeedProfile(20, "Wire", CadenceTier.FIREHOSE, medianIntervalHours = 0.5)
    private val weekly = FeedProfile(30, "Weekly", CadenceTier.WEEKLY, medianIntervalHours = 168.0)
    private val rare = FeedProfile(31, "Rare", CadenceTier.RARE, medianIntervalHours = 720.0, sliderStep = SliderStep.FAVOURITE)
    private val feeds = dailies + wire + weekly + rare

    private fun post(feed: FeedProfile, ageHours: Double) = Candidate(
        articleId = nextArticleId++,
        feedId = feed.id,
        publishedAt = release.minus(Duration.ofMinutes((ageHours * 60).toLong())),
        firstSeenAt = release.minus(Duration.ofHours(1)),
    )

    private fun afternoon(): List<Candidate> =
        dailies.flatMap { feed -> (0 until 3).map { post(feed, 1.0 + feed.id + it) } } + (0 until 30).map { post(wire, 0.2 * it) }

    // --- Evening edition ---------------------------------------------------------------------

    @Test
    fun theEveningPaperIsAFrontPageBriefsAndTheIndex() {
        val posts = afternoon()

        val draft = builder.build(posts, feeds, EditionLength.STANDARD, release, kind = EditionKind.EVENING)

        assertEquals(
            setOf(ItemKind.LEAD, ItemKind.FRONT, ItemKind.BRIEF, ItemKind.INDEX),
            draft.items.map { it.kind }.toSet(),
        )
        assertEquals(5, draft.items.count { it.page == 1 })
        assertEquals(15, draft.items.count { it.kind == ItemKind.BRIEF })
        assertEquals(listOf(1, 2, 3), draft.items.map { it.page }.distinct())
        // Every post is placed exactly once.
        assertEquals(posts.map { it.articleId }.sorted(), draft.items.map { it.articleId }.sorted())
    }

    @Test
    fun eveningBriefsFollowTheEditionLength() {
        fun briefs(length: EditionLength) =
            builder.build(afternoon(), feeds, length, release, kind = EditionKind.EVENING).items.count { it.kind == ItemKind.BRIEF }

        assertEquals(8, briefs(EditionLength.SHORT))
        assertEquals(23, briefs(EditionLength.LONG))
    }

    @Test
    fun weeklyAndRarePostsWaitForTheMorningAndAreNotEvenIndexed() {
        val slow = listOf(post(weekly, 1.0), post(rare, 0.5), post(rare, 2.0).copy(isHeldOver = true))
        val fast = afternoon()

        val draft = builder.build(fast + slow, feeds, EditionLength.STANDARD, release, kind = EditionKind.EVENING)

        assertTrue(draft.items.none { it.feedId == weekly.id || it.feedId == rare.id })
        assertEquals(3, draft.waitingForMorning)
        assertEquals(fast.size, draft.items.size)
        // The same pool in a morning paper leads with the favourite rare feed.
        val morning = builder.build(fast + slow, feeds, EditionLength.STANDARD, release)
        assertEquals(rare.id, morning.lead!!.feedId)
        assertEquals(0, morning.waitingForMorning)
    }

    @Test
    fun anEveningWithOnlySlowPostsHasNothingToPrint() {
        val draft = builder.build(listOf(post(weekly, 1.0), post(rare, 1.0)), feeds, EditionLength.STANDARD, release, kind = EditionKind.EVENING)
        assertTrue(draft.items.isEmpty())
    }

    // --- Welcome back ------------------------------------------------------------------------

    private fun quietFor(days: Long, feed: FeedProfile = weekly, ageHours: Double = 3.0): Candidate {
        val post = post(feed, ageHours)
        return post.copy(previousPostAt = post.publishedAt.minus(Duration.ofDays(days)))
    }

    @Test
    fun aPostAfterNinetyQuietDaysGetsTheBoostOnTopOfScarcity() {
        val comeback = quietFor(90)
        val score = scorer.score(weekly, comeback, release)

        assertEquals(1.5, score.c, 0.0)
        assertEquals(90L, score.quietDays)
        assertEquals(scorer.affinityWeight(0.5) * scorer.freshness(3.0, 168.0) * scorer.scarcity(168.0) * 1.5, score.value, 1e-12)
        assertEquals(config.comebackBoost, score.c, 0.0)

        val ordinary = scorer.score(weekly, quietFor(89), release)
        assertEquals(1.0, ordinary.c, 0.0)
        assertNull(ordinary.quietDays)
        assertFalse(ordinary.isComeback)
        assertEquals(1.0, scorer.score(weekly, post(weekly, 3.0), release).c, 0.0)
    }

    @Test
    fun theLabelCountsMonthsThenYears() {
        assertEquals("Back after 3 months", scorer.comebackLabel(90))
        assertEquals("Back after 6 months", scorer.comebackLabel(200))
        assertEquals("Back after 11 months", scorer.comebackLabel(364))
        assertEquals("Back after a year", scorer.comebackLabel(500))
        assertEquals("Back after 2 years", scorer.comebackLabel(800))
        assertEquals("Back after 10 years", scorer.comebackLabel(3660))

        val comeback = quietFor(800)
        val reason = scorer.reason(weekly, comeback, scorer.score(weekly, comeback, release), ItemKind.LEAD)
        assertEquals("Back after 2 years: Weekly has posted again", reason)
        assertEquals("Back after 2 years", Scorer.comebackLabelOf(reason))
        assertEquals("Back after 2 years", Scorer.comebackLabelOf("$reason; also in 1 other feed"))
        assertNull(Scorer.comebackLabelOf("New from Weekly since the last edition"))
        assertNull(Scorer.comebackLabelOf(null))
    }

    @Test
    fun aComebackMakesTheFrontPageAheadOfADaysNews() {
        // Strong competition: nine well-liked daily feeds posting within the hour.
        val liked = (1..9L).map { FeedProfile(it, "Liked $it", CadenceTier.DAILY, medianIntervalHours = 24.0, affinity = 0.9) }
        val news = liked.flatMap { feed -> (0 until 3).map { post(feed, 0.1 * it) } }
        val comeback = quietFor(400, ageHours = 20.0)

        val draft = builder.build(news + comeback, liked + weekly, EditionLength.STANDARD, release)

        val item = draft.items.single { it.articleId == comeback.articleId }
        assertEquals(ItemKind.LEAD, item.kind)
        assertTrue(item.reason.startsWith("Back after a year"))
        // Without the boost it would not have led.
        val plain = builder.build(news + comeback.copy(previousPostAt = null), liked + weekly, EditionLength.STANDARD, release)
        assertFalse(plain.lead!!.articleId == comeback.articleId)
    }

    @Test
    fun aFeedTheReaderTurnedDownIsLabelledButNotBoostedAndAReprintLosesBoth() {
        val less = weekly.copy(sliderStep = SliderStep.LESS)
        val score = scorer.score(less, quietFor(200, less), release)
        assertEquals(1.0, score.c, 0.0)
        assertTrue(score.isComeback)

        val held = quietFor(200).copy(isHeldOver = true)
        val reprint = scorer.score(weekly, held, release)
        assertEquals(1.0, reprint.c, 0.0)
        assertFalse(reprint.isComeback)
    }

    // --- Freshness floor ---------------------------------------------------------------------

    @Test
    fun aNewPostUnderTheFreshnessFloorIsListedNotPrinted() {
        // F = 0.05 at about 51.9 hours for a firehose and 155.6 hours for a daily feed.
        val faded = listOf(post(wire, 53.0), post(dailies[0], 157.0))
        val fresh = listOf(post(wire, 51.0), post(dailies[1], 154.0))

        val draft = builder.build(faded + fresh, feeds, EditionLength.STANDARD, release)

        faded.forEach { post -> assertEquals(ItemKind.INDEX, draft.items.single { it.articleId == post.articleId }.kind) }
        fresh.forEach { post -> assertFalse(draft.items.single { it.articleId == post.articleId }.kind == ItemKind.INDEX) }
    }

    @Test
    fun heldOverAndExemptPostsIgnoreTheFloorAndTheAgeLimit() {
        val loved = FeedProfile(40, "Loved daily", CadenceTier.DAILY, medianIntervalHours = 24.0, sliderStep = SliderStep.FAVOURITE)
        val held = post(loved, 200.0).copy(isHeldOver = true)
        val exempt = post(dailies[0], 24.0 * 60).copy(exemptFromAgeLimits = true)
        val old = post(dailies[1], 24.0 * 60)
        val news = post(dailies[2], 1.0)

        val draft = builder.build(listOf(held, exempt, old, news), feeds + loved, EditionLength.STANDARD, release)

        assertEquals(setOf(held.articleId, exempt.articleId, news.articleId), draft.printed.map { it.articleId }.toSet())
        assertEquals(1, draft.staleSkipped)
        assertTrue(draft.index.isEmpty())
    }

    // --- The release clock -------------------------------------------------------------------

    private val zone: ZoneId = ZoneOffset.UTC
    private val reset: LocalTime = LocalTime.of(6, 30)
    private val evening: LocalTime = LocalTime.of(18, 0)
    private fun at(time: String) = Instant.parse("2026-11-10T$time:00Z")

    @Test
    fun theNextReleaseIsWhicheverComesFirst() {
        assertEquals(Release(at("18:00"), EditionKind.EVENING), EditionClock.nextRelease(at("07:00"), reset, evening, zone))
        assertEquals(Release(at("06:30"), EditionKind.MORNING), EditionClock.nextRelease(at("05:00"), reset, evening, zone))
        assertEquals(
            Release(Instant.parse("2026-11-11T06:30:00Z"), EditionKind.MORNING),
            EditionClock.nextRelease(at("18:00"), reset, evening, zone),
        )
        // Switched off: the morning reset only.
        assertEquals(
            Release(Instant.parse("2026-11-11T06:30:00Z"), EditionKind.MORNING),
            EditionClock.nextRelease(at("07:00"), reset, null, zone),
        )
        // Set to the same time, the morning edition wins.
        assertEquals(EditionKind.MORNING, EditionClock.nextRelease(at("05:00"), reset, reset, zone).kind)
    }

    @Test
    fun theEveningSlotIsOnlyCurrentBetweenItsReleaseAndTheNextReset() {
        assertNull(EditionClock.currentEveningRelease(at("07:00"), reset, evening, zone))
        assertNull(EditionClock.currentEveningRelease(at("17:59"), reset, evening, zone))
        assertEquals(at("18:00"), EditionClock.currentEveningRelease(at("18:00"), reset, evening, zone))
        assertEquals(at("18:00"), EditionClock.currentEveningRelease(Instant.parse("2026-11-11T06:29:00Z"), reset, evening, zone))
        assertNull(EditionClock.currentEveningRelease(Instant.parse("2026-11-11T06:30:00Z"), reset, evening, zone))
        assertNull(EditionClock.currentEveningRelease(at("12:00"), reset, reset, zone))
        assertEquals(LocalDate.of(2026, 11, 10), EditionClock.editionDate(at("18:00"), zone))
    }
}
