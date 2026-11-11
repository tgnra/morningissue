package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Within-feed ranking in a whole edition: where W decides, and where it has no say. */
class EditionWithinFeedTest {
    private val release: Instant = Instant.parse("2026-11-10T06:30:00Z")
    private var nextArticleId = 1L

    private val ars = FeedProfile(1, "Ars Technica", CadenceTier.DAILY, medianIntervalHours = 24.0)

    private fun post(feed: FeedProfile, ageHours: Double, title: String? = null) = Candidate(
        articleId = nextArticleId++,
        feedId = feed.id,
        publishedAt = release.minus(Duration.ofMinutes((ageHours * 60).toLong())),
        firstSeenAt = release.minus(Duration.ofHours(1)),
        title = title,
    )

    private fun about(category: String) = PostTraits(ars.id, categories = setOf(category))

    /** Thirty posts of Ars Technica over the last month: every one about space read, the rest skipped. */
    private fun spaceReader(): WithinFeedModel {
        val history = (1..30).map { day ->
            val ranAt = release.minus(Duration.ofDays(day.toLong()))
            val space = day % 3 == 0
            LabelledPost(
                articleId = 1000L + day,
                evidence = ArticleEvidence(ars.id, listOf(ranAt), openedAt = ranAt.takeIf { space }, readAt = ranAt.takeIf { space }),
                traits = about(if (space) "space" else "politics"),
            )
        }
        return WithinFeed().learn(history, release)
    }

    private fun builder(source: WithinFeedSource) = EditionBuilder(scorer = Scorer(withinFeedSource = source))

    private fun EditionDraft.item(candidate: Candidate) = items.single { it.articleId == candidate.articleId }

    @Test
    fun twoPostsOfOneFeedOnTheSameDayAreOrderedByW() {
        val politics = post(ars, ageHours = 2.0)
        val space = post(ars, ageHours = 6.0)
        val traits = mapOf(politics.articleId to about("politics"), space.articleId to about("space"))
        val candidates = listOf(politics, space)

        // As it was: only age tells them apart, so the newer post leads.
        val before = EditionBuilder().build(candidates, listOf(ars), EditionLength.STANDARD, release)
        assertEquals(politics.articleId, before.lead!!.articleId)
        assertEquals("New from Ars Technica since the last edition", before.lead!!.reason)

        // With the reader's record: the post about space leads, four hours older though it is.
        val model = spaceReader()
        val after = builder(model.source(traits)).build(candidates, listOf(ars), EditionLength.STANDARD, release)
        assertEquals(space.articleId, after.lead!!.articleId)
        assertEquals(ItemKind.TOP, after.item(politics).kind)
        assertTrue(after.item(space).score > after.item(politics).score)

        val plain = Scorer()
        val w = model.weight(about("space")).multiplier
        assertTrue(w > 1.15)
        assertEquals(plain.score(ars, space, release).value * w, after.lead!!.score, 1e-12)
        assertEquals(
            plain.score(ars, politics, release).value * model.weight(about("politics")).multiplier,
            after.item(politics).score,
            1e-12,
        )
        // The space posts were all read (a rate of about 0.6 after shrinkage), so the line can say "usually".
        assertEquals("You usually read Ars Technica's posts about space", after.lead!!.reason)
        assertEquals("New from Ars Technica since the last edition", after.item(politics).reason)
    }

    @Test
    fun aNeutralSourceBuildsTheSameEdition() {
        val feeds = (1..12L).map { FeedProfile(it, "Feed $it", CadenceTier.DAILY, medianIntervalHours = 24.0, affinity = 0.3 + it * 0.05) }
        val candidates = feeds.flatMap { feed -> (0 until 4).map { post(feed, ageHours = 1.0 + feed.id + it * 7.0) } }

        val before = EditionBuilder().build(candidates, feeds, EditionLength.STANDARD, release)
        val neutral = builder(WithinFeedSource.Neutral).build(candidates, feeds, EditionLength.STANDARD, release)
        val untrained = builder(WithinFeedModel.empty().source(emptyMap())).build(candidates, feeds, EditionLength.STANDARD, release)

        assertEquals(before, neutral)
        assertEquals(before, untrained)
    }

    @Test
    fun wCannotBringBackAPostPastTheFreshnessFloor() {
        // A week old in a daily feed: F is under 0.05, whatever the reader thinks of its subject.
        val stale = post(ars, ageHours = 7 * 24.0)
        val fresh = post(ars, ageHours = 3.0)
        val source = WithinFeedSource { _, candidate -> WithinFeedWeight(if (candidate.articleId == stale.articleId) 1.4 else 1.0) }

        val draft = builder(source).build(listOf(stale, fresh), listOf(ars), EditionLength.STANDARD, release)

        assertEquals(ItemKind.INDEX, draft.item(stale).kind)
        assertEquals(ItemKind.LEAD, draft.item(fresh).kind)
    }

    @Test
    fun aHeldOverPostWaitsForTheArchiveSoonerWhenItsKindIsSkipped() {
        val loved = FeedProfile(2, "Slow Blog", CadenceTier.WEEKLY, medianIntervalHours = 168.0, affinity = 0.9)
        // Two weeks old and run once unopened: 0.93 × 0.38 × 1.45 × 0.5 ≈ 0.26, above the 0.2 it needs.
        val held = post(loved, ageHours = 14 * 24.0).copy(isHeldOver = true, editionsRunUnopened = 1)
        val other = post(ars, ageHours = 3.0)
        fun build(w: Double) = builder { _, candidate -> WithinFeedWeight(if (candidate.articleId == held.articleId) w else 1.0) }
            .build(listOf(held, other), listOf(ars, loved), EditionLength.STANDARD, release)

        assertEquals(ItemKind.FRONT, build(1.0).item(held).kind)
        // The score it is judged on carries W: at 0.7 it drops to 0.18 and leaves the regular pages.
        val skipped = build(0.7).item(held)
        assertEquals(ItemKind.ARCHIVE, skipped.kind)
        assertTrue(skipped.score < RankingConfig.Default.heldOverMinScore)
        // Held over is still what the reader is told, even when W lifts the post.
        assertEquals(
            "Held over: still unread from Slow Blog, a source you read a lot",
            builder { _, _ -> WithinFeedWeight(1.4, WithinFeedSignal(WithinFeedSignalKind.CATEGORY, "space", 0.4, rate = 0.8)) }
                .build(listOf(held, other), listOf(ars, loved), EditionLength.STANDARD, release)
                .item(held).reason,
        )
    }

    @Test
    fun aSyndicatedStoryIsStillKeptFromTheFeedWithTheHigherAffinity() {
        val liked = FeedProfile(3, "Liked", CadenceTier.DAILY, medianIntervalHours = 24.0, affinity = 0.8)
        val soSo = FeedProfile(4, "So-so", CadenceTier.DAILY, medianIntervalHours = 24.0, affinity = 0.6)
        val title = "Probe reaches the edge of the solar system"
        val fromLiked = post(liked, ageHours = 3.0, title = title)
        val fromSoSo = post(soSo, ageHours = 3.0, title = title)
        // W makes the so-so feed's copy the better-scoring one: 0.72 × 1.4 against 0.86.
        val source = WithinFeedSource { feed, _ -> WithinFeedWeight(if (feed.id == soSo.id) 1.4 else 1.0) }

        val draft = builder(source).build(listOf(fromSoSo, fromLiked), listOf(liked, soSo), EditionLength.STANDARD, release)

        assertEquals(1, draft.duplicatesDropped)
        assertEquals(listOf(fromLiked.articleId), draft.items.map { it.articleId })
    }

    @Test
    fun thePullOutOrdersMissedStoriesByWToo() {
        val lastVisit = release.minus(Duration.ofDays(3))
        val politics = post(ars, ageHours = 80.0)
        val space = post(ars, ageHours = 84.0)
        val today = post(FeedProfile(5, "Other"), ageHours = 2.0)
        val traits = mapOf(politics.articleId to about("politics"), space.articleId to about("space"))
        val away = AwayPool(listOf(politics, space), lastVisit)
        val feeds = listOf(ars, FeedProfile(5, "Other"))

        fun awayPage(builder: EditionBuilder) = builder.build(listOf(today), feeds, EditionLength.STANDARD, release, away = away)
            .items.filter { it.kind == ItemKind.AWAY }

        assertEquals(listOf(politics.articleId, space.articleId), awayPage(EditionBuilder()).map { it.articleId })
        val page = awayPage(builder(spaceReader().source(traits)))
        assertEquals(listOf(space.articleId, politics.articleId), page.map { it.articleId })
        // The pull-out keeps its own reason line.
        assertTrue(page.all { it.reason.startsWith("While you were away") })
    }
}
