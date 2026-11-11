package net.tgnra.morningissue.edition

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.repository.EditionPages
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomEditionPublisherTest {
    /** Tuesday 10 November 2026, half an hour after the 06:30 reset (UTC). */
    private val morning: Instant = Instant.parse("2026-11-10T07:00:00Z")
    private val clock = MutableClock(morning)
    private val settings = FakeSettings()
    private var zone: ZoneId = ZoneOffset.UTC
    private lateinit var db: MorningIssueDatabase
    private lateinit var publisher: RoomEditionPublisher
    private var guid = 0

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        publisher = newPublisher()
    }

    @After
    fun tearDown() = db.close()

    private fun newPublisher() = RoomEditionPublisher(db, settings, clock, zone = { zone })

    private suspend fun addFeed(
        title: String,
        tier: CadenceTier = CadenceTier.WEEKLY,
        medianHours: Double? = 168.0,
        muteUntil: Instant? = null,
    ): Long = db.feedDao().insert(
        FeedEntity(
            url = "https://example.com/$title.xml",
            title = title,
            tier = tier,
            medianIntervalHrs = medianHours,
            muteUntil = muteUntil,
            addedAt = morning.minus(Duration.ofDays(30)),
        ),
    )

    private suspend fun addPost(
        feedId: Long,
        firstSeenAt: Instant,
        publishedAt: Instant = firstSeenAt,
        imageUrl: String? = null,
    ): Long {
        val id = "post-${guid++}"
        return db.articleDao().insertNew(
            ArticleEntity(
                feedId = feedId,
                guid = id,
                url = "https://example.com/$id",
                title = "Title $id",
                imageUrl = imageUrl,
                publishedAt = publishedAt,
                firstSeenAt = firstSeenAt,
            ),
        )!!
    }

    /** [count] weekly feeds with one post each, first seen at [seenAt]. Returns the article IDs. */
    private suspend fun addSlowPosts(count: Int, seenAt: Instant, prefix: String = "Blog"): List<Long> =
        (0 until count).map { i ->
            val feedId = addFeed("$prefix $i")
            addPost(feedId, firstSeenAt = seenAt, publishedAt = seenAt.minus(Duration.ofHours(1L + i)))
        }

    private suspend fun articleIdsOf(editionId: Long): Set<Long> =
        db.editionDao().getItems(editionId).map { it.item.articleId }.toSet()

    private fun minutesBefore(instant: Instant, minutes: Long) = instant.minus(Duration.ofMinutes(minutes))

    @Test
    fun firstEditionIsBuiltOnOpenFromStoredPosts() = runTest {
        val posts = addSlowPosts(8, seenAt = minutesBefore(morning, 20))

        val id = publisher.ensureCurrentEdition()

        val edition = db.editionDao().getById(id!!)!!
        assertEquals(1, edition.number)
        assertEquals(LocalDate.of(2026, 11, 10), edition.date)
        assertEquals(EditionKind.MORNING, edition.kind)
        assertEquals(EditionLength.STANDARD, edition.length)
        assertEquals(Instant.parse("2026-11-10T06:30:00Z"), edition.releaseAt)
        assertEquals(morning, edition.cutoffAt)
        assertEquals(morning, edition.builtAt)
        assertEquals(posts.toSet(), articleIdsOf(id))
        assertEquals(id, db.editionDao().getLatestReleased(morning)?.id)

        // Asking again shows the same frozen edition.
        val items = db.editionDao().getItems(id)
        clock.now = morning.plus(Duration.ofHours(5))
        addSlowPosts(3, seenAt = clock.now, prefix = "Later")
        assertEquals(id, publisher.ensureCurrentEdition())
        assertEquals(1, db.editionDao().count())
        assertEquals(items.map { it.item }, db.editionDao().getItems(id).map { it.item })
    }

    @Test
    fun firstEditionOnlyReachesBackAFewDays() = runTest {
        val feed = addFeed("Blog")
        val recent = addPost(feed, firstSeenAt = morning.minus(Duration.ofDays(2)))
        val old = addPost(feed, firstSeenAt = morning.minus(Duration.ofDays(5)))

        val id = publisher.ensureCurrentEdition()!!

        assertEquals(setOf(recent), articleIdsOf(id))
        assertTrue(db.editionDao().getItemsForArticle(old).isEmpty())
    }

    @Test
    fun secondEditionOnlyCoversPostsFirstSeenAfterThePreviousCutoff() = runTest {
        val firstPosts = addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        val first = publisher.ensureCurrentEdition()!!

        // Seen during the day, after the first edition's cutoff at 07:00.
        val newPosts = addSlowPosts(4, seenAt = morning.plus(Duration.ofHours(6)), prefix = "Day")
        // Published two days ago but only fetched this evening: it belongs to the next edition.
        val lateFeed = addFeed("Slow server")
        val fetchedLate = addPost(
            lateFeed,
            firstSeenAt = morning.plus(Duration.ofHours(12)),
            publishedAt = morning.minus(Duration.ofDays(2)),
        )

        clock.now = Instant.parse("2026-11-11T08:00:00Z")
        val second = publisher.ensureCurrentEdition()!!

        assertNotEquals(first, second)
        val edition = db.editionDao().getById(second)!!
        assertEquals(2, edition.number)
        assertEquals(LocalDate.of(2026, 11, 11), edition.date)
        assertEquals(Instant.parse("2026-11-11T06:30:00Z"), edition.releaseAt)
        assertEquals(clock.now, edition.cutoffAt)
        assertEquals((newPosts + fetchedLate).toSet(), articleIdsOf(second))
        assertEquals(firstPosts.toSet(), articleIdsOf(first))
        assertEquals(1, db.editionDao().getItemsForArticle(fetchedLate).size)
    }

    @Test
    fun aPostSeenAfterTheEarlyBuildWaitsForTheNextEdition() = runTest {
        addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        publisher.ensureCurrentEdition()!!

        // 05:00 the next day: the early build freezes tomorrow's paper.
        clock.now = Instant.parse("2026-11-11T05:00:00Z")
        val inTime = addSlowPosts(3, seenAt = minutesBefore(clock.now, 10), prefix = "In time")
        val early = publisher.buildNextEdition()!!
        // Fetched at 05:40, after that cutoff and before the 06:30 release.
        val tooLate = addPost(addFeed("Straggler"), firstSeenAt = Instant.parse("2026-11-11T05:40:00Z"))

        clock.now = Instant.parse("2026-11-11T07:00:00Z")
        assertEquals(early, publisher.ensureCurrentEdition())
        assertEquals(inTime.toSet(), articleIdsOf(early))

        clock.now = Instant.parse("2026-11-12T07:00:00Z")
        val third = publisher.ensureCurrentEdition()!!
        assertEquals(setOf(tooLate), articleIdsOf(third))
        assertEquals(3, db.editionDao().getById(third)!!.number)
    }

    @Test
    fun earlyBuildIsStoredUnreleasedForTheNextReset() = runTest {
        addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        val first = publisher.ensureCurrentEdition()!!

        clock.now = Instant.parse("2026-11-11T05:00:00Z")
        addSlowPosts(5, seenAt = minutesBefore(clock.now, 10), prefix = "Overnight")
        val next = publisher.buildNextEdition()!!

        val edition = db.editionDao().getById(next)!!
        assertEquals(LocalDate.of(2026, 11, 11), edition.date)
        assertEquals(Instant.parse("2026-11-11T06:30:00Z"), edition.releaseAt)
        assertEquals(clock.now, edition.cutoffAt)
        assertEquals(2, edition.number)
        // Building again returns the stored edition; before the reset the old one is current.
        assertEquals(next, publisher.buildNextEdition())
        assertEquals(first, publisher.ensureCurrentEdition())
        assertEquals(Instant.parse("2026-11-11T06:30:00Z"), publisher.nextReleaseAt())

        clock.now = Instant.parse("2026-11-11T06:30:00Z")
        assertEquals(next, publisher.ensureCurrentEdition())
        assertEquals(2, db.editionDao().count())
        assertEquals(Instant.parse("2026-11-12T06:30:00Z"), publisher.nextReleaseAt())
    }

    @Test
    fun aLongGapPutsEverythingSinceTheLastCutoffInOneEdition() = runTest {
        addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        val first = publisher.ensureCurrentEdition()!!

        // Phone off for ten days; posts trickled in before it went dark and after it woke.
        val beforeDark = addSlowPosts(3, seenAt = morning.plus(Duration.ofHours(3)), prefix = "Before")
        val afterWake = addSlowPosts(7, seenAt = Instant.parse("2026-11-20T09:00:00Z"), prefix = "After")
        clock.now = Instant.parse("2026-11-20T09:05:00Z")

        val second = publisher.ensureCurrentEdition()!!

        val edition = db.editionDao().getById(second)!!
        assertEquals(2, edition.number)
        assertEquals(LocalDate.of(2026, 11, 20), edition.date)
        assertEquals(Instant.parse("2026-11-20T06:30:00Z"), edition.releaseAt)
        assertEquals((beforeDark + afterWake).toSet(), articleIdsOf(second))
        assertEquals(2, db.editionDao().count())
        assertEquals(6, articleIdsOf(first).size)
    }

    @Test
    fun concurrentCallsCreateOneEdition() = runTest {
        addSlowPosts(10, seenAt = minutesBefore(morning, 20))
        val other = newPublisher()

        val ids = withContext(Dispatchers.Default) {
            (0 until 24).map { i -> async { (if (i % 2 == 0) publisher else other).ensureCurrentEdition() } }.awaitAll()
        }

        assertEquals(1, ids.toSet().size)
        assertNotNull(ids.first())
        assertEquals(1, db.editionDao().count())
        assertEquals(1, db.editionDao().maxNumber())
        assertEquals(10, db.editionDao().getItems(ids.first()!!).size)
    }

    @Test
    fun concurrentEarlyBuildAndOpenDoNotDuplicate() = runTest {
        addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        publisher.ensureCurrentEdition()!!
        clock.now = Instant.parse("2026-11-11T05:00:00Z")
        addSlowPosts(5, seenAt = minutesBefore(clock.now, 10), prefix = "Overnight")

        val ids = withContext(Dispatchers.Default) {
            (0 until 12).map { async { publisher.buildNextEdition() } }.awaitAll()
        }

        assertEquals(1, ids.toSet().size)
        assertEquals(2, db.editionDao().count())
    }

    /** A feed found through discovery, with one post the reader saved [savedAt] and never read. */
    private suspend fun addSavedDiscoveredPost(title: String, seenAt: Instant, savedAt: Instant = seenAt): Long {
        val feedId = db.feedDao().insert(
            FeedEntity(
                url = "https://found.example/$title.xml",
                title = title,
                addedAt = seenAt,
                status = FeedStatus.DISCOVERED,
                discoveredAt = seenAt,
            ),
        )
        val post = addPost(feedId, firstSeenAt = seenAt)
        db.engagementDao().setSaved(post, savedAt)
        return post
    }

    @Test
    fun aLoneSavedDiscoveredPostStillGivesNothingNew() = runTest {
        addFeed("Subscribed but quiet")
        addSavedDiscoveredPost("Found", seenAt = minutesBefore(morning, 10))

        // First seen in the window, saved and unread, yet not from a subscription: no paper.
        assertNull(publisher.ensureCurrentEdition())
        assertNull(publisher.buildNextEdition())
        assertEquals(0, db.editionDao().count())

        // Nor once there is an edition to follow.
        addSlowPosts(4, seenAt = minutesBefore(morning, 5))
        val first = publisher.ensureCurrentEdition()!!
        addSavedDiscoveredPost("Found later", seenAt = Instant.parse("2026-11-11T05:00:00Z"))
        clock.now = Instant.parse("2026-11-11T08:00:00Z")
        assertEquals(first, publisher.ensureCurrentEdition())
        assertEquals(1, db.editionDao().count())
    }

    @Test
    fun aSavedDiscoveredPostRunsFromYourReadingListAtMostOnceAWeek() = runTest {
        val older = addSavedDiscoveredPost("Found", seenAt = morning.minus(Duration.ofDays(3)), savedAt = morning.minus(Duration.ofDays(3)))
        val newer = addSavedDiscoveredPost("Found too", seenAt = morning.minus(Duration.ofDays(1)), savedAt = morning.minus(Duration.ofDays(1)))
        val posts = addSlowPosts(8, seenAt = minutesBefore(morning, 20))

        val id = publisher.ensureCurrentEdition()!!
        val kinds = db.editionDao().getItems(id).associate { it.item.articleId to it.item.kind }
        // The oldest saved one, on a page of its own; the other is not a candidate anywhere.
        assertEquals(ItemKind.READING_LIST, kinds[older])
        assertFalse(newer in kinds)
        assertTrue(posts.all { kinds[it] != ItemKind.READING_LIST })
        val edition = EditionPages.toEdition(db.editionDao().getById(id)!!, db.editionDao().getItems(id), publisher.nextReleaseAt())
        val page = edition.pages.filterIsInstance<EditionPage.FromYourReadingList>().single()
        assertEquals(listOf(older), page.stories.map { it.articleId })
        assertFalse(page.stories.single().feedSubscribed)
        assertTrue(page.stories.single().reason!!.contains("don't subscribe"))
        assertEquals(EditionPage.Back::class, edition.pages.last()::class)

        // The next morning has news but no reading list: one a week at most.
        addSlowPosts(3, seenAt = Instant.parse("2026-11-11T05:00:00Z"), prefix = "Wed")
        clock.now = Instant.parse("2026-11-11T07:00:00Z")
        val wednesday = publisher.ensureCurrentEdition()!!
        assertTrue(db.editionDao().getItems(wednesday).none { it.item.kind == ItemKind.READING_LIST })

        // A week on, the next saved post (the first has had its turn, read or not).
        addSlowPosts(3, seenAt = Instant.parse("2026-11-17T05:00:00Z"), prefix = "Next Tue")
        clock.now = Instant.parse("2026-11-17T07:00:00Z")
        val week = publisher.ensureCurrentEdition()!!
        val reading = db.editionDao().getItems(week).filter { it.item.kind == ItemKind.READING_LIST }.map { it.item.articleId }
        assertEquals(listOf(newer), reading)
    }

    @Test
    fun aReadOrSubscribedPostIsNotOnTheReadingList() = runTest {
        val read = addSavedDiscoveredPost("Read", seenAt = morning.minus(Duration.ofDays(3)))
        db.engagementDao().markOpened(read, morning.minus(Duration.ofDays(2)))
        db.engagementDao().markRead(read, morning.minus(Duration.ofDays(2)))
        val subscribedLater = addSavedDiscoveredPost("Subscribed", seenAt = morning.minus(Duration.ofDays(2)))
        db.feedDao().subscribeDiscovered(db.articleDao().getById(subscribedLater)!!.feedId, morning.minus(Duration.ofDays(1)))
        addSlowPosts(6, seenAt = minutesBefore(morning, 20))

        val id = publisher.ensureCurrentEdition()!!
        assertTrue(db.editionDao().getItems(id).none { it.item.kind == ItemKind.READING_LIST })
    }

    @Test
    fun nothingToBuildReturnsNull() = runTest {
        assertNull(publisher.ensureCurrentEdition())
        assertNull(publisher.buildNextEdition())

        addFeed("Empty")
        assertNull(publisher.ensureCurrentEdition())
        assertNull(publisher.buildNextEdition())
        assertEquals(0, db.editionDao().count())
    }

    @Test
    fun aDayWithoutNewPostsKeepsThePreviousEditionCurrent() = runTest {
        addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        val first = publisher.ensureCurrentEdition()!!

        clock.now = Instant.parse("2026-11-11T05:00:00Z")
        assertNull(publisher.buildNextEdition())
        clock.now = Instant.parse("2026-11-11T08:00:00Z")
        assertEquals(first, publisher.ensureCurrentEdition())
        assertEquals(1, db.editionDao().count())

        // The posts that do arrive later that day make the paper when the app is next opened.
        val late = addSlowPosts(2, seenAt = Instant.parse("2026-11-11T09:00:00Z"), prefix = "Late")
        clock.now = Instant.parse("2026-11-11T10:00:00Z")
        val second = publisher.ensureCurrentEdition()!!
        assertEquals(late.toSet(), articleIdsOf(second))
        assertEquals(LocalDate.of(2026, 11, 11), db.editionDao().getById(second)!!.date)
    }

    @Test
    fun mutedFeedsAndReadPostsDoNotMakeAnEdition() = runTest {
        val muted = addFeed("Muted", muteUntil = morning.plus(Duration.ofDays(3)))
        addPost(muted, firstSeenAt = minutesBefore(morning, 20))
        val feed = addFeed("Read already")
        val read = addPost(feed, firstSeenAt = minutesBefore(morning, 20))
        db.engagementDao().markOpened(read, minutesBefore(morning, 5))
        db.engagementDao().markRead(read, minutesBefore(morning, 4))

        assertNull(publisher.ensureCurrentEdition())
        assertEquals(0, db.editionDao().count())

        val fresh = addPost(feed, firstSeenAt = minutesBefore(morning, 1))
        val id = publisher.ensureCurrentEdition()!!
        val items = db.editionDao().getItems(id).associate { it.item.articleId to it.item.kind }
        assertEquals(mapOf(fresh to ItemKind.LEAD, read to ItemKind.INDEX), items)
    }

    @Test
    fun editionRendersThroughEditionPagesInTheExpectedPageSequence() = runTest {
        val seenAt = minutesBefore(morning, 20)
        addSlowPosts(30, seenAt = seenAt)
        val wire = addFeed("Wire", tier = CadenceTier.FIREHOSE, medianHours = 0.5)
        repeat(40) { i -> addPost(wire, firstSeenAt = seenAt, publishedAt = seenAt.minus(Duration.ofMinutes(20L * i))) }

        val id = publisher.ensureCurrentEdition()!!
        val nextAt = publisher.nextReleaseAt()
        val edition = EditionPages.toEdition(db.editionDao().getById(id)!!, db.editionDao().getItems(id), nextAt)

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

        val back = edition.pages[5] as EditionPage.Back
        assertEquals(Instant.parse("2026-11-11T06:30:00Z"), back.nextEditionAt)
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
    fun editionLengthSettingIsUsedAndFrozen() = runTest {
        settings.setEditionLength(EditionLength.SHORT)
        addSlowPosts(30, seenAt = minutesBefore(morning, 20))

        val id = publisher.ensureCurrentEdition()!!
        settings.setEditionLength(EditionLength.LONG)

        assertEquals(EditionLength.SHORT, db.editionDao().getById(id)!!.length)
        val perPage = db.editionDao().getItems(id).filter { it.item.kind != ItemKind.INDEX }.groupBy { it.item.page }
        assertEquals(listOf(5, 4, 4, 3), perPage.toSortedMap().values.map { it.size })
    }

    @Test
    fun resetTimeSettingDecidesReleaseAndDate() = runTest {
        settings.setResetTime(LocalTime.of(9, 0))
        addSlowPosts(5, seenAt = minutesBefore(morning, 20))

        // 07:00 is before today's 09:00 reset: the current edition is yesterday's.
        val id = publisher.ensureCurrentEdition()!!

        val edition = db.editionDao().getById(id)!!
        assertEquals(LocalDate.of(2026, 11, 9), edition.date)
        assertEquals(Instant.parse("2026-11-09T09:00:00Z"), edition.releaseAt)
        assertEquals(Instant.parse("2026-11-10T09:00:00Z"), publisher.nextReleaseAt())
    }

    @Test
    fun supersedingAnEditionCountsItsUnopenedRunsAndTheLead() = runTest {
        val seenAt = minutesBefore(morning, 20)
        addSlowPosts(30, seenAt = seenAt)
        val first = publisher.ensureCurrentEdition()!!
        val items = db.editionDao().getItems(first).map { it.item }
        val lead = items.single { it.kind == ItemKind.LEAD }
        val printed = items.filter { it.kind != ItemKind.INDEX }
        val opened = printed.first { it.kind == ItemKind.TOP }
        val indexed = items.first { it.kind == ItemKind.INDEX }

        assertEquals(1, db.engagementDao().get(lead.articleId)?.leadCount)
        // Nothing is counted while the edition is still the current one.
        assertEquals(0, db.engagementDao().get(lead.articleId)?.editionsRunUnopened)
        db.engagementDao().markOpened(opened.articleId, morning.plus(Duration.ofHours(1)))

        clock.now = Instant.parse("2026-11-11T07:00:00Z")
        addSlowPosts(3, seenAt = minutesBefore(clock.now, 30), prefix = "Next")
        val second = publisher.ensureCurrentEdition()!!

        assertEquals(1, db.engagementDao().get(lead.articleId)?.editionsRunUnopened)
        assertEquals(0, db.engagementDao().get(opened.articleId)?.editionsRunUnopened)
        assertNull(db.engagementDao().get(indexed.articleId))
        printed.filter { it.articleId != opened.articleId }.forEach {
            assertEquals(1, db.engagementDao().get(it.articleId)?.editionsRunUnopened)
        }
        // The new edition's own stories have not run unopened yet.
        val secondLead = db.editionDao().getItems(second).single { it.item.kind == ItemKind.LEAD }
        assertEquals(0, db.engagementDao().get(secondLead.item.articleId)?.editionsRunUnopened)
        assertEquals(1, db.engagementDao().get(secondLead.item.articleId)?.leadCount)

        // A day with nothing to build must not count the same edition again.
        clock.now = Instant.parse("2026-11-12T07:00:00Z")
        assertEquals(second, publisher.ensureCurrentEdition())
        assertEquals(1, db.engagementDao().get(lead.articleId)?.editionsRunUnopened)
        assertEquals(0, db.engagementDao().get(secondLead.item.articleId)?.editionsRunUnopened)
    }

    @Test
    fun travellingWestKeepsTheNewestEditionCurrent() = runTest {
        addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        val first = publisher.ensureCurrentEdition()!!
        addSlowPosts(2, seenAt = morning.plus(Duration.ofMinutes(10)), prefix = "New")

        // 07:30 UTC is 21:30 the evening before in Honolulu, where the latest reset is the 9th's.
        clock.now = morning.plus(Duration.ofMinutes(30))
        zone = ZoneId.of("Pacific/Honolulu")

        assertEquals(first, publisher.ensureCurrentEdition())
        assertEquals(1, db.editionDao().count())
        // The next local reset is the 10th at 06:30 HST; that date's edition already exists.
        assertEquals(Instant.parse("2026-11-10T16:30:00Z"), publisher.nextReleaseAt())
        assertEquals(first, publisher.buildNextEdition())
    }

    @Test
    fun travellingEastDoesNotShowAnEditionBeforeItsOwnReleaseTime() = runTest {
        addSlowPosts(6, seenAt = minutesBefore(morning, 30))
        val first = publisher.ensureCurrentEdition()!!
        clock.now = Instant.parse("2026-11-11T05:00:00Z")
        addSlowPosts(4, seenAt = minutesBefore(clock.now, 10), prefix = "Overnight")
        val early = publisher.buildNextEdition()!!

        // In Tokyo it is already 14:10 on the 11th, past the local reset, but the stored
        // edition for the 11th is released at 06:30 UTC.
        clock.now = Instant.parse("2026-11-11T05:10:00Z")
        zone = ZoneId.of("Asia/Tokyo")
        assertEquals(first, publisher.ensureCurrentEdition())

        clock.now = Instant.parse("2026-11-11T06:30:00Z")
        assertEquals(early, publisher.ensureCurrentEdition())
        assertEquals(2, db.editionDao().count())
    }
}
