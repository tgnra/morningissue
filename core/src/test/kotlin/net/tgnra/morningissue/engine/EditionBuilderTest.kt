package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditionBuilderTest {
    private val release: Instant = Instant.parse("2026-11-10T06:30:00Z")
    private val builder = EditionBuilder()
    private var nextArticleId = 1L

    private fun weekly(id: Long, title: String = "Weekly $id") =
        FeedProfile(id, title, CadenceTier.WEEKLY, medianIntervalHours = 168.0)

    private fun daily(id: Long, title: String = "Daily $id") =
        FeedProfile(id, title, CadenceTier.DAILY, medianIntervalHours = 24.0)

    private fun firehose(id: Long, title: String = "Wire $id") =
        FeedProfile(id, title, CadenceTier.FIREHOSE, medianIntervalHours = 0.5)

    private fun post(feed: FeedProfile, ageHours: Double, hasImage: Boolean = false) = Candidate(
        articleId = nextArticleId++,
        feedId = feed.id,
        publishedAt = release.minus(Duration.ofMinutes((ageHours * 60).toLong())),
        firstSeenAt = release.minus(Duration.ofHours(1)),
        hasImage = hasImage,
    )

    private fun build(
        candidates: List<Candidate>,
        feeds: List<FeedProfile>,
        length: EditionLength = EditionLength.STANDARD,
    ) = builder.build(candidates, feeds, length, release)

    /** [count] weekly feeds with [postsEach] posts each, all of different ages. */
    private fun slowFeeds(count: Int, postsEach: Int): Pair<List<FeedProfile>, List<Candidate>> {
        val feeds = (1..count).map { weekly(it.toLong()) }
        val posts = feeds.flatMap { feed -> (0 until postsEach).map { post(feed, ageHours = 1.0 + feed.id + it * 30.0) } }
        return feeds to posts
    }

    private fun EditionDraft.onPage(page: Int) = items.filter { it.page == page }.sortedBy { it.slot }

    @Test
    fun highestScoreLeadsWhetherOrNotItHasAnImage() {
        val feeds = (1..6L).map { weekly(it) }
        val best = post(feeds[2], ageHours = 0.5, hasImage = false)
        val others = feeds.filter { it.id != 3L }.map { post(it, ageHours = 5.0 + it.id, hasImage = true) }

        val draft = build(others + best, feeds)

        assertEquals(best.articleId, draft.lead?.articleId)
        assertEquals(1, draft.items.count { it.kind == ItemKind.LEAD })
        assertEquals(draft.items.maxOf { it.score }, draft.lead!!.score, 0.0)
        assertEquals(0, draft.lead!!.slot)
        assertEquals(1, draft.lead!!.page)
    }

    @Test
    fun frontPageTakesAtMostOnePerFeed() {
        val busy = weekly(1, "Busy")
        val feeds = listOf(busy) + (2..8L).map { weekly(it) }
        // The busy feed has the ten best posts of all.
        val posts = (0 until 10).map { post(busy, ageHours = 0.1 * it) } +
            feeds.drop(1).map { post(it, ageHours = 24.0 + it.id) }

        val front = build(posts, feeds).onPage(1)

        assertEquals(5, front.size)
        assertEquals(listOf(ItemKind.LEAD, ItemKind.FRONT, ItemKind.FRONT, ItemKind.FRONT, ItemKind.FRONT), front.map { it.kind })
        assertEquals(5, front.map { it.feedId }.distinct().size)
        assertEquals(busy.id, front.first().feedId)
        assertEquals(front.map { it.score }.sortedDescending(), front.map { it.score })
    }

    @Test
    fun frontPageIsSmallerWhenFewerFeedsHavePosts() {
        val feeds = listOf(weekly(1), weekly(2))
        val posts = feeds.flatMap { feed -> (0 until 4).map { post(feed, ageHours = it + 1.0) } }

        val draft = build(posts, feeds)

        assertEquals(2, draft.onPage(1).size)
        assertEquals(6, draft.onPage(2).size)
        assertTrue(draft.onPage(2).all { it.kind == ItemKind.TOP })
    }

    @Test
    fun pageSizesFollowTheEditionLength() {
        val (feeds, posts) = slowFeeds(count = 20, postsEach = 5)

        fun sizes(length: EditionLength) = build(posts, feeds, length).printed.groupBy { it.page }.toSortedMap().values.map { it.size }

        // Front page, two Top stories pages, then General for these untagged feeds.
        assertEquals(listOf(5, 8, 8, 6), sizes(EditionLength.STANDARD))
        assertEquals(listOf(5, 4, 4, 3), sizes(EditionLength.SHORT))
        assertEquals(listOf(5, 12, 12, 9), sizes(EditionLength.LONG))

        val standard = build(posts, feeds)
        assertTrue(standard.onPage(2).all { it.kind == ItemKind.TOP })
        assertTrue(standard.onPage(3).all { it.kind == ItemKind.TOP })
        assertEquals((0 until 8).toList(), standard.onPage(2).map { it.slot })
        assertTrue(standard.onPage(4).all { it.kind == ItemKind.SECTION && it.section == EditionBuilder.GENERAL_SECTION })
        assertEquals(100 - 21 - 6, standard.index.size)
    }

    @Test
    fun limitsScaleWithLength() {
        val config = RankingConfig.Default
        assertEquals(EditionLimits(4, 2, 8, 6, 15), EditionLimits.of(config, EditionLength.STANDARD))
        assertEquals(EditionLimits(4, 2, 4, 3, 8, awayStories = 15), EditionLimits.of(config, EditionLength.SHORT))
        assertEquals(EditionLimits(4, 2, 12, 9, 23), EditionLimits.of(config, EditionLength.LONG))
        assertEquals(21, EditionLimits.of(config, EditionLength.STANDARD).maxMixedStories)
    }

    @Test
    fun topStoriesAreTheNextBestAfterTheFrontPage() {
        val (feeds, posts) = slowFeeds(count = 30, postsEach = 1)

        val draft = build(posts, feeds)
        val printed = draft.printed.map { it.score }

        // One post per feed: no diversity effect, so the paper is the ranking cut into pages.
        assertEquals(printed.sortedDescending(), printed)
        assertTrue(draft.index.all { it.score <= printed.min() })
    }

    @Test
    fun briefsOnlyComeFromFirehoseAndDailyFeeds() {
        val (slow, slowPosts) = slowFeeds(count = 25, postsEach = 2)
        val wire = firehose(100)
        val newsletter = daily(101)
        val fastPosts = (0 until 30).map { post(wire, ageHours = 14.0 + it * 0.1) } +
            (0 until 5).map { post(newsletter, ageHours = 60.0 + it) }

        val draft = build(slowPosts + fastPosts, slow + wire + newsletter)
        val briefs = draft.items.filter { it.kind == ItemKind.BRIEF }

        assertEquals(15, briefs.size)
        assertTrue(briefs.all { it.feedId == wire.id || it.feedId == newsletter.id })
        assertEquals(1, briefs.map { it.page }.distinct().size)
        assertEquals(5, briefs.first().page)
        assertEquals((0 until 15).toList(), briefs.map { it.slot })
        // Each feed's briefs run best first; between feeds the same-feed penalty decides.
        for (feed in listOf(wire, newsletter)) {
            val own = briefs.filter { it.feedId == feed.id }.map { it.score }
            assertEquals(own.sortedDescending(), own)
        }
        // The slow feeds' leftovers are listed, not briefed, even though they score higher.
        val slowLeftovers = draft.index.filter { it.feedId < 100 }
        assertEquals(50 - 21 - 6, slowLeftovers.size)
        assertTrue(slowLeftovers.maxOf { it.score } > briefs.minOf { it.score })
    }

    @Test
    fun briefsNeedAMinimumScore() {
        // Enough slow posts to fill the mixed pages and General, leaving the wire to the briefs.
        val (slow, slowPosts) = slowFeeds(count = 27, postsEach = 1)
        val wire = firehose(100)
        // Neutral firehose posts fall under 0.1 a little after 32 hours with the 12 h half-life.
        val fresh = (0 until 3).map { post(wire, ageHours = 20.0 + it) }
        val stale = (0 until 4).map { post(wire, ageHours = 40.0 + it) }

        val draft = build(slowPosts + fresh + stale, slow + wire)
        val briefs = draft.items.filter { it.kind == ItemKind.BRIEF }

        assertEquals(fresh.map { it.articleId }.toSet(), briefs.map { it.articleId }.toSet())
        assertTrue(briefs.all { it.score >= RankingConfig.Default.briefMinScore })
        assertTrue(draft.index.map { it.articleId }.containsAll(stale.map { it.articleId }))
    }

    @Test
    fun briefLimitFollowsTheEditionLength() {
        val wire = firehose(1)
        val posts = (0 until 80).map { post(wire, ageHours = it * 0.1) }

        fun briefs(length: EditionLength) = build(posts, listOf(wire), length).items.count { it.kind == ItemKind.BRIEF }

        assertEquals(8, briefs(EditionLength.SHORT))
        assertEquals(15, briefs(EditionLength.STANDARD))
        assertEquals(23, briefs(EditionLength.LONG))
    }

    @Test
    fun everyLeftoverLandsInTheIndexAndNothingAppearsTwice() {
        val (slow, slowPosts) = slowFeeds(count = 12, postsEach = 6)
        val wire = firehose(100)
        val wirePosts = (0 until 60).map { post(wire, ageHours = it * 1.5) }
        val all = slowPosts + wirePosts

        val draft = build(all, slow + wire)

        assertEquals(all.size, draft.items.size)
        assertEquals(all.map { it.articleId }.toSet(), draft.items.map { it.articleId }.toSet())
        assertEquals(draft.items.size, draft.items.map { it.articleId }.distinct().size)
        assertEquals(all.size - draft.printed.size, draft.index.size)
        assertTrue(draft.items.all { it.reason.isNotBlank() })
    }

    @Test
    fun pagesAreNumberedFromOneWithOnePageTypeEach() {
        val (slow, slowPosts) = slowFeeds(count = 12, postsEach = 6)
        val wire = firehose(100)
        val draft = build(slowPosts + (0 until 60).map { post(wire, ageHours = it * 1.5) }, slow + wire)

        fun family(kind: ItemKind) = if (kind == ItemKind.LEAD) ItemKind.FRONT else kind
        val byPage = draft.items.groupBy { it.page }.toSortedMap()

        assertEquals((1..byPage.size).toList(), byPage.keys.toList())
        assertEquals(
            listOf(ItemKind.FRONT, ItemKind.TOP, ItemKind.TOP, ItemKind.SECTION, ItemKind.BRIEF, ItemKind.INDEX),
            byPage.values.map { items -> items.map { family(it.kind) }.distinct().single() },
        )
        byPage.values.forEach { items -> assertEquals(items.indices.toList(), items.map { it.slot }.sorted()) }
        // Items come out in reading order.
        assertEquals(draft.items.sortedWith(compareBy({ it.page }, { it.slot })), draft.items)
    }

    @Test
    fun indexIsGroupedBySource() {
        val zebra = weekly(1, "Zebra")
        val apple = weekly(2, "apple")
        val mango = weekly(3, "Mango")
        val feeds = listOf(zebra, apple, mango) + (10..40L).map { weekly(it, "Filler $it") }
        val fillers = feeds.drop(3).map { post(it, ageHours = 1.0) }
        // Old enough to miss the printed pages.
        val old = listOf(zebra, apple, mango, zebra, apple, mango).mapIndexed { i, feed -> post(feed, ageHours = 400.0 + i) }

        val index = build(fillers + old, feeds).index.filter { it.feedId <= 3 }

        assertEquals(listOf(2L, 2L, 3L, 3L, 1L, 1L), index.map { it.feedId })
        index.groupBy { it.feedId }.values.forEach { group ->
            assertEquals(group.map { it.score }.sortedDescending(), group.map { it.score })
        }
        assertEquals(index.map { it.slot }.sorted(), index.map { it.slot })
    }

    @Test
    fun mutedFeedsAreSkipped() {
        val muted = weekly(1).copy(muteUntil = release.plus(Duration.ofDays(2)))
        val muteOver = weekly(2).copy(muteUntil = release.minus(Duration.ofHours(1)))
        val open = weekly(3)
        val posts = listOf(muted, muteOver, open).flatMap { feed -> (0 until 3).map { post(feed, ageHours = 1.0 + it) } }

        val draft = build(posts, listOf(muted, muteOver, open))

        assertEquals(3, draft.mutedSkipped)
        assertEquals(6, draft.items.size)
        assertFalse(draft.items.any { it.feedId == muted.id })
        assertTrue(draft.items.any { it.feedId == muteOver.id })
    }

    @Test
    fun postsOlderThanTheAgeLimitAndUnknownFeedsAreLeftOut() {
        val feed = weekly(1)
        val recent = post(feed, ageHours = 29 * 24.0)
        val ancient = post(feed, ageHours = 31 * 24.0)
        val orphan = post(weekly(99), ageHours = 1.0)

        val draft = build(listOf(recent, ancient, orphan), listOf(feed))

        assertEquals(listOf(recent.articleId), draft.items.map { it.articleId })
        assertEquals(1, draft.staleSkipped)
    }

    @Test
    fun aCandidateListedTwiceIsPlacedOnce() {
        val feed = weekly(1)
        val post = post(feed, ageHours = 1.0)

        val draft = build(listOf(post, post.copy(), post), listOf(feed))

        assertEquals(1, draft.items.size)
        assertEquals(ItemKind.LEAD, draft.items.single().kind)
    }

    @Test
    fun postsThatRanUnopenedSinkBelowFreshOnes() {
        val a = weekly(1)
        val b = weekly(2)
        val ranTwice = post(a, ageHours = 1.0).copy(editionsRunUnopened = 2, isHeldOver = true)
        val fresh = post(b, ageHours = 1.0)

        val draft = build(listOf(ranTwice, fresh), listOf(a, b))
        val byArticle = draft.items.associateBy { it.articleId }

        assertEquals(fresh.articleId, draft.lead?.articleId)
        assertEquals(byArticle.getValue(fresh.articleId).score * 0.25, byArticle.getValue(ranTwice.articleId).score, 1e-12)
    }

    @Test
    fun readPostsAreOnlyListed() {
        val feed = weekly(1)
        val read = post(feed, ageHours = 0.5).copy(isOpened = true, isRead = true)
        val unread = post(feed, ageHours = 50.0)

        val draft = build(listOf(read, unread), listOf(feed))

        assertEquals(unread.articleId, draft.lead?.articleId)
        assertEquals(ItemKind.INDEX, draft.items.single { it.articleId == read.articleId }.kind)
    }

    @Test
    fun onlyReadPostsGiveAnIndexWithoutPrintedPages() {
        val feed = weekly(1)
        val draft = build(listOf(post(feed, 1.0).copy(isRead = true)), listOf(feed))

        assertTrue(draft.printed.isEmpty())
        assertNull(draft.lead)
        assertEquals(1, draft.index.single().page)
    }

    @Test
    fun aBusyFeedDoesNotTakeAWholeTopStoriesPage() {
        val wire = firehose(1)
        val dailies = (2..9L).map { daily(it) }
        // The wire's posts are all fresher, and so higher scoring, than any daily post.
        val wirePosts = (0 until 40).map { post(wire, ageHours = it * 0.05) }
        val dailyPosts = dailies.map { post(it, ageHours = 14.0 + it.id) }

        val draft = build(wirePosts + dailyPosts, dailies + wire)
        val page2 = draft.onPage(2)

        assertEquals(8, page2.size)
        assertTrue(page2.count { it.feedId == wire.id } < 8)
        assertTrue(page2.any { it.feedId != wire.id })
        assertEquals(wire.id, page2.first().feedId)
    }

    @Test
    fun aBusyFeedSharesInBriefWithTheOtherFeeds() {
        // A Favourite wire: forty posts, each of them scoring above every other feed's post.
        // Enough slow posts fill the mixed pages and General, leaving In brief to the fast feeds.
        val (slow, slowPosts) = slowFeeds(count = 27, postsEach = 1)
        val wire = firehose(100).copy(sliderStep = SliderStep.FAVOURITE)
        val dailies = (101..105L).map { daily(it) }
        val wirePosts = (0 until 40).map { post(wire, ageHours = 20.0 + it * 0.05) }
        val dailyPosts = dailies.flatMap { feed -> (0 until 2).map { post(feed, ageHours = 62.0 + (feed.id - 101) + it * 0.5) } }

        val draft = build(slowPosts + wirePosts + dailyPosts, slow + wire + dailies)
        val briefs = draft.items.filter { it.kind == ItemKind.BRIEF }.sortedBy { it.slot }
        val wireIds = wirePosts.map { it.articleId }.toSet()

        assertEquals(15, briefs.size)
        assertTrue(dailyPosts.all { post -> draft.items.single { it.articleId == post.articleId }.score < wirePosts.minOf { p -> draft.items.single { it.articleId == p.articleId }.score } })
        // The wire opens In brief and keeps a share of it; every daily feed gets a line too.
        assertEquals(wire.id, briefs.first().feedId)
        assertTrue(briefs.count { it.articleId in wireIds } < 10)
        assertTrue(dailies.all { feed -> briefs.any { it.feedId == feed.id } })
        // Without the penalty the wire would have every line.
        val unpenalised = EditionBuilder(RankingConfig(sameFeedPagePenalty = 1.0)).build(slowPosts + wirePosts + dailyPosts, slow + wire + dailies, EditionLength.STANDARD, release)
        assertTrue(unpenalised.items.filter { it.kind == ItemKind.BRIEF }.all { it.articleId in wireIds })
    }

    @Test
    fun aSingleFeedStillFillsInBrief() {
        val wire = firehose(1)
        val posts = (0 until 60).map { post(wire, ageHours = it * 0.1) }

        val briefs = build(posts, listOf(wire)).items.filter { it.kind == ItemKind.BRIEF }.sortedBy { it.slot }

        assertEquals(15, briefs.size)
        assertEquals(briefs.map { it.score }.sortedDescending(), briefs.map { it.score })
    }

    @Test
    fun aSingleFeedStillFillsTheTopStoriesPages() {
        val only = weekly(1)
        val posts = (0 until 30).map { post(only, ageHours = it.toDouble()) }

        val draft = build(posts, listOf(only))

        assertEquals(listOf(1, 8, 8, 6), draft.printed.groupBy { it.page }.toSortedMap().values.map { it.size })
        assertEquals(draft.onPage(2).map { it.score }.sortedDescending(), draft.onPage(2).map { it.score })
        assertEquals(30 - 23, draft.index.size)
    }

    @Test
    fun noCandidatesGiveAnEmptyDraft() {
        assertTrue(build(emptyList(), listOf(weekly(1))).items.isEmpty())
    }

    @Test
    fun buildingIsDeterministicWhateverTheInputOrder() {
        val (slow, slowPosts) = slowFeeds(count = 12, postsEach = 6)
        val wire = firehose(100)
        val posts = slowPosts + (0 until 60).map { post(wire, ageHours = it * 1.5) }
        val feeds = slow + wire

        val draft = build(posts, feeds)

        assertEquals(draft, build(posts, feeds))
        assertEquals(draft, build(posts.reversed(), feeds.reversed()))
        assertEquals(draft, build(posts.shuffled(java.util.Random(7)), feeds))
    }

    @Test
    fun aThousandCandidatesBuildWellInsideTheFallbackBudget() {
        val feeds = (1..20L).map { firehose(it) } + (21..50L).map { daily(it) } + (51..80L).map { weekly(it) }
        val posts = (0 until 1_000).map { i -> post(feeds[i % feeds.size], ageHours = (i * 37 % 700) / 10.0) }

        repeat(20) { build(posts, feeds) } // let the JIT settle
        val started = System.nanoTime()
        val draft = build(posts, feeds)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(1_000, draft.items.size)
        // The whole on-open fallback has 300 ms on a low-end phone; laying out is a sliver of it.
        assertTrue("built in $elapsedMs ms", elapsedMs < 150)
    }
}
