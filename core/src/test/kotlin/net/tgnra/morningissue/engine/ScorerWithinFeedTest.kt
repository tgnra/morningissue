package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** W in the score and in "Why is this here?". */
class ScorerWithinFeedTest {
    private val release: Instant = Instant.parse("2026-11-10T06:30:00Z")
    private val plain = Scorer()

    private fun feed(
        affinity: Double = 0.5,
        medianHours: Double? = 24.0,
        tier: CadenceTier = CadenceTier.DAILY,
        slider: SliderStep = SliderStep.AUTO,
        title: String = "Ars Technica",
    ) = FeedProfile(id = 1, title = title, tier = tier, medianIntervalHours = medianHours, affinity = affinity, sliderStep = slider)

    private fun post(ageHours: Double = 2.0) = Candidate(
        articleId = 1,
        feedId = 1,
        publishedAt = release.minus(Duration.ofMinutes((ageHours * 60).toLong())),
        firstSeenAt = release,
    )

    private fun signal(kind: WithinFeedSignalKind, label: String = "", rate: Double? = 0.7, fromLikes: Boolean = false) =
        WithinFeedSignal(kind, label, effect = 0.25, rate = rate, fromLikes = fromLikes)

    private fun scorerWith(weight: WithinFeedWeight) = Scorer(withinFeedSource = { _, _ -> weight })

    private fun reason(
        weight: WithinFeedWeight,
        feed: FeedProfile = feed(),
        post: Candidate = post(),
        kind: ItemKind? = null,
    ): String {
        val scorer = scorerWith(weight)
        return scorer.reason(feed, post, scorer.score(feed, post, release), kind)
    }

    private fun lifted(kind: WithinFeedSignalKind, label: String = "", w: Double = 1.3, rate: Double? = 0.7, fromLikes: Boolean = false) =
        WithinFeedWeight(w, signal(kind, label, rate, fromLikes))

    @Test
    fun withoutASourceTheScoreIsWhatItWas() {
        val score = plain.score(feed(affinity = 0.8, medianHours = 48.0), post(20.0), release)

        assertEquals(1.0, score.w, 0.0)
        assertSame(WithinFeedWeight.Neutral, score.within)
        assertEquals(score.a * score.f * score.s * score.p * score.c, score.value, 0.0)
    }

    @Test
    fun wMultipliesTheScore() {
        val feed = feed(affinity = 0.8, medianHours = 48.0)
        val before = plain.score(feed, post(20.0), release)
        val up = scorerWith(lifted(WithinFeedSignalKind.CATEGORY, "space", w = 1.25)).score(feed, post(20.0), release)
        val down = scorerWith(WithinFeedWeight(0.8)).score(feed, post(20.0), release)

        assertEquals(1.25, up.w, 0.0)
        assertEquals(before.value * 1.25, up.value, 1e-12)
        assertEquals(before.value * 0.8, down.value, 1e-12)
        assertEquals(up.a * up.f * up.s * up.p * up.c * up.w, up.value, 0.0)
        // Every other factor is untouched.
        assertEquals(before, up.copy(within = WithinFeedWeight.Neutral))
        // A read post stays at zero whatever its W.
        assertEquals(0.0, scorerWith(WithinFeedWeight(1.4)).score(feed, post().copy(isRead = true), release).value, 0.0)
    }

    @Test
    fun theScorerHoldsAnySourceToTheCaps() {
        val high = scorerWith(lifted(WithinFeedSignalKind.CATEGORY, "space", w = 3.0)).score(feed(), post(), release)
        val low = scorerWith(WithinFeedWeight(0.1)).score(feed(), post(), release)

        // withinFeedMinWeight and withinFeedMaxWeight: the bounds of W as a whole, keys and
        // text taste together.
        assertEquals(2.0, high.w, 0.0)
        assertEquals("space", high.within.signal!!.label)
        assertEquals(0.5, low.w, 0.0)
    }

    @Test
    fun theSourceSeesTheFeedAndTheCandidate() {
        val scorer = Scorer(withinFeedSource = { feed, candidate -> WithinFeedWeight(if (feed.id == 1L && candidate.articleId == 1L) 1.2 else 0.9) })

        assertEquals(1.2, scorer.score(feed(), post(), release).w, 0.0)
        assertEquals(0.9, scorer.score(feed(), post().copy(articleId = 2), release).w, 0.0)
    }

    @Test
    fun reasonNamesTheWithinFeedSignal() {
        assertEquals(
            "You usually read Ars Technica's posts about space",
            reason(lifted(WithinFeedSignalKind.CATEGORY, "space")),
        )
        assertEquals("You usually read long posts from Ars Technica", reason(lifted(WithinFeedSignalKind.LENGTH, "long")))
        assertEquals("You usually read brief posts from Ars Technica", reason(lifted(WithinFeedSignalKind.LENGTH, "brief")))
        assertEquals("You usually open audio posts from Ars Technica", reason(lifted(WithinFeedSignalKind.ENCLOSURE, "audio")))
        assertEquals("You usually open video posts from Ars Technica", reason(lifted(WithinFeedSignalKind.ENCLOSURE, "video")))
        assertEquals("You usually open posts with attachments from Ars Technica", reason(lifted(WithinFeedSignalKind.ENCLOSURE, "other")))
        assertEquals("You usually read link posts from Ars Technica", reason(lifted(WithinFeedSignalKind.LINK_POST)))
        assertEquals("You usually read Ars Technica's own pieces rather than its links", reason(lifted(WithinFeedSignalKind.LINK_POST, WithinFeed.NOT)))
        assertEquals("You usually read Ars Technica's posts without code", reason(lifted(WithinFeedSignalKind.CODE, WithinFeed.NOT)))
        assertEquals("You usually read posts with code from Ars Technica", reason(lifted(WithinFeedSignalKind.CODE)))
        assertEquals("You usually read \"links\" posts from Ars Technica", reason(lifted(WithinFeedSignalKind.TITLE_PREFIX, "links")))
        assertEquals("Similar to posts you have Liked", reason(lifted(WithinFeedSignalKind.TEXT, rate = null, fromLikes = true)))
        assertEquals("Similar to posts you have read", reason(lifted(WithinFeedSignalKind.TEXT, rate = null)))
    }

    @Test
    fun aWeakOrNegativeWGivesNoReasonOfItsOwn() {
        val new = "New from Ars Technica since the last edition"

        // Strong enough from 1.15.
        assertEquals(new, reason(lifted(WithinFeedSignalKind.CATEGORY, "space", w = 1.14)))
        assertTrue(reason(lifted(WithinFeedSignalKind.CATEGORY, "space", w = 1.15)).startsWith("You usually read"))
        // Below 1 the post is explained as it would have been.
        assertEquals(new, reason(WithinFeedWeight(0.7, signal(WithinFeedSignalKind.CATEGORY, "politics", rate = 0.1).copy(effect = -0.4))))
        assertEquals(
            "You read most posts from Ars Technica",
            reason(WithinFeedWeight(0.7, signal(WithinFeedSignalKind.CATEGORY, "politics").copy(effect = -0.4)), feed(affinity = 0.9)),
        )
        // "Usually" is only said of posts the reader reads at least as often as not.
        assertEquals(new, reason(lifted(WithinFeedSignalKind.CATEGORY, "space", rate = 0.45)))
        assertTrue(reason(lifted(WithinFeedSignalKind.CATEGORY, "space", rate = 0.5)).startsWith("You usually read"))
        // A lift with no signal named cannot be explained, and is not.
        assertEquals(new, reason(WithinFeedWeight(1.3)))
    }

    @Test
    fun theWithinFeedSignalSpeaksOnlyWhenItPullsHardest() {
        val space = lifted(WithinFeedSignalKind.CATEGORY, "space", w = 1.3)
        val spaceAtCap = lifted(WithinFeedSignalKind.CATEGORY, "space", w = 1.4)

        // A loved feed (pull 0.8) outweighs W = 1.3 (pull 0.75), but not W at its cap.
        assertEquals("You read most posts from Ars Technica", reason(space, feed(affinity = 0.9)))
        assertEquals("You usually read Ars Technica's posts about space", reason(spaceAtCap, feed(affinity = 0.9)))
        // A mildly liked feed says nothing itself, so the signal does.
        assertEquals("You usually read Ars Technica's posts about space", reason(space, feed(affinity = 0.6)))
        // A favourite is a favourite; a tie goes to the reason there was before.
        assertEquals("Ars Technica is one of your favourites", reason(spaceAtCap, feed(slider = SliderStep.FAVOURITE)))
        // A monthly blog's post is there for its rarity (pull 0.92), a weekly one's (0.56) for the subject.
        assertTrue(reason(space, feed(medianHours = 30 * 24.0, tier = CadenceTier.RARE)).startsWith("Rare post"))
        assertEquals(
            "You usually read Ars Technica's posts about space",
            reason(space, feed(medianHours = 7 * 24.0, tier = CadenceTier.WEEKLY)),
        )
    }

    @Test
    fun archiveAwayComebackAndHeldOverKeepPriority() {
        val space = lifted(WithinFeedSignalKind.CATEGORY, "space", w = 1.4)
        val loved = feed(affinity = 0.9)

        assertEquals(
            "From the archive: you have not read this one from Ars Technica yet",
            reason(space, loved, post().copy(isHeldOver = true), ItemKind.ARCHIVE),
        )
        assertEquals(
            "While you were away: Ars Technica ran this in an edition you missed",
            reason(space, kind = ItemKind.AWAY),
        )
        assertEquals(
            "Back after 5 months: Ars Technica has posted again",
            reason(space, post = post().copy(previousPostAt = release.minus(Duration.ofDays(160)))),
        )
        assertEquals(
            "Held over: still unread from Ars Technica, a source you read a lot",
            reason(space, loved, post().copy(isHeldOver = true)),
        )
    }
}
