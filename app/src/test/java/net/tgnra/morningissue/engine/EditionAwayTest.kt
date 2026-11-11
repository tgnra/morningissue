package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The While you were away pull-out (assembly step 8). */
class EditionAwayTest {
    private val release: Instant = Instant.parse("2026-11-10T06:30:00Z")

    /** The reader last opened an edition four days ago. */
    private val lastVisit: Instant = release.minus(Duration.ofDays(4))
    private val builder = EditionBuilder()
    private val scorer = Scorer()
    private var nextArticleId = 1L

    private val dailies = (1..8L).map { FeedProfile(it, "Daily $it", CadenceTier.DAILY, medianIntervalHours = 24.0) }
    private val weekly = FeedProfile(20, "Weekly", CadenceTier.WEEKLY, medianIntervalHours = 168.0)
    private val wire = FeedProfile(30, "Wire", CadenceTier.FIREHOSE, medianIntervalHours = 0.5)
    private val feeds = dailies + weekly + wire

    private fun post(feed: FeedProfile, at: Instant) =
        Candidate(articleId = nextArticleId++, feedId = feed.id, publishedAt = at, firstSeenAt = at)

    /** A story of a missed edition, published [daysAfterVisit] days after the last visit. */
    private fun missed(feed: FeedProfile, daysAfterVisit: Double, runUnopened: Int = 1) =
        post(feed, lastVisit.plus(Duration.ofMinutes((daysAfterVisit * 24 * 60).toLong()))).copy(editionsRunUnopened = runUnopened)

    private fun today(): List<Candidate> = dailies.map { post(it, release.minus(Duration.ofHours(1 + it.id))) }

    private fun build(
        away: List<Candidate>,
        feeds: List<FeedProfile> = this.feeds,
        today: List<Candidate> = today(),
        length: EditionLength = EditionLength.STANDARD,
        engaged: Set<Long> = emptySet(),
        kind: EditionKind = EditionKind.MORNING,
    ) = builder.build(today, feeds, length, release, kind = kind, away = AwayPool(away, lastVisit, engaged))

    private fun EditionDraft.away() = items.filter { it.kind == ItemKind.AWAY }.sortedBy { it.slot }

    @Test
    fun thePullOutIsPageTwoAndTheRestOfThePaperFollows() {
        val stories = listOf(missed(dailies[0], 1.0), missed(weekly, 2.0))
        val plain = builder.build(today(), feeds, EditionLength.STANDARD, release)
        nextArticleId = 3
        val draft = build(stories)

        assertEquals(listOf(2, 2), draft.away().map { it.page })
        assertEquals(listOf(0, 1), draft.away().map { it.slot })
        assertEquals(1, draft.lead!!.page)
        // Every other page keeps its content and order, one page further back.
        val shifted = draft.items.filter { it.kind != ItemKind.AWAY && it.page > 1 }.map { Triple(it.articleId, it.page - 1, it.slot) }
        assertEquals(plain.items.filter { it.page > 1 }.map { Triple(it.articleId, it.page, it.slot) }, shifted)
        assertEquals(draft.items.sortedWith(compareBy<DraftItem> { it.page }.thenBy { it.slot }), draft.items)
        assertTrue(draft.away().all { it.reason.startsWith("While you were away") })
    }

    @Test
    fun freshnessIsMeasuredFromTheLastVisitAndMissedRunsDoNotCount() {
        // Published a day before the visit ended; three and a half days after. Both ran twice unopened.
        val older = missed(dailies[0], -1.0, runUnopened = 2)
        val newer = missed(dailies[1], 3.5, runUnopened = 2)

        val away = build(listOf(older, newer)).away()

        assertEquals(listOf(newer.articleId, older.articleId), away.map { it.articleId })
        // Anything published since the visit is brand new to this reader: F = 1, P = 1.
        assertEquals(scorer.affinityWeight(0.5) * scorer.scarcity(24.0), away[0].score, 1e-12)
        assertEquals(scorer.affinityWeight(0.5) * scorer.scarcity(24.0) * scorer.freshness(24.0, 24.0), away[1].score, 1e-12)
    }

    @Test
    fun firehoseFeedsNeedARecentLikeOrSave() {
        val fromWire = missed(wire, 1.0)
        val fromDaily = missed(dailies[0], 1.0)

        assertEquals(listOf(fromDaily.articleId), build(listOf(fromWire, fromDaily)).away().map { it.articleId })
        assertEquals(
            setOf(fromWire.articleId, fromDaily.articleId),
            build(listOf(fromWire, fromDaily), engaged = setOf(wire.id)).away().map { it.articleId }.toSet(),
        )
    }

    @Test
    fun onlyFeedsAtAutoOrAboveAppear() {
        val turnedDown = listOf(SliderStep.RARELY, SliderStep.LESS).mapIndexed { i, step -> dailies[i].copy(sliderStep = step) }
        val kept = listOf(SliderStep.AUTO, SliderStep.MORE, SliderStep.FAVOURITE).mapIndexed { i, step -> dailies[i + 2].copy(sliderStep = step) }
        val all = turnedDown + kept + dailies.drop(5) + weekly + wire
        val stories = (turnedDown + kept).map { missed(it, 1.0) }

        val away = build(stories, feeds = all).away()

        assertEquals(kept.map { it.id }.toSet(), away.map { it.feedId }.toSet())
    }

    @Test
    fun mutedFeedsOpenedStoriesAndUnknownFeedsAreLeftOut() {
        val muted = dailies[0].copy(muteUntil = release.plus(Duration.ofDays(1)))
        val stories = listOf(
            missed(muted, 1.0),
            missed(dailies[1], 1.0).copy(isOpened = true),
            missed(dailies[2], 1.0).copy(isRead = true),
            missed(FeedProfile(99, "Gone"), 1.0),
            missed(dailies[3], 1.0),
        )

        val away = build(stories, feeds = listOf(muted) + feeds.drop(1), today = today().drop(1)).away()

        assertEquals(listOf(stories.last().articleId), away.map { it.articleId })
    }

    @Test
    fun itStopsAtThirtyAndFifteenForAShortEdition() {
        val stories = (0 until 50).map { missed(weekly, 0.01 * it) }
        val best = stories.sortedByDescending { it.publishedAt }.map { it.articleId }

        val standard = build(stories).away()
        assertEquals(30, standard.size)
        assertEquals(best.take(30), standard.map { it.articleId })
        assertEquals(15, build(stories, length = EditionLength.SHORT).away().size)
        assertEquals(30, build(stories, length = EditionLength.LONG).away().size)

        // The rest are dropped, not queued in the index.
        val draft = build(stories)
        assertEquals(30, draft.items.count { it.feedId == weekly.id })
    }

    @Test
    fun aStoryPlacedInTodaysPagesIsNotRepeatedInThePullOut() {
        // A held-over post that also ran in a missed edition: it keeps its regular place.
        val loved = weekly.copy(sliderStep = SliderStep.FAVOURITE)
        val held = missed(loved, 1.0).copy(isHeldOver = true)
        val other = missed(dailies[0], 1.0)

        val draft = builder.build(
            today() + held, dailies + loved + wire, EditionLength.STANDARD, release,
            away = AwayPool(listOf(held, other, other), lastVisit),
        )

        assertEquals(1, draft.items.count { it.articleId == held.articleId })
        assertFalse(draft.items.single { it.articleId == held.articleId }.kind == ItemKind.AWAY)
        assertEquals(listOf(other.articleId), draft.away().map { it.articleId })
        assertEquals(draft.items.size, draft.items.map { it.articleId }.distinct().size)
    }

    @Test
    fun aMissedStoryBoundForTheIndexMovesToThePullOut() {
        // Ran unopened and not held over: retired to the index on its own, unless it was missed.
        val retired = missed(dailies[0], 1.0)

        val draft = builder.build(today() + retired, feeds, EditionLength.STANDARD, release, away = AwayPool(listOf(retired), lastVisit))

        assertEquals(ItemKind.AWAY, draft.items.single { it.articleId == retired.articleId }.kind)
    }

    @Test
    fun weakStoriesAndCopiesAreLeftOut() {
        // Three weeks before the visit: a daily feed's post is long past caring about.
        val faded = missed(dailies[0], -21.0)
        val a = missed(dailies[1], 1.0).copy(title = "The bridge over the Tay reopens", url = "https://one.example/tay")
        val b = missed(dailies[2], 1.2).copy(title = "The bridge over the Tay reopens", url = "https://two.example/tay")

        val away = build(listOf(faded, a, b)).away()

        assertEquals(1, away.size)
        assertEquals(1, away.single().alsoInFeeds)
    }

    @Test
    fun nothingMissedOrNothingEligibleMeansNoPage() {
        val news = today()
        val plain = builder.build(news, feeds, EditionLength.STANDARD, release).items
        assertEquals(plain, build(emptyList(), today = news).items)
        assertEquals(plain, build(listOf(missed(wire, 1.0)), today = news).items)
        // No front page, no pull-out: missed stories alone do not make a paper.
        assertTrue(build(listOf(missed(weekly, 1.0)), today = emptyList()).items.isEmpty())
        // The evening paper never carries one.
        assertTrue(build(listOf(missed(dailies[0], 1.0)), kind = EditionKind.EVENING).away().isEmpty())
    }
}
