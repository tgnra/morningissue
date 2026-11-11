package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import net.tgnra.morningissue.model.EnclosureKind

class WithinFeedTest {
    private val now: Instant = Instant.parse("2026-11-10T06:30:00Z")
    private val learner = WithinFeed()
    private var nextArticleId = 1L

    private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    /** A post that ran at [at]; [read] means opened and read (one "post read"), [liked] adds a Like. */
    private fun labelled(traits: PostTraits, read: Boolean, liked: Boolean = false, at: Instant = now) = LabelledPost(
        articleId = nextArticleId++,
        evidence = ArticleEvidence(
            feedId = traits.feedId,
            ranAt = listOf(at),
            openedAt = at.takeIf { read },
            readAt = at.takeIf { read },
            likedAt = at.takeIf { liked },
        ),
        traits = traits,
    )

    private fun about(vararg categories: String, feedId: Long = 1) = PostTraits(feedId, categories = categories.toSet())

    /** Feed 1: [read] read and [skipped] skipped posts about each category. */
    private fun history(vararg categories: Triple<String, Int, Int>, at: Instant = now): List<LabelledPost> =
        categories.flatMap { (category, read, skipped) ->
            List(read) { labelled(about(category), read = true, at = at) } + List(skipped) { labelled(about(category), read = false, at = at) }
        }

    private fun w(model: WithinFeedModel, traits: PostTraits) = model.weight(traits).multiplier

    @Test
    fun noEvidenceIsExactlyNeutral() {
        val post = PostTraits(1, categories = setOf("space"), wordCount = 2000, hasCode = true, embedding = floatArrayOf(1f, 0f))

        assertSame(WithinFeedWeight.Neutral, WithinFeedModel.empty().weight(post))
        assertSame(WithinFeedWeight.Neutral, learner.learn(emptyList(), now).weight(post))
        assertEquals(1.0, WithinFeedWeight.Neutral.multiplier, 0.0)
        assertNull(WithinFeedWeight.Neutral.signal)

        // Posts that were neither printed nor touched are no evidence.
        val untouched = List(20) { LabelledPost(it.toLong(), ArticleEvidence(1), about("space")) }
        assertSame(WithinFeedWeight.Neutral, learner.learn(untouched, now).weight(post))

        // Another feed's record says nothing about this feed's keys.
        val elsewhere = learner.learn(history("space" to 10 and 0, "politics" to 0 and 20).map { it.inFeed(2) }, now)
        assertSame(WithinFeedWeight.Neutral, elsewhere.weight(about("space")))
        assertNull(elsewhere.feed(1))
    }

    @Test
    fun aCategoryTheReaderOpensInAFeedTheyMostlySkipRises() {
        val model = learner.learn(history("space" to 10 and 0, "politics" to 0 and 20), now)

        // The feed's rate is a third of a post read; it is trusted 30 / (30 + 12).
        val feed = model.feed(1)!!
        assertEquals(1.0 / 3.0, feed.rate, 1e-12)
        assertEquals(30.0, feed.exposure, 1e-12)
        assertEquals(30.0 / 42.0, feed.trust, 1e-12)
        // space: (10 × ⅓ + 10) / (10 + 10) = ⅔, a third above the feed.
        assertEquals(1.0 / 3.0, feed.lift(TraitKey(WithinFeedSignalKind.CATEGORY, "space")), 1e-12)
        // politics: (10 × ⅓ + 0) / (10 + 20) = 1/9, two ninths below.
        assertEquals(-2.0 / 9.0, feed.lift(TraitKey(WithinFeedSignalKind.CATEGORY, "politics")), 1e-12)

        val space = model.weight(about("space"))
        assertEquals(exp(30.0 / 42.0 / 3.0), space.multiplier, 1e-12)
        assertEquals(WithinFeedSignalKind.CATEGORY, space.signal!!.kind)
        assertEquals("space", space.signal.label)
        assertEquals(2.0 / 3.0, space.signal.rate!!, 1e-12)

        val politics = model.weight(about("politics"))
        assertEquals(exp(-30.0 / 42.0 * 2.0 / 9.0), politics.multiplier, 1e-12)
        assertEquals("politics", politics.signal!!.label)

        // A category the feed has never used, and a post without categories, are left alone.
        assertEquals(1.0, w(model, about("gardening")), 0.0)
        assertEquals(1.0, w(model, PostTraits(1)), 0.0)
    }

    @Test
    fun aCategoryTheReaderSkipsInAFeedTheyMostlyReadFalls() {
        val model = learner.learn(history("essays" to 25 and 0, "sponsor" to 0 and 5), now)

        assertEquals(25.0 / 30.0, model.feed(1)!!.rate, 1e-12)
        val sponsor = w(model, about("sponsor"))
        val essays = w(model, about("essays"))
        // sponsor: (0 − 5 × ⅚) / (10 + 5) below the feed; essays: (25 − 25 × ⅚) / (10 + 25) above.
        assertEquals(exp(-30.0 / 42.0 * (25.0 / 6.0) / 15.0), sponsor, 1e-12)
        assertEquals(exp(30.0 / 42.0 * (25.0 / 6.0) / 35.0), essays, 1e-12)
        assertTrue(sponsor < 0.85)
        // Reading nearly everything does not make the feed's usual fare look special.
        assertTrue(essays < 1.1)
    }

    @Test
    fun aFeedTheReaderAlwaysOpensDoesNotMakeItsKeysLookGood() {
        val allRead = learner.learn(history("space" to 20 and 0, "politics" to 20 and 0), now)
        val allSkipped = learner.learn(history("space" to 0 and 20, "politics" to 0 and 20), now)

        for (model in listOf(allRead, allSkipped)) {
            assertEquals(1.0, w(model, about("space")), 1e-12)
            assertEquals(1.0, w(model, about("politics")), 1e-12)
        }
    }

    @Test
    fun aKeyCarriedByEveryPostIsNeutral() {
        // Every post is filed under "blog", is long and has code; a third are also about space.
        fun post(space: Boolean) = PostTraits(
            feedId = 1,
            categories = if (space) setOf("blog", "space") else setOf("blog"),
            wordCount = 3000,
            hasCode = true,
        )
        val posts = List(7) { labelled(post(space = true), read = true) } +
            List(3) { labelled(post(space = true), read = false) } +
            List(3) { labelled(post(space = false), read = true) } +
            List(17) { labelled(post(space = false), read = false) }
        val model = learner.learn(posts, now)
        val feed = model.feed(1)!!

        assertEquals(0.0, feed.lift(TraitKey(WithinFeedSignalKind.CATEGORY, "blog")), 0.0)
        assertEquals(0.0, feed.lift(TraitKey(WithinFeedSignalKind.LENGTH, "long")), 0.0)
        assertEquals(0.0, feed.lift(TraitKey(WithinFeedSignalKind.CODE)), 0.0)
        // A post with only the universal keys is exactly neutral, although it lacks "space".
        assertSame(WithinFeedWeight.Neutral, model.weight(post(space = false)))
        // The key that does tell posts apart still works, and is the one named.
        val space = model.weight(post(space = true))
        assertTrue(space.multiplier > 1.1)
        assertEquals("space", space.signal!!.label)
    }

    @Test
    fun everyKindOfKeyIsLearned() {
        fun check(kind: WithinFeedSignalKind, label: String, with: PostTraits, without: PostTraits) {
            val posts = List(12) { labelled(with, read = true) } + List(18) { labelled(without, read = false) }
            val weight = learner.learn(posts, now).weight(with)
            assertTrue("$kind", weight.multiplier > 1.2)
            assertEquals(kind, weight.signal!!.kind)
            assertEquals(label, weight.signal.label)
        }
        check(WithinFeedSignalKind.CATEGORY, "space", about("space"), PostTraits(1))
        check(WithinFeedSignalKind.LENGTH, "long", PostTraits(1, wordCount = 1501), PostTraits(1, wordCount = 1500))
        check(WithinFeedSignalKind.LENGTH, "brief", PostTraits(1, wordCount = 0), PostTraits(1, wordCount = 151))
        check(WithinFeedSignalKind.ENCLOSURE, "audio", PostTraits(1, enclosure = EnclosureKind.AUDIO), PostTraits(1))
        check(WithinFeedSignalKind.ENCLOSURE, "video", PostTraits(1, enclosure = EnclosureKind.VIDEO), PostTraits(1, enclosure = EnclosureKind.OTHER))
        check(WithinFeedSignalKind.LINK_POST, "", PostTraits(1, isLinkPost = true), PostTraits(1))
        check(WithinFeedSignalKind.CODE, "", PostTraits(1, hasCode = true), PostTraits(1))
        check(WithinFeedSignalKind.TITLE_PREFIX, "links", PostTraits(1, titlePrefix = "links"), PostTraits(1))
    }

    @Test
    fun lengthBucketsComeFromTheConfig() {
        val config = RankingConfig.Default
        fun bucket(words: Int, with: RankingConfig = config) = WithinFeed.lengthBucket(with, words)

        assertNull(bucket(PostTraits.UNKNOWN_WORD_COUNT))
        assertEquals(listOf("brief", "brief", "short", "short", "medium", "medium", "long"), listOf(0, 150, 151, 500, 501, 1500, 1501).map { bucket(it) })
        assertEquals("long", bucket(900, RankingConfig(lengthMediumMaxWords = 800)))
    }

    @Test
    fun theCapsHold() {
        // Every space post read and Liked, every politics post ignored, two hundred of each.
        val posts = List(200) { labelled(about("space"), read = true, liked = true) } + List(200) { labelled(about("politics"), read = false) }
        val model = learner.learn(posts, now)

        assertEquals(1.4, w(model, about("space")), 0.0)
        assertEquals(0.7, w(model, about("politics")), 0.0)

        val narrow = WithinFeed(RankingConfig(withinFeedMinWeight = 0.9, withinFeedMaxWeight = 1.1)).learn(posts, now)
        assertEquals(1.1, w(narrow, about("space")), 0.0)
        assertEquals(0.9, w(narrow, about("politics")), 0.0)
    }

    @Test
    fun fewLabelsBarelyMoveAPost() {
        // The same reader, seen for longer: a third of the posts are about space and all of those are read.
        val weights = listOf(1, 2, 4, 8, 16).map { n -> w(learner.learn(history("space" to n and 0, "politics" to 0 and 2 * n), now), about("space")) }

        // One read post out of three: (1 − ⅓) / (10 + 1) trusted 3 / (3 + 12).
        assertEquals(exp(3.0 / 15.0 * (2.0 / 3.0) / 11.0), weights[0], 1e-12)
        assertTrue(weights[0] < 1.02)
        assertTrue(weights[1] < 1.05)
        assertEquals(weights.sorted(), weights)
        // Two dozen labelled posts: (8 − 8 × ⅓) / (10 + 8) trusted 24 / (24 + 12).
        assertEquals(exp(24.0 / 36.0 * (16.0 / 3.0) / 18.0), weights[3], 1e-12)
        assertTrue(weights[3] > 1.2)
    }

    @Test
    fun correlatedKeysCountOnce() {
        // The same twelve episodes, marked one way or three.
        val once = PostTraits(1, titlePrefix = "podcast")
        val thrice = PostTraits(1, categories = setOf("podcast"), enclosure = EnclosureKind.AUDIO, titlePrefix = "podcast")
        fun model(episode: PostTraits) = learner.learn(
            List(9) { labelled(episode, read = true) } + List(3) { labelled(episode, read = false) } + List(24) { labelled(PostTraits(1), read = false) },
            now,
        )

        val single = model(once).weight(once)
        val triple = model(thrice).weight(thrice)
        assertTrue(single.multiplier > 1.15 && single.multiplier < 1.4)
        assertEquals(single.multiplier, triple.multiplier, 0.0)
        // The first of the equal keys is named: categories come before the enclosure and the prefix.
        assertEquals(WithinFeedSignalKind.CATEGORY, triple.signal!!.kind)

        // The same on the way down.
        fun disliked(episode: PostTraits) = learner.learn(
            List(12) { labelled(episode, read = false) } + List(24) { labelled(PostTraits(1), read = true) },
            now,
        ).weight(episode).multiplier
        assertTrue(disliked(once) < 0.9)
        assertEquals(disliked(once), disliked(thrice), 0.0)
    }

    @Test
    fun theStrongestLiftAndTheStrongestDragNetOut() {
        // The reader reads space and skips the rest; whatever the subject, they skip brief posts more.
        fun post(category: String, words: Int) = PostTraits(1, categories = setOf(category), wordCount = words)
        val posts = List(10) { labelled(post("space", 900), read = true) } +
            List(2) { labelled(post("space", 100), read = true) } +
            List(4) { labelled(post("space", 100), read = false) } +
            List(8) { labelled(post("politics", 900), read = false) } +
            List(6) { labelled(post("politics", 100), read = false) }
        val model = learner.learn(posts, now)
        val feed = model.feed(1)!!
        val space = feed.lift(TraitKey(WithinFeedSignalKind.CATEGORY, "space"))
        val brief = feed.lift(TraitKey(WithinFeedSignalKind.LENGTH, "brief"))
        val medium = feed.lift(TraitKey(WithinFeedSignalKind.LENGTH, "medium"))
        assertTrue(space > medium && medium > 0.0 && brief < 0.0)

        // A medium post about space: both keys lift it, and only the stronger one counts.
        val both = model.weight(post("space", 900))
        assertEquals(exp(feed.trust * space), both.multiplier, 1e-12)
        assertEquals("space", both.signal!!.label)
        // A brief post about space: the lift and the drag are added in the logarithm.
        val conflict = model.weight(post("space", 100))
        assertEquals(exp(feed.trust * (space + brief)), conflict.multiplier, 1e-12)
        assertEquals("space", conflict.signal!!.label)
        assertTrue(conflict.multiplier > 1.0 && conflict.multiplier < both.multiplier)
    }

    @Test
    fun oldEvidenceFades() {
        fun weightFrom(at: Instant) = w(learner.learn(history("space" to 10 and 0, "politics" to 0 and 20, at = at), now), about("space"))

        val fresh = weightFrom(now)
        val month = weightFrom(daysAgo(30))
        val quarter = weightFrom(daysAgo(90))
        // One half-life: every tally is halved. (5 − 5 × ⅓) / (10 + 5), trusted 15 / (15 + 12).
        assertEquals(exp(15.0 / 27.0 * (10.0 / 3.0) / 15.0), month, 1e-9)
        assertTrue(fresh > month && month > quarter && quarter > 1.0)
        assertTrue(quarter < 1.03)

        // What the reader does now outweighs what they did three months ago.
        val changed = history("space" to 20 and 0, "politics" to 0 and 20, at = daysAgo(90)) +
            history("space" to 0 and 6, "politics" to 6 and 0)
        val model = learner.learn(changed, now)
        assertTrue(w(model, about("space")) < 1.0)
        assertTrue(w(model, about("politics")) > 1.0)

        // Learning is as of the instant given: the same record seen a month later is a month older.
        val later = learner.learn(history("space" to 10 and 0, "politics" to 0 and 20), now.plus(Duration.ofDays(30)))
        assertEquals(month, w(later, about("space")), 1e-9)
    }

    @Test
    fun likesCountForMoreThanReads() {
        fun weight(liked: Boolean) = w(
            learner.learn(List(6) { labelled(about("space"), read = true, liked = liked) } + List(24) { labelled(about("politics"), read = false) }, now),
            about("space"),
        )
        assertTrue(weight(liked = true) > weight(liked = false))
    }

    // Text taste

    private val random = Random(7)

    /** A unit vector near axis [axis] of a [dimension]-dimensional space. */
    private fun near(axis: Int, dimension: Int = 16, spread: Double = 0.35): FloatArray {
        val vector = DoubleArray(dimension) { random.nextGaussian() * spread / Math.sqrt(dimension.toDouble()) }
        vector[axis] += 1.0
        val length = Math.sqrt(vector.sumOf { it * it })
        return FloatArray(dimension) { (vector[it] / length).toFloat() }
    }

    private fun textPost(feedId: Long, axis: Int) = PostTraits(feedId, embedding = near(axis))

    /**
     * Four feeds with nothing but vectors. Each has [perFeed] posts near axis 0, all read (the
     * first of them Liked when [liked]), and as many near axis 1, all skipped.
     */
    private fun clusters(perFeed: Int, liked: Boolean = true): List<LabelledPost> = (1..4L).flatMap { feedId ->
        List(perFeed) { labelled(textPost(feedId, 0), read = true, liked = liked && it == 0) } +
            List(perFeed) { labelled(textPost(feedId, 1), read = false) }
    }

    @Test
    fun postsCloseToTheLikedClusterRiseAndThoseCloseToTheSkippedOneFall() {
        val model = learner.learn(clusters(perFeed = 40), now)
        val text = model.text!!

        assertEquals(16, text.dimension)
        assertEquals(320.0, text.labelled, 1e-9)
        assertEquals(320.0 / 520.0, text.trust, 1e-12)
        assertTrue(text.fromLikes)
        // The clusters sit about one deviation either side of the middle, and a read post is
        // worth about half a post read more than its feed's average.
        assertTrue(text.slope > 0.45 && text.slope < 0.6)

        val liked = model.weight(textPost(1, 0))
        assertTrue(liked.multiplier > 1.25)
        assertEquals(WithinFeedSignalKind.TEXT, liked.signal!!.kind)
        assertTrue(liked.signal.fromLikes)
        assertNull(liked.signal.rate)
        // The effect is what the signal asked for; the multiplier is what the cap allowed.
        assertTrue(liked.signal.effect >= ln(liked.multiplier) - 1e-12)

        val skipped = model.weight(textPost(1, 1))
        assertTrue(skipped.multiplier < 0.8)
        assertEquals(WithinFeedSignalKind.TEXT, skipped.signal!!.kind)

        // The taste is pooled: a feed with no record at all is ordered by it too.
        assertNull(model.feed(9))
        assertTrue(w(model, textPost(9, 0)) > 1.25)
        assertTrue(w(model, textPost(9, 1)) < 0.8)
        // A post about something else altogether sits in between.
        assertTrue(w(model, textPost(9, 5)) in 0.85..1.15)
    }

    @Test
    fun postsWithoutAVectorTakeNoPartInTheTextTaste() {
        val base = clusters(perFeed = 15)
        val model = learner.learn(base, now)
        // More posts in a feed of their own: no vector, a vector of another length, a zero vector.
        val extra = List(10) { labelled(PostTraits(8), read = it % 2 == 0) } +
            List(10) { labelled(PostTraits(8, embedding = FloatArray(8) { 1f }), read = it % 2 == 0) } +
            List(10) { labelled(PostTraits(8, embedding = FloatArray(16)), read = it % 2 == 0) }
        val withExtra = learner.learn(base + extra, now)

        assertEquals(model.text!!.slope, withExtra.text!!.slope, 0.0)
        assertEquals(model.text.labelled, withExtra.text.labelled, 0.0)
        val candidate = textPost(1, 0)
        assertEquals(w(model, candidate), w(withExtra, candidate), 0.0)

        // A candidate without a usable vector is neutral, whatever the taste.
        assertSame(WithinFeedWeight.Neutral, model.weight(PostTraits(1)))
        assertSame(WithinFeedWeight.Neutral, model.weight(PostTraits(1, embedding = FloatArray(8) { 1f })))
        assertSame(WithinFeedWeight.Neutral, model.weight(PostTraits(1, embedding = FloatArray(16))))

        // No vectors at all: no text model, and nothing breaks.
        assertNull(learner.learn(history("space" to 10 and 0, "politics" to 0 and 20), now).text)
    }

    @Test
    fun onlyTheDirectionOfAVectorCounts() {
        val model = learner.learn(clusters(perFeed = 15), now)
        // Halfway between the read cluster and an unrelated subject, so the cap is not reached.
        val towards = near(0)
        val aside = near(5)
        val vector = FloatArray(16) { towards[it] + aside[it] }
        val scaled = FloatArray(vector.size) { vector[it] * 4f }

        assertTrue(w(model, PostTraits(1, embedding = vector)) in 1.05..1.39)
        assertEquals(w(model, PostTraits(1, embedding = vector)), w(model, PostTraits(1, embedding = scaled)), 1e-6)
    }

    @Test
    fun theTextTasteIsShrunkUntilThereAreEnoughLabelledVectors() {
        val few = learner.learn(clusters(perFeed = 2), now)
        val some = learner.learn(clusters(perFeed = 6), now)
        val many = learner.learn(clusters(perFeed = 30), now)

        assertEquals(16.0 / 216.0, few.text!!.trust, 1e-12)
        assertEquals(48.0 / 248.0, some.text!!.trust, 1e-12)
        assertEquals(240.0 / 440.0, many.text!!.trust, 1e-12)
        val candidate = textPost(1, 0)
        val weights = listOf(few, some, many).map { w(it, candidate) }
        assertEquals(weights.sorted(), weights)
        assertTrue(weights.first() > 1.0 && weights.first() < 1.1)

        // The threshold is a config constant.
        val eager = WithinFeed(RankingConfig(withinFeedTextPriorPosts = 0.0)).learn(clusters(perFeed = 2), now)
        assertEquals(1.0, eager.text!!.trust, 0.0)
    }

    @Test
    fun aTextLabelIsRelativeToItsOwnFeed() {
        // Feed 1 is all about axis 0 and always read; feed 2 is all about axis 1 and never read.
        // Within each feed nothing sets one post apart from another, so there is no taste to learn.
        val posts = List(40) { labelled(textPost(1, 0), read = true) } + List(40) { labelled(textPost(2, 1), read = false) }
        val model = learner.learn(posts, now)

        assertEquals(1.0, w(model, textPost(1, 0)), 0.0)
        assertEquals(1.0, w(model, textPost(2, 1)), 0.0)
        assertEquals(1.0, w(model, textPost(3, 0)), 0.0)
    }

    @Test
    fun theTextLineOnlyNamesLikesWhenThereAreSome() {
        val model = learner.learn(clusters(perFeed = 15, liked = false), now)
        val weight = model.weight(textPost(1, 0))

        assertEquals(WithinFeedSignalKind.TEXT, weight.signal!!.kind)
        assertTrue(!weight.signal.fromLikes)
    }

    @Test
    fun textAndKeysDoNotCompound() {
        // The read cluster is also filed under "space": the same fact, told by a key and by the vectors.
        val posts = (1..4L).flatMap { feedId ->
            List(15) { labelled(PostTraits(feedId, categories = setOf("space"), embedding = near(0)), read = true) } +
                List(15) { labelled(PostTraits(feedId, categories = setOf("politics"), embedding = near(1)), read = false) }
        }
        val model = learner.learn(posts, now)
        val vector = near(0)
        val byKey = w(model, PostTraits(1, categories = setOf("space")))
        val byText = w(model, PostTraits(1, embedding = vector))
        val byBoth = w(model, PostTraits(1, categories = setOf("space"), embedding = vector))

        assertTrue(byKey > 1.1 && byText > 1.1)
        assertEquals(maxOf(byKey, byText), byBoth, 0.0)
    }

    @Test
    fun learningIsDeterministic() {
        val posts = clusters(perFeed = 15) + history("space" to 10 and 0, "politics" to 0 and 20).map { it.inFeed(7) }
        val candidates = listOf(textPost(1, 0), textPost(2, 1), textPost(3, 4), about("space", feedId = 7), about("politics", feedId = 7))

        val first = learner.learn(posts, now)
        val again = WithinFeed().learn(posts.toList(), now)
        for (candidate in candidates) assertEquals(first.weight(candidate), again.weight(candidate))

        // The order posts are passed in does not matter, beyond rounding in the sums.
        val shuffled = learner.learn(posts.shuffled(Random(3)), now)
        for (candidate in candidates) {
            assertEquals(first.weight(candidate).multiplier, shuffled.weight(candidate).multiplier, 1e-6)
            assertEquals(first.weight(candidate).signal?.kind, shuffled.weight(candidate).signal?.kind)
        }
    }

    @Test
    fun theSourceLooksTraitsUpByArticle() {
        val model = learner.learn(history("space" to 10 and 0, "politics" to 0 and 20), now)
        val source = model.source(mapOf(100L to about("space"), 101L to about("politics")))
        val feed = FeedProfile(1, "Example")
        fun candidate(id: Long) = Candidate(articleId = id, feedId = 1, publishedAt = now, firstSeenAt = now)

        assertEquals(model.weight(about("space")), source.weight(feed, candidate(100)))
        assertEquals(model.weight(about("politics")), source.weight(feed, candidate(101)))
        assertSame(WithinFeedWeight.Neutral, source.weight(feed, candidate(102)))
        assertNotNull(source.weight(feed, candidate(100)).signal)
    }

    private infix fun Pair<String, Int>.and(skipped: Int) = Triple(first, second, skipped)

    private fun LabelledPost.inFeed(feedId: Long) = LabelledPost(
        articleId,
        evidence.copy(feedId = feedId),
        PostTraits(feedId, traits.categories, traits.wordCount, traits.enclosure, traits.isLinkPost, traits.hasCode, embedding = traits.embedding),
    )
}
