package net.tgnra.morningissue.server.editions

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EditionPage
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.ItemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's `RoomEditionPublisherTest` over the server's [EditionPublisher], per user. The
 * rules are unchanged; "first seen" is the user's effective first-seen time (2.2), and the
 * zone and schedule come from the user's settings.
 */
class RoomEditionPublisherTest {
    /** Tuesday 10 November 2026, half an hour after the 06:30 reset (UTC). */
    private val morning: Instant = Instant.parse("2026-11-10T07:00:00Z")
    private val world = TestWorld(morning)
    private val clock = world.clock
    private val db = world.db
    private val publisher = newPublisher()
    private val user = runBlocking { world.addUser() }

    @After
    fun tearDown() = world.close()

    private fun newPublisher() = EditionPublisher(db, world.settings, clock)

    private suspend fun addFeed(title: String, tier: CadenceTier = CadenceTier.WEEKLY, medianHours: Double? = 168.0, muteUntil: Instant? = null, userId: Long = user) =
        world.addFeed(userId, title, tier, medianHours, muteUntil, addedAt = morning.minus(Duration.ofDays(30)))

    private suspend fun addPost(feedId: Long, firstSeenAt: Instant, publishedAt: Instant = firstSeenAt) =
        world.addPost(feedId, firstSeenAt = firstSeenAt, publishedAt = publishedAt)

    /** [count] weekly feeds with one post each, first seen at [seenAt]. Returns the article IDs. */
    private suspend fun addSlowPosts(count: Int, seenAt: Instant, prefix: String = "Blog", userId: Long = user): List<Long> =
        (0 until count).map { i ->
            val feedId = addFeed("$prefix $i $userId", userId = userId)
            addPost(feedId, firstSeenAt = seenAt, publishedAt = seenAt.minus(Duration.ofHours(1L + i)))
        }

    private suspend fun articleIdsOf(editionId: Long, userId: Long = user) = world.articleIdsOf(userId, editionId)

    private suspend fun editionsHolding(articleId: Long): Int =
        db.editionDao().getRecent(user, 100).count { articleId in articleIdsOf(it.id) }

    private suspend fun ensure() = publisher.ensureCurrentEdition(user)
    private suspend fun buildNext() = publisher.buildNextEdition(user)
    private suspend fun count() = db.editionDao().count(user)
    private suspend fun edition(id: Long) = db.editionDao().getById(user, id)!!
    private suspend fun items(id: Long) = db.editionDao().getItems(user, id)
    private suspend fun engagement(articleId: Long) = db.engagementDao().get(user, articleId)

    private fun minutesBefore(instant: Instant, minutes: Long) = instant.minus(Duration.ofMinutes(minutes))

    @Test
    fun firstEditionIsBuiltOnOpenFromStoredPosts() = runBlocking {
        val posts = addSlowPosts(8, seenAt = minutesBefore(morning, 20))

        val id = ensure()

        val edition = edition(id!!)
        assertEquals(1, edition.number)
        assertEquals(LocalDate.of(2026, 11, 10), edition.date)
        assertEquals(EditionKind.MORNING, edition.kind)
        assertEquals(EditionLength.STANDARD, edition.length)
        assertEquals(Instant.parse("2026-11-10T06:30:00Z"), edition.releaseAt)
        assertEquals(morning, edition.cutoffAt)
        assertEquals(morning, edition.builtAt)
        assertEquals(posts.toSet(), articleIdsOf(id))
        assertEquals(id, db.editionDao().getLatestReleased(user, morning)?.id)
        assertTrue("a new edition is a change for the clients", edition.changeSeq > 0)

        // Asking again shows the same frozen edition.
        val items = items(id)
        clock.now = morning.plus(Duration.ofHours(5))
        addSlowPosts(3, seenAt = clock.now, prefix = "Later")
        assertEquals(id, ensure())
        assertEquals(1, count())
        assertEquals(items.map { it.item }, items(id).map { it.item })
    }

    @Test
    fun firstEditionOnlyReachesBackAFewDays() = runBlocking {
        val feed = addFeed("Blog")
        val recent = addPost(feed, firstSeenAt = morning.minus(Duration.ofDays(2)))
        val old = addPost(feed, firstSeenAt = morning.minus(Duration.ofDays(5)))

        val id = ensure()!!

        assertEquals(setOf(recent), articleIdsOf(id))
        assertEquals(0, editionsHolding(old))
    }

    @Test
    fun secondEditionOnlyCoversPostsFirstSeenAfterThePreviousCutoff() = runBlocking {
        val firstPosts = addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        val first = ensure()!!

        // Seen during the day, after the first edition's cutoff at 07:00.
        val newPosts = addSlowPosts(4, seenAt = morning.plus(Duration.ofHours(6)), prefix = "Day")
        // Published two days ago but only fetched this evening: it belongs to the next edition.
        val lateFeed = addFeed("Slow server")
        val fetchedLate = addPost(lateFeed, firstSeenAt = morning.plus(Duration.ofHours(12)), publishedAt = morning.minus(Duration.ofDays(2)))

        clock.now = Instant.parse("2026-11-11T08:00:00Z")
        val second = ensure()!!

        assertNotEquals(first, second)
        val edition = edition(second)
        assertEquals(2, edition.number)
        assertEquals(LocalDate.of(2026, 11, 11), edition.date)
        assertEquals(Instant.parse("2026-11-11T06:30:00Z"), edition.releaseAt)
        assertEquals(clock.now, edition.cutoffAt)
        assertEquals((newPosts + fetchedLate).toSet(), articleIdsOf(second))
        assertEquals(firstPosts.toSet(), articleIdsOf(first))
        assertEquals(1, editionsHolding(fetchedLate))
    }

    @Test
    fun aPostSeenAfterTheEarlyBuildWaitsForTheNextEdition() = runBlocking {
        addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        ensure()!!

        // 05:00 the next day: the early build freezes tomorrow's paper.
        clock.now = Instant.parse("2026-11-11T05:00:00Z")
        val inTime = addSlowPosts(3, seenAt = minutesBefore(clock.now, 10), prefix = "In time")
        val early = buildNext()!!
        // Fetched at 05:40, after that cutoff and before the 06:30 release.
        val tooLate = addPost(addFeed("Straggler"), firstSeenAt = Instant.parse("2026-11-11T05:40:00Z"))

        clock.now = Instant.parse("2026-11-11T07:00:00Z")
        assertEquals(early, ensure())
        assertEquals(inTime.toSet(), articleIdsOf(early))

        clock.now = Instant.parse("2026-11-12T07:00:00Z")
        val third = ensure()!!
        assertEquals(setOf(tooLate), articleIdsOf(third))
        assertEquals(3, edition(third).number)
    }

    @Test
    fun earlyBuildIsStoredUnreleasedForTheNextReset() = runBlocking {
        addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        val first = ensure()!!

        clock.now = Instant.parse("2026-11-11T05:00:00Z")
        addSlowPosts(5, seenAt = minutesBefore(clock.now, 10), prefix = "Overnight")
        val next = buildNext()!!

        val edition = edition(next)
        assertEquals(LocalDate.of(2026, 11, 11), edition.date)
        assertEquals(Instant.parse("2026-11-11T06:30:00Z"), edition.releaseAt)
        assertEquals(clock.now, edition.cutoffAt)
        assertEquals(2, edition.number)
        // Building again returns the stored edition; before the reset the old one is current.
        assertEquals(next, buildNext())
        assertEquals(first, ensure())
        assertEquals(Instant.parse("2026-11-11T06:30:00Z"), publisher.nextRelease(user).at)

        clock.now = Instant.parse("2026-11-11T06:30:00Z")
        assertEquals(next, ensure())
        assertEquals(2, count())
        assertEquals(Instant.parse("2026-11-12T06:30:00Z"), publisher.nextRelease(user).at)
    }

    @Test
    fun aLongGapPutsEverythingSinceTheLastCutoffInOneEdition() = runBlocking {
        addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        val first = ensure()!!

        // The server's user went quiet for ten days; posts arrived before and after.
        val beforeDark = addSlowPosts(3, seenAt = morning.plus(Duration.ofHours(3)), prefix = "Before")
        val afterWake = addSlowPosts(7, seenAt = Instant.parse("2026-11-20T09:00:00Z"), prefix = "After")
        clock.now = Instant.parse("2026-11-20T09:05:00Z")

        val second = ensure()!!

        val edition = edition(second)
        assertEquals(2, edition.number)
        assertEquals(LocalDate.of(2026, 11, 20), edition.date)
        assertEquals(Instant.parse("2026-11-20T06:30:00Z"), edition.releaseAt)
        assertEquals((beforeDark + afterWake).toSet(), articleIdsOf(second))
        assertEquals(2, count())
        assertEquals(6, articleIdsOf(first).size)
    }

    @Test
    fun concurrentCallsCreateOneEdition() = runBlocking {
        addSlowPosts(10, seenAt = minutesBefore(morning, 20))
        val other = newPublisher()

        val ids = withContext(Dispatchers.Default) {
            (0 until 24).map { i -> async { (if (i % 2 == 0) publisher else other).ensureCurrentEdition(user) } }.awaitAll()
        }

        assertEquals(1, ids.toSet().size)
        assertNotNull(ids.first())
        assertEquals(1, count())
        assertEquals(1, db.editionDao().maxNumber(user))
        assertEquals(10, items(ids.first()!!).size)
    }

    @Test
    fun concurrentEarlyBuildAndOpenDoNotDuplicate() = runBlocking {
        addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        ensure()!!
        clock.now = Instant.parse("2026-11-11T05:00:00Z")
        addSlowPosts(5, seenAt = minutesBefore(clock.now, 10), prefix = "Overnight")

        val ids = withContext(Dispatchers.Default) {
            (0 until 12).map { async { buildNext() } }.awaitAll()
        }

        assertEquals(1, ids.toSet().size)
        assertEquals(2, count())
    }

    /** A feed found through discovery, with one post the reader saved [savedAt] and never read. */
    private suspend fun addSavedDiscoveredPost(title: String, seenAt: Instant, savedAt: Instant = seenAt): Long {
        val feedId = world.addFeed(user, title, addedAt = seenAt, status = FeedStatus.DISCOVERED)
        val post = addPost(feedId, firstSeenAt = seenAt)
        world.save(user, post, savedAt)
        return post
    }

    @Test
    fun aLoneSavedDiscoveredPostStillGivesNothingNew() = runBlocking {
        addFeed("Subscribed but quiet")
        addSavedDiscoveredPost("Found", seenAt = minutesBefore(morning, 10))

        // First seen in the window, saved and unread, yet not from a subscription: no paper.
        assertNull(ensure())
        assertNull(buildNext())
        assertEquals(0, count())

        // Nor once there is an edition to follow.
        addSlowPosts(4, seenAt = minutesBefore(morning, 5))
        val first = ensure()!!
        addSavedDiscoveredPost("Found later", seenAt = Instant.parse("2026-11-11T05:00:00Z"))
        clock.now = Instant.parse("2026-11-11T08:00:00Z")
        assertEquals(first, ensure())
        assertEquals(1, count())
    }

    @Test
    fun aSavedDiscoveredPostRunsFromYourReadingListAtMostOnceAWeek() = runBlocking {
        val older = addSavedDiscoveredPost("Found", seenAt = morning.minus(Duration.ofDays(3)), savedAt = morning.minus(Duration.ofDays(3)))
        val newer = addSavedDiscoveredPost("Found too", seenAt = morning.minus(Duration.ofDays(1)), savedAt = morning.minus(Duration.ofDays(1)))
        val posts = addSlowPosts(8, seenAt = minutesBefore(morning, 20))

        val id = ensure()!!
        val kinds = items(id).associate { it.item.articleId to it.item.kind }
        // The oldest saved one, on a page of its own; the other is not a candidate anywhere.
        assertEquals(ItemKind.READING_LIST, kinds[older])
        assertFalse(newer in kinds)
        assertTrue(posts.all { kinds[it] != ItemKind.READING_LIST })
        val edition = world.pages(user, id)
        val page = edition.pages.filterIsInstance<EditionPage.FromYourReadingList>().single()
        assertEquals(listOf(older), page.stories.map { it.articleId })
        assertFalse(page.stories.single().feedSubscribed)
        assertTrue(page.stories.single().reason!!.contains("don't subscribe"))
        assertEquals(EditionPage.Back::class, edition.pages.last()::class)

        // The next morning has news but no reading list: one a week at most.
        addSlowPosts(3, seenAt = Instant.parse("2026-11-11T05:00:00Z"), prefix = "Wed")
        clock.now = Instant.parse("2026-11-11T07:00:00Z")
        val wednesday = ensure()!!
        assertTrue(items(wednesday).none { it.item.kind == ItemKind.READING_LIST })

        // A week on, the next saved post (the first has had its turn, read or not).
        addSlowPosts(3, seenAt = Instant.parse("2026-11-17T05:00:00Z"), prefix = "Next Tue")
        clock.now = Instant.parse("2026-11-17T07:00:00Z")
        val week = ensure()!!
        val reading = items(week).filter { it.item.kind == ItemKind.READING_LIST }.map { it.item.articleId }
        assertEquals(listOf(newer), reading)
    }

    @Test
    fun aReadOrSubscribedPostIsNotOnTheReadingList() = runBlocking {
        val read = addSavedDiscoveredPost("Read", seenAt = morning.minus(Duration.ofDays(3)))
        world.open(user, read, morning.minus(Duration.ofDays(2)))
        world.read(user, read, morning.minus(Duration.ofDays(2)))
        val subscribedLater = addSavedDiscoveredPost("Subscribed", seenAt = morning.minus(Duration.ofDays(2)))
        db.importDao().promoteDiscovered(user, db.importDao().article(subscribedLater)!!.feedId, morning.minus(Duration.ofDays(1)))
        addSlowPosts(6, seenAt = minutesBefore(morning, 20))

        val id = ensure()!!
        assertTrue(items(id).none { it.item.kind == ItemKind.READING_LIST })
    }

    @Test
    fun nothingToBuildReturnsNull() = runBlocking {
        assertNull(ensure())
        assertNull(buildNext())

        addFeed("Empty")
        assertNull(ensure())
        assertNull(buildNext())
        assertEquals(0, count())
    }

    @Test
    fun aDayWithoutNewPostsKeepsThePreviousEditionCurrent() = runBlocking {
        addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        val first = ensure()!!

        clock.now = Instant.parse("2026-11-11T05:00:00Z")
        assertNull(buildNext())
        clock.now = Instant.parse("2026-11-11T08:00:00Z")
        assertEquals(first, ensure())
        assertEquals(1, count())

        // The posts that do arrive later that day make the paper when it is next asked for.
        val late = addSlowPosts(2, seenAt = Instant.parse("2026-11-11T09:00:00Z"), prefix = "Late")
        clock.now = Instant.parse("2026-11-11T10:00:00Z")
        val second = ensure()!!
        assertEquals(late.toSet(), articleIdsOf(second))
        assertEquals(LocalDate.of(2026, 11, 11), edition(second).date)
    }

    @Test
    fun mutedFeedsAndReadPostsDoNotMakeAnEdition() = runBlocking {
        val muted = addFeed("Muted", muteUntil = morning.plus(Duration.ofDays(3)))
        addPost(muted, firstSeenAt = minutesBefore(morning, 20))
        val feed = addFeed("Read already")
        val read = addPost(feed, firstSeenAt = minutesBefore(morning, 20))
        world.open(user, read, minutesBefore(morning, 5))
        world.read(user, read, minutesBefore(morning, 4))

        assertNull(ensure())
        assertEquals(0, count())

        val fresh = addPost(feed, firstSeenAt = minutesBefore(morning, 1))
        val id = ensure()!!
        val items = items(id).associate { it.item.articleId to it.item.kind }
        assertEquals(mapOf(fresh to ItemKind.LEAD, read to ItemKind.INDEX), items)
    }

    @Test
    fun editionRendersThroughEditionPagesInTheExpectedPageSequence() = runBlocking {
        val seenAt = minutesBefore(morning, 20)
        addSlowPosts(30, seenAt = seenAt)
        val wire = addFeed("Wire", tier = CadenceTier.FIREHOSE, medianHours = 0.5)
        repeat(40) { i -> addPost(wire, firstSeenAt = seenAt, publishedAt = seenAt.minus(Duration.ofMinutes(20L * i))) }

        val id = ensure()!!
        val edition = world.pages(user, id)

        assertEquals(
            listOf(
                EditionPage.Front::class,
                EditionPage.TopStories::class,
                EditionPage.TopStories::class,
                EditionPage.Section::class,
                EditionPage.InBrief::class,
                EditionPage.Back::class,
            ),
            edition.pages.map { it::class },
        )
        assertEquals((1..6).toList(), edition.pages.map { it.number })

        val front = edition.pages[0] as EditionPage.Front
        assertNotNull(front.lead)
        assertEquals(ItemKind.LEAD, front.lead!!.kind)
        assertEquals(4, front.secondaries.size)
        assertEquals(5, front.stories.map { it.feedId }.distinct().size)
        assertEquals(edition.pages.flatMap { it.stories }.maxOf { it.score }, front.lead!!.score, 0.0)
        assertEquals(8, edition.pages[1].stories.size)
        assertEquals(8, edition.pages[2].stories.size)
        // The feeds carry no tags, so the one section is General.
        val general = edition.pages[3] as EditionPage.Section
        assertEquals("General", general.name)
        assertEquals(6, general.stories.size)
        val briefs = edition.pages[4] as EditionPage.InBrief
        assertEquals(15, briefs.stories.size)
        assertTrue(briefs.stories.all { it.feedId == wire })

        // (The back page's next-release line is the client's to work out; not checked here.)
        val back = edition.pages[5] as EditionPage.Back
        assertEquals(70 - 5 - 16 - 6 - 15, back.stories.size)
        assertEquals(back.alsoInYourFeeds.map { it.feedId }.distinct(), back.alsoInYourFeeds.map { it.feedId })
        assertEquals(
            back.alsoInYourFeeds.map { it.feedTitle }.sortedWith(String.CASE_INSENSITIVE_ORDER),
            back.alsoInYourFeeds.map { it.feedTitle },
        )
        // Every post appears exactly once, and every story can say why it is there.
        val all = edition.pages.flatMap { it.stories }
        assertEquals(70, all.size)
        assertEquals(70, all.map { it.articleId }.distinct().size)
        assertTrue(all.all { !it.reason.isNullOrBlank() })
        assertEquals(1, edition.number)
        assertFalse(edition.isRead)
    }

    @Test
    fun editionLengthSettingIsUsedAndFrozen() = runBlocking {
        world.settings.update(user, customize = true) { it.copy(editionLength = EditionLength.SHORT) }
        addSlowPosts(30, seenAt = minutesBefore(morning, 20))

        val id = ensure()!!
        world.settings.update(user, customize = true) { it.copy(editionLength = EditionLength.LONG) }

        assertEquals(EditionLength.SHORT, edition(id).length)
        val perPage = items(id).filter { it.item.kind != ItemKind.INDEX }.groupBy { it.item.page }
        assertEquals(listOf(5, 4, 4, 3), perPage.toSortedMap().values.map { it.size })
    }

    @Test
    fun resetTimeSettingDecidesReleaseAndDate() = runBlocking {
        world.settings.update(user, customize = true) { it.copy(resetTime = StoredSettings.time(LocalTime.of(9, 0))) }
        addSlowPosts(5, seenAt = minutesBefore(morning, 20))

        // 07:00 is before today's 09:00 reset: the current edition is yesterday's.
        val id = ensure()!!

        val edition = edition(id)
        assertEquals(LocalDate.of(2026, 11, 9), edition.date)
        assertEquals(Instant.parse("2026-11-09T09:00:00Z"), edition.releaseAt)
        assertEquals(Instant.parse("2026-11-10T09:00:00Z"), publisher.nextRelease(user).at)
    }

    @Test
    fun supersedingAnEditionCountsItsUnopenedRunsAndTheLead() = runBlocking {
        val seenAt = minutesBefore(morning, 20)
        addSlowPosts(30, seenAt = seenAt)
        val first = ensure()!!
        val items = items(first).map { it.item }
        val lead = items.single { it.kind == ItemKind.LEAD }
        val printed = items.filter { it.kind != ItemKind.INDEX }
        val opened = printed.first { it.kind == ItemKind.TOP }
        val indexed = items.first { it.kind == ItemKind.INDEX }

        assertEquals(1, engagement(lead.articleId)?.leadCount)
        // Nothing is counted while the edition is still the current one.
        assertEquals(0, engagement(lead.articleId)?.editionsRunUnopened)
        world.open(user, opened.articleId, morning.plus(Duration.ofHours(1)))
        // The reader turns through every page but the last, which they never reach.
        val unseenPage = printed.maxOf { it.page }
        assertTrue(unseenPage > lead.page)
        world.viewPages(user, first, morning.plus(Duration.ofHours(1)), skipping = setOf(unseenPage))

        clock.now = Instant.parse("2026-11-11T07:00:00Z")
        addSlowPosts(3, seenAt = minutesBefore(clock.now, 30), prefix = "Next")
        val second = ensure()!!

        assertEquals(1, engagement(lead.articleId)?.editionsRunUnopened)
        assertEquals(0, engagement(opened.articleId)?.editionsRunUnopened)
        assertNull(engagement(indexed.articleId))
        printed.filter { it.articleId != opened.articleId }.forEach {
            // A story on the page the reader never saw has not been passed over.
            val expected = if (it.page == unseenPage) 0 else 1
            assertEquals(expected, engagement(it.articleId)?.editionsRunUnopened ?: 0)
        }
        // The new edition's own stories have not run unopened yet.
        val secondLead = items(second).single { it.item.kind == ItemKind.LEAD }
        assertEquals(0, engagement(secondLead.item.articleId)?.editionsRunUnopened)
        assertEquals(1, engagement(secondLead.item.articleId)?.leadCount)

        // A day with nothing to build must not count the same edition again.
        clock.now = Instant.parse("2026-11-12T07:00:00Z")
        assertEquals(second, ensure())
        assertEquals(1, engagement(lead.articleId)?.editionsRunUnopened)
        assertEquals(0, engagement(secondLead.item.articleId)?.editionsRunUnopened)
    }

    @Test
    fun travellingWestKeepsTheNewestEditionCurrent() = runBlocking {
        addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        val first = ensure()!!
        addSlowPosts(2, seenAt = morning.plus(Duration.ofMinutes(10)), prefix = "New")

        // 07:30 UTC is 21:30 the evening before in Honolulu, where the latest reset is the 9th's.
        clock.now = morning.plus(Duration.ofMinutes(30))
        world.settings.update(user, customize = false) { it.copy(timeZone = "Pacific/Honolulu") }

        assertEquals(first, ensure())
        assertEquals(1, count())
        // The next local reset is the 10th at 06:30 HST; that date's edition already exists.
        assertEquals(Instant.parse("2026-11-10T16:30:00Z"), publisher.nextRelease(user).at)
        assertEquals(first, buildNext())
    }

    @Test
    fun travellingEastDoesNotShowAnEditionBeforeItsOwnReleaseTime() = runBlocking {
        addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        val first = ensure()!!
        clock.now = Instant.parse("2026-11-11T05:00:00Z")
        addSlowPosts(4, seenAt = minutesBefore(clock.now, 10), prefix = "Overnight")
        val early = buildNext()!!

        // In Tokyo it is already 14:10 on the 11th, past the local reset, but the stored
        // edition for the 11th is released at 06:30 UTC.
        clock.now = Instant.parse("2026-11-11T05:10:00Z")
        world.settings.update(user, customize = false) { it.copy(timeZone = "Asia/Tokyo") }
        assertEquals(first, ensure())

        clock.now = Instant.parse("2026-11-11T06:30:00Z")
        assertEquals(early, ensure())
        assertEquals(2, count())
    }

    // --- Several users -------------------------------------------------------------------------

    @Test
    fun usersSharingAFeedEachGetTheirOwnEditionAndEngagement() = runBlocking {
        val other = world.addUser()
        val shared = world.addGlobalFeed("Shared")
        world.subscribe(user, shared)
        world.subscribe(other, shared)
        val post = addPost(shared, firstSeenAt = minutesBefore(morning, 20))
        val mine = addSlowPosts(3, seenAt = minutesBefore(morning, 20))
        // The user read the shared post already; the other did not.
        world.open(user, post, minutesBefore(morning, 5))
        world.read(user, post, minutesBefore(morning, 4))

        val a = ensure()!!
        val b = publisher.ensureCurrentEdition(other)!!

        assertNotEquals(a, b)
        assertEquals(1, edition(a).number)
        assertEquals(1, db.editionDao().getById(other, b)!!.number)
        assertEquals(setOf(post), articleIdsOf(b, other))
        assertEquals(ItemKind.LEAD, db.editionDao().getItems(other, b).single().item.kind)
        assertEquals(ItemKind.INDEX, items(a).single { it.item.articleId == post }.item.kind)
        assertTrue(articleIdsOf(a).containsAll(mine))
        // Neither sees the other's edition.
        assertNull(db.editionDao().getById(other, a))
        assertNull(db.editionDao().getById(user, b))
        assertNull(db.engagementDao().get(other, post)?.readAt)
    }

    @Test
    fun aPostFirstSeenBeforeTheSubscriptionCountsAsSeenWhenSubscribed() = runBlocking {
        val other = world.addUser()
        val shared = world.addGlobalFeed("Shared")
        // Fetched five days ago for the user, beyond a first edition's reach.
        world.subscribe(user, shared, addedAt = morning.minus(Duration.ofDays(30)))
        val post = addPost(shared, firstSeenAt = morning.minus(Duration.ofDays(5)))
        addSlowPosts(2, seenAt = minutesBefore(morning, 20))
        // The other user subscribed twenty minutes ago: for them the post is new.
        world.subscribe(other, shared, addedAt = minutesBefore(morning, 20))

        val a = ensure()!!
        val b = publisher.ensureCurrentEdition(other)!!

        assertFalse(post in articleIdsOf(a))
        assertEquals(setOf(post), articleIdsOf(b, other))
    }
}
