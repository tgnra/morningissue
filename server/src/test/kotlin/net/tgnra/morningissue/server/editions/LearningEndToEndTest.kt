package net.tgnra.morningissue.server.editions

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.Edition
import net.tgnra.morningissue.model.EditionPage
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.server.db.UserItemRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Milestone 2 against a real database: several simulated days of publishing and reading, for
 * one user of the server (the app's `LearningEndToEndTest`). The reader's actions are written
 * as merged events write them and relearned at once ([Reader]), as the phone did.
 */
class LearningEndToEndTest {
    /** Half an hour after the 06:30 reset (UTC) on day 0. */
    private val firstMorning: Instant = Instant.parse("2026-11-10T07:00:00Z")
    private val world = TestWorld(firstMorning)
    private val clock = world.clock
    private val db = world.db
    private var user = 0L

    @After
    fun tearDown() = world.close()

    private suspend fun setUp() {
        user = world.addUser()
    }

    private fun publisher(config: RankingConfig = RankingConfig.Default) = EditionPublisher(db, world.settings, clock, config)

    private suspend fun addFeed(
        title: String,
        tier: CadenceTier,
        vararg tags: String,
        slider: SliderStep = SliderStep.AUTO,
        muteUntil: Instant? = null,
    ): Long {
        val median = when (tier) {
            CadenceTier.FIREHOSE -> 0.5
            CadenceTier.DAILY -> 24.0
            CadenceTier.WEEKLY -> 168.0
            CadenceTier.RARE -> 720.0
        }
        return world.addFeed(
            user, title, tier = tier, medianHours = median, muteUntil = muteUntil,
            addedAt = firstMorning.minus(Duration.ofDays(60)), sliderStep = slider, tags = tags.toList(),
        )
    }

    private suspend fun addPost(feedId: Long, seenAt: Instant, publishedAt: Instant = seenAt): Long =
        world.addPost(feedId, firstSeenAt = seenAt, publishedAt = publishedAt)

    /** Moves the clock to 07:00 on [day] and returns the time posts of that morning were fetched. */
    private fun morningOf(day: Int): Instant {
        clock.now = firstMorning.plus(Duration.ofDays(day.toLong()))
        return clock.now.minus(Duration.ofMinutes(30))
    }

    private suspend fun items(editionId: Long): List<UserItemRow> = db.editionDao().getItems(user, editionId)

    private suspend fun kindOf(editionId: Long, articleId: Long): ItemKind? =
        items(editionId).singleOrNull { it.item.articleId == articleId }?.item?.kind

    private suspend fun render(editionId: Long): Edition = world.pages(user, editionId)

    private suspend fun affinityOf(feedId: Long): Double = world.affinityOf(user, feedId)

    @Test
    fun readingOneFeedAndIgnoringAnotherShiftsWhereTheirStoriesRun() = runBlocking {
        setUp()
        // Small mixed pages (a lead, one secondary, two Top stories) so sections fill up.
        val config = RankingConfig(frontPageSecondaries = 1, topStoriesPages = 1, topStoriesPerPage = 2)
        val publisher = publisher(config)
        val articles = Reader(world, user, config)

        val ignored = addFeed("Ignored", CadenceTier.DAILY, "Art")
        val read = addFeed("Read", CadenceTier.DAILY, "Zines")
        val artFeeds = (1..3).map { addFeed("Art $it", CadenceTier.DAILY, "Art") }
        val zineFeeds = (1..3).map { addFeed("Zine $it", CadenceTier.DAILY, "Zines") }
        val wire = addFeed("Wire", CadenceTier.FIREHOSE)
        val essays = addFeed("Essays", CadenceTier.RARE, slider = SliderStep.FAVOURITE)
        // An essay fetched long before the first edition and never read.
        val essay = addPost(essays, seenAt = firstMorning.minus(Duration.ofDays(10)), publishedAt = firstMorning.minus(Duration.ofDays(40)))

        val editions = ArrayList<Long>()
        val ignoredPosts = ArrayList<Long>()
        val readPosts = ArrayList<Long>()
        for (day in 0..4) {
            val seenAt = morningOf(day)
            // The two feeds post at the same moment every day; "Ignored" is stored first, so
            // it wins any tie.
            ignoredPosts += addPost(ignored, seenAt)
            readPosts += addPost(read, seenAt)
            (artFeeds + zineFeeds).forEach { feed -> repeat(2) { addPost(feed, seenAt) } }
            repeat(10) { addPost(wire, seenAt, publishedAt = seenAt.minus(Duration.ofMinutes(3L * it))) }

            editions += publisher.ensureCurrentEdition(user)!!
            // The reader turns through the whole paper ...
            world.viewPages(user, editions.last(), clock.now)

            // ... and opens, finishes and Likes the one feed's story and nothing else.
            clock.now = clock.now.plus(Duration.ofHours(1))
            articles.markOpened(readPosts.last())
            articles.setReadFraction(readPosts.last(), 0.9f)
            articles.setLiked(readPosts.last(), true)
        }
        assertEquals(5, editions.distinct().size)

        // Day 0: nothing is known, the two feeds are equals, and the tie goes to "Ignored".
        val first = render(editions.first())
        assertEquals(ignoredPosts[0], (first.pages[0] as EditionPage.Front).lead!!.articleId)
        assertEquals(listOf(readPosts[0]), (first.pages[0] as EditionPage.Front).secondaries.map { it.articleId })
        assertEquals(listOf("Art", "Zines", "General"), first.pages.filterIsInstance<EditionPage.Section>().map { it.name })

        // Day 4: the feed that gets read leads the paper ...
        val last = render(editions.last())
        val front = last.pages[0] as EditionPage.Front
        assertEquals(readPosts[4], front.lead!!.articleId)
        assertEquals("You read most posts from Read", front.lead!!.reason)
        // ... the ignored one has dropped off the mixed pages into its section ...
        val sections = last.pages.filterIsInstance<EditionPage.Section>()
        val art = sections.single { it.name == "Art" }
        assertTrue(ignoredPosts[4] in art.stories.map { it.articleId })
        assertTrue(last.pages.take(2).none { page -> page.stories.any { it.feedId == ignored } })
        // ... and its tag's page now comes after the tag the reader cares about.
        assertEquals(listOf("Zines", "Art", "General"), sections.map { it.name })

        // The stored numbers are what the Sources screen shows.
        assertTrue(affinityOf(read) > 0.85)
        assertTrue(affinityOf(ignored) < 0.2)
        val tags = world.tagAffinities(user)
        assertTrue(tags.getValue("Zines") > tags.getValue("Art"))
        assertTrue(tags.getValue("Art") < 0.3)
        // Feeds sharing the read feed's tag borrow from it; they outrank the other tag's feeds.
        assertTrue(zineFeeds.minOf { affinityOf(it) } > artFeeds.maxOf { affinityOf(it) })

        // The whole paper, in page order.
        assertEquals(
            listOf(
                EditionPage.Front::class,
                EditionPage.TopStories::class,
                EditionPage.Section::class,
                EditionPage.Section::class,
                EditionPage.Section::class,
                EditionPage.InBrief::class,
                EditionPage.FromTheArchive::class,
                EditionPage.Back::class,
            ),
            last.pages.map { it::class },
        )
        assertEquals((1..8).toList(), last.pages.map { it.number })
        assertTrue(sections.all { it.stories.size in 2..6 })
        assertTrue(sections.single { it.name == "General" }.stories.all { it.feedId == wire })
        val everyStory = last.pages.flatMap { it.stories }.map { it.articleId }
        assertEquals(everyStory.size, everyStory.distinct().size)
        assertTrue(last.pages.flatMap { it.stories }.all { !it.reason.isNullOrBlank() })

        // From the archive: the old essay ran in the first edition, rested while it was among
        // the last three, and came back in the fifth.
        val archive = last.pages.filterIsInstance<EditionPage.FromTheArchive>().single()
        assertEquals(listOf(essay), archive.stories.map { it.articleId })
        assertEquals("From the archive: you have not read this one from Essays yet", archive.stories.single().reason)
        assertEquals(
            listOf(ItemKind.ARCHIVE, ItemKind.INDEX, ItemKind.INDEX, ItemKind.INDEX, ItemKind.ARCHIVE),
            editions.map { kindOf(it, essay) },
        )

        // Yesterday's news is not printed again, and what ran unopened was counted once.
        assertNull(kindOf(editions[1], ignoredPosts[0]))
        assertEquals(1, db.engagementDao().get(user, ignoredPosts[0])?.editionsRunUnopened)
        assertEquals(0, db.engagementDao().get(user, readPosts[0])?.editionsRunUnopened)
    }

    @Test
    fun aHeldOverPostLeadsTwiceThenWaitsForTheArchiveUntilItIsRead() = runBlocking {
        setUp()
        val publisher = publisher()
        val articles = Reader(world, user)
        val essays = addFeed("Essays", CadenceTier.RARE, slider = SliderStep.FAVOURITE)
        val dailies = (1..6).map { addFeed("Daily $it", CadenceTier.DAILY) }
        var essay = 0L
        val editions = ArrayList<Long>()

        suspend fun publishDay(day: Int): Long {
            val seenAt = morningOf(day)
            if (day == 0) essay = addPost(essays, seenAt)
            dailies.forEach { addPost(it, seenAt) }
            // The reader turns through every page and opens nothing.
            return publisher.ensureCurrentEdition(user)!!.also { editions += it; world.viewPages(user, it, seenAt) }
        }

        // New and loved: it leads. Unread the next day, it is held over and leads once more.
        assertEquals(ItemKind.LEAD, kindOf(publishDay(0), essay))
        val second = publishDay(1)
        assertEquals(ItemKind.LEAD, kindOf(second, essay))
        assertTrue(items(second).single { it.item.articleId == essay }.item.reason!!.startsWith("Held over"))
        assertEquals(2, db.engagementDao().get(user, essay)?.leadCount)
        // Nothing else from the first edition was carried into the second.
        val firstIds = items(editions[0]).map { it.item.articleId }.toSet()
        assertEquals(setOf(essay), items(second).map { it.item.articleId }.filter { it in firstIds }.toSet())

        // It has led twice: off the pages, and not yet rested enough for the archive.
        for (day in 2..4) assertEquals("day $day", ItemKind.INDEX, kindOf(publishDay(day), essay))
        assertEquals(2, db.engagementDao().get(user, essay)?.editionsRunUnopened)
        assertEquals(2, db.engagementDao().get(user, essay)?.leadCount)

        // Three editions later it is the From the archive story.
        val sixth = publishDay(5)
        assertEquals(ItemKind.ARCHIVE, kindOf(sixth, essay))
        val pages = render(sixth).pages
        assertTrue(pages[pages.size - 2] is EditionPage.FromTheArchive)
        assertEquals(listOf(essay), pages[pages.size - 2].stories.map { it.articleId })

        // Once read it is no longer held over.
        clock.now = clock.now.plus(Duration.ofHours(1))
        articles.markOpened(essay)
        articles.markRead(essay)
        assertNull(kindOf(publishDay(6), essay))
        assertEquals(2, db.engagementDao().get(user, essay)?.editionsRunUnopened)
    }

    @Test
    fun aFeedTheReaderCameToLoveHoldsItsUnreadPostsOver() = runBlocking {
        setUp()
        val publisher = publisher()
        val articles = Reader(world, user)
        val blog = addFeed("Blog", CadenceTier.WEEKLY)
        val meh = addFeed("Meh", CadenceTier.WEEKLY)
        val daily = addFeed("Daily", CadenceTier.DAILY)

        suspend fun like(articleId: Long) {
            articles.markOpened(articleId)
            articles.setReadFraction(articleId, 1f)
            articles.setLiked(articleId, true)
        }

        var seenAt = morningOf(0)
        val liked = mutableListOf(addPost(blog, seenAt), addPost(blog, seenAt))
        addPost(daily, seenAt)
        world.viewPages(user, publisher.ensureCurrentEdition(user)!!, seenAt)
        liked.forEach { like(it) }

        seenAt = morningOf(1)
        liked += addPost(blog, seenAt)
        val unreadFromBlog = addPost(blog, seenAt)
        val unreadFromMeh = addPost(meh, seenAt)
        addPost(daily, seenAt)
        val second = publisher.ensureCurrentEdition(user)!!
        world.viewPages(user, second, seenAt)
        like(liked.last())
        assertTrue(kindOf(second, unreadFromBlog) != ItemKind.INDEX)
        assertTrue(kindOf(second, unreadFromMeh) != ItemKind.INDEX)

        seenAt = morningOf(2)
        addPost(daily, seenAt)
        val third = publisher.ensureCurrentEdition(user)!!

        // Three Liked posts and one left unread: loved, by what was learned alone.
        assertTrue(affinityOf(blog) >= 0.7)
        assertEquals(SliderStep.AUTO, db.editionArticleDao().feed(user, blog)!!.sliderStep)
        val heldOver = items(third).single { it.item.articleId == unreadFromBlog }.item
        assertTrue(heldOver.kind in setOf(ItemKind.LEAD, ItemKind.FRONT, ItemKind.TOP))
        assertEquals("Held over: still unread from Blog, a source you read a lot", heldOver.reason)
        // It ran unopened once, so it scores half of what it otherwise would.
        assertEquals(1, db.engagementDao().get(user, unreadFromBlog)?.editionsRunUnopened)
        // The other slow feed is not loved: its unread post is not printed, or even listed, again.
        assertNull(kindOf(third, unreadFromMeh))
        // Posts the reader finished are never carried over.
        assertTrue(liked.none { id -> items(third).any { it.item.articleId == id } })
    }

    @Test
    fun heldOverPostsAreCappedPerFeedAndAloneDoNotMakeAnEdition() = runBlocking {
        setUp()
        val publisher = publisher()
        val essays = addFeed("Essays", CadenceTier.RARE, slider = SliderStep.FAVOURITE)
        val daily = addFeed("Daily", CadenceTier.DAILY)
        val longAgo = firstMorning.minus(Duration.ofDays(10))
        // Seven unread essays from before the first edition's window, oldest first.
        val old = (0 until 7).map { addPost(essays, seenAt = longAgo, publishedAt = longAgo.minus(Duration.ofDays(7L - it))) }

        // Nothing new: no edition, however many posts are held over.
        assertNull(publisher.ensureCurrentEdition(user))
        assertEquals(0, db.editionDao().count(user))

        addPost(daily, morningOf(0))
        val edition = publisher.ensureCurrentEdition(user)!!

        val fromEssays = items(edition).filter { it.row.article.feedId == essays }.map { it.item.articleId }.toSet()
        assertEquals(old.takeLast(5).toSet(), fromEssays)
    }

    @Test
    fun aMutedLovedFeedKeepsItsPostsForWhenTheMuteEnds() = runBlocking {
        setUp()
        val publisher = publisher()
        // Muted until noon on day 0: after that morning's release, before the next one.
        val essays = addFeed("Essays", CadenceTier.RARE, slider = SliderStep.FAVOURITE, muteUntil = firstMorning.plus(Duration.ofHours(5)))
        val daily = addFeed("Daily", CadenceTier.DAILY)
        val essay = addPost(essays, seenAt = firstMorning.minus(Duration.ofHours(2)))

        addPost(daily, morningOf(0))
        val muted = publisher.ensureCurrentEdition(user)!!
        assertNull(kindOf(muted, essay))
        // A muted feed's posts are not exposures: nothing was held against it.
        assertNull(db.engagementDao().get(user, essay))

        addPost(daily, morningOf(1))
        val after = publisher.ensureCurrentEdition(user)!!
        assertEquals(ItemKind.LEAD, kindOf(after, essay))
        assertFalse(db.editionArticleDao().feed(user, essays)!!.muteUntil!!.isAfter(db.editionDao().getById(user, after)!!.releaseAt))
    }
}
