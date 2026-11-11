package net.tgnra.morningissue.edition

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.repository.EditionPages
import net.tgnra.morningissue.data.repository.RoomEditionRepository
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.Edition
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionPage
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Multi-day runs of the publisher against a real database: While you were away, the Evening
 * edition, cross-feed deduplication, Welcome back and the Extra edition.
 */
@RunWith(AndroidJUnit4::class)
class EditionScenariosTest {
    private val clock = MutableClock(at(10, "07:00"))
    private val settings = FakeSettings()
    private lateinit var db: MorningIssueDatabase
    private lateinit var publisher: RoomEditionPublisher
    private var guid = 0

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        publisher = RoomEditionPublisher(db, settings, clock, zone = { ZoneOffset.UTC })
    }

    @After
    fun tearDown() = db.close()

    /** A time on [day] of November 2026 (UTC). The reset is at 06:30. */
    private fun at(day: Int, time: String): Instant = Instant.parse("2026-11-%02dT%s:00Z".format(day, time))

    private suspend fun addFeed(
        title: String,
        tier: CadenceTier = CadenceTier.WEEKLY,
        slider: SliderStep = SliderStep.AUTO,
    ): Long = db.feedDao().insert(
        FeedEntity(
            url = "https://example.com/$title.xml",
            title = title,
            tier = tier,
            medianIntervalHrs = when (tier) {
                CadenceTier.FIREHOSE -> 0.5
                CadenceTier.DAILY -> 24.0
                CadenceTier.WEEKLY -> 168.0
                CadenceTier.RARE -> 720.0
            },
            sliderStep = slider,
            addedAt = at(1, "00:00"),
        ),
    )

    private suspend fun addPost(
        feedId: Long,
        seenAt: Instant,
        publishedAt: Instant = seenAt.minus(Duration.ofMinutes(30)),
        title: String? = null,
        url: String? = null,
    ): Long {
        val id = "post-${guid++}"
        return db.articleDao().insertNew(
            ArticleEntity(
                feedId = feedId,
                guid = id,
                url = url ?: "https://example.com/$id",
                title = title ?: "Title $id",
                publishedAt = publishedAt,
                firstSeenAt = seenAt,
            ),
        )!!
    }

    /** [count] new feeds of [tier] with one post each, seen at [seenAt]. */
    private suspend fun addPosts(count: Int, seenAt: Instant, prefix: String, tier: CadenceTier = CadenceTier.WEEKLY): List<Long> =
        (0 until count).map { i ->
            addPost(addFeed("$prefix $i", tier), seenAt, publishedAt = seenAt.minus(Duration.ofMinutes(10L + i)))
        }

    private suspend fun itemsOf(editionId: Long): List<EditionItemEntity> = db.editionDao().getItems(editionId).map { it.item }

    private suspend fun printedIn(editionId: Long): Set<Long> =
        itemsOf(editionId).filter { it.kind != ItemKind.INDEX }.map { it.articleId }.toSet()

    private suspend fun idsOf(editionId: Long): Set<Long> = itemsOf(editionId).map { it.articleId }.toSet()

    private suspend fun render(editionId: Long): Edition =
        EditionPages.toEdition(db.editionDao().getById(editionId)!!, db.editionDao().getItems(editionId), nextEditionAt = null)

    private suspend fun awayIn(editionId: Long): List<Long> =
        itemsOf(editionId).filter { it.kind == ItemKind.AWAY }.sortedBy { it.slot }.map { it.articleId }

    private suspend fun open(editionId: Long) = db.editionDao().markOpened(editionId, clock.now)

    private suspend fun runs(articleId: Long): Int = db.engagementDao().get(articleId)?.editionsRunUnopened ?: 0

    // --- While you were away -----------------------------------------------------------------

    @Test
    fun skippingTwoDaysBringsAPullOutOfTheMissedEditionsOnceAndNeverAgain() = runTest {
        // Tuesday: the reader opens the paper.
        val tuesdayPosts = addPosts(8, at(10, "06:40"), "Tue")
        val tuesday = publisher.ensureCurrentEdition()!!
        open(tuesday)
        assertTrue(awayIn(tuesday).isEmpty())

        // Wednesday and Thursday are built overnight and never opened.
        clock.now = at(11, "05:00")
        val wednesdayPosts = addPosts(6, at(11, "04:00"), "Wed")
        val wire = addFeed("Wire", CadenceTier.FIREHOSE)
        val fromWire = addPost(wire, at(11, "04:30"))
        val turnedDown = addPost(addFeed("Turned down", slider = SliderStep.LESS), at(11, "04:30"))
        val wednesday = publisher.buildNextEdition()!!
        assertTrue("yesterday's paper was opened, so nothing was missed", awayIn(wednesday).isEmpty())
        assertTrue(fromWire in printedIn(wednesday) && turnedDown in printedIn(wednesday))

        clock.now = at(12, "05:00")
        val thursdayPosts = addPosts(6, at(12, "04:00"), "Thu")
        val thursday = publisher.buildNextEdition()!!
        // Thursday's paper already carried Wednesday's best, but nobody saw it.
        assertEquals(wednesdayPosts.toSet(), awayIn(thursday).toSet())

        // The reader read one of Wednesday's stories on its source page meanwhile.
        val readElsewhere = wednesdayPosts[2]
        db.engagementDao().markOpened(readElsewhere, at(12, "12:00"))

        // Friday: back again.
        clock.now = at(13, "08:00")
        val fridayPosts = addPosts(7, at(13, "07:30"), "Fri")
        val friday = publisher.ensureCurrentEdition()!!

        val away = awayIn(friday)
        // Both missed editions' stories, minus the one already opened; no firehose post, no
        // feed turned down, nothing from the edition that was read.
        assertEquals((wednesdayPosts + thursdayPosts - readElsewhere).toSet(), away.toSet())
        assertFalse(fromWire in idsOf(friday))
        assertFalse(turnedDown in idsOf(friday))
        assertTrue(tuesdayPosts.none { it in idsOf(friday) })
        // Fresher first: Thursday's stories lead the pull-out.
        assertEquals(thursdayPosts.toSet(), away.take(6).toSet())
        // Every article is placed once, and today's news is on the regular pages.
        assertEquals(itemsOf(friday).size, idsOf(friday).size)
        assertTrue(itemsOf(friday).filter { it.articleId in fridayPosts }.none { it.kind == ItemKind.AWAY })

        val pages = render(friday).pages
        assertTrue(pages[0] is EditionPage.Front)
        assertTrue(pages[1] is EditionPage.WhileYouWereAway)
        assertEquals(away, pages[1].stories.map { it.articleId })
        assertEquals(11, pages[1].stories.size)
        assertEquals(11, pages[1].stories.map { it.feedId }.distinct().size)

        // The reader opens Friday's paper. Saturday has nothing to catch up on.
        open(friday)
        clock.now = at(14, "07:00")
        addPosts(5, at(14, "06:00"), "Sat")
        val saturday = publisher.ensureCurrentEdition()!!
        assertTrue(awayIn(saturday).isEmpty())

        // Saturday is skipped. Monday's pull-out has Saturday's stories only: nothing from the
        // earlier pull-out comes back, and the old missed editions do not pile up.
        clock.now = at(16, "07:00")
        addPosts(5, at(16, "06:00"), "Mon")
        val monday = publisher.ensureCurrentEdition()!!
        assertEquals(printedIn(saturday), awayIn(monday).toSet())
        assertTrue(away.none { it in idsOf(monday) })
    }

    @Test
    fun aFirehoseFeedEntersThePullOutThroughARecentLikeOrSave() = runTest {
        addPosts(6, at(10, "06:40"), "Tue")
        val wire = addFeed("Wire", CadenceTier.FIREHOSE)
        val liked = addPost(wire, at(10, "06:45"))
        open(publisher.ensureCurrentEdition()!!)
        db.engagementDao().setLiked(liked, at(10, "07:30"))

        clock.now = at(11, "05:00")
        addPosts(4, at(11, "04:00"), "Wed")
        val missedFromWire = addPost(wire, at(11, "04:50"))
        val wednesday = publisher.buildNextEdition()!!
        assertTrue(missedFromWire in printedIn(wednesday))

        clock.now = at(12, "08:00")
        addPosts(4, at(12, "07:00"), "Thu")
        val thursday = publisher.ensureCurrentEdition()!!

        assertTrue(missedFromWire in awayIn(thursday))
    }

    @Test
    fun aReaderWhoHasNeverOpenedAnEditionHasNotBeenAway() = runTest {
        addPosts(6, at(10, "06:40"), "Tue")
        publisher.ensureCurrentEdition()!!
        clock.now = at(11, "07:00")
        addPosts(6, at(11, "06:40"), "Wed")

        assertTrue(awayIn(publisher.ensureCurrentEdition()!!).isEmpty())
    }

    // --- Evening edition ---------------------------------------------------------------------

    private suspend fun enableEvening() = settings.setEveningEditionEnabled(true)

    @Test
    fun eveningAndNextMorningCoverEveryPostWithNothingPrintedTwice() = runTest {
        enableEvening()
        val favourite = addFeed("Essays", CadenceTier.RARE, SliderStep.FAVOURITE)
        val essay = addPost(favourite, at(10, "06:00"))
        addPosts(6, at(10, "06:40"), "Tue")
        val morning = publisher.ensureCurrentEdition()!!
        val morningPrinted = printedIn(morning)
        assertEquals(at(10, "18:00"), publisher.nextReleaseAt())

        // During the day: daily feeds and a wire post a lot, slow feeds a little.
        val dailies = (0 until 6).map { addFeed("Daily $it", CadenceTier.DAILY) }
        val wire = addFeed("Wire", CadenceTier.FIREHOSE)
        val fast = dailies.flatMap { feed -> (0 until 2).map { addPost(feed, at(10, "12:00"), at(10, "11:0$it")) } } +
            (0 until 20).map { addPost(wire, at(10, "13:00"), at(10, "12:00").plus(Duration.ofMinutes(it.toLong()))) }
        val slow = addPosts(3, at(10, "12:00"), "Slow") + addPost(favourite, at(10, "14:00"))

        // Before 18:00 nothing changes.
        clock.now = at(10, "17:00")
        assertEquals(morning, publisher.ensureCurrentEdition())
        assertEquals(1, db.editionDao().count())

        // The reader turned through the morning paper.
        db.viewPages(morning, at(10, "08:00"))
        clock.now = at(10, "18:30")
        val evening = publisher.ensureCurrentEdition()!!
        val eveningEdition = db.editionDao().getById(evening)!!
        assertEquals(EditionKind.EVENING, eveningEdition.kind)
        assertEquals(LocalDate.of(2026, 11, 10), eveningEdition.date)
        assertEquals(2, eveningEdition.number)
        assertEquals(at(10, "18:00"), eveningEdition.releaseAt)
        assertEquals(at(10, "18:30"), eveningEdition.cutoffAt)
        // Only the fast feeds, on a front page and In brief; the rest of them listed.
        assertEquals(fast.toSet(), idsOf(evening))
        val eveningPrinted = printedIn(evening)
        assertEquals(20, eveningPrinted.size)
        assertEquals(
            setOf(ItemKind.LEAD, ItemKind.FRONT, ItemKind.BRIEF, ItemKind.INDEX),
            itemsOf(evening).map { it.kind }.toSet(),
        )
        assertEquals(
            listOf("Front", "InBrief", "Back"),
            render(evening).pages.map { it::class.simpleName },
        )
        // It superseded the morning paper: those stories have now run unopened once.
        morningPrinted.forEach { assertEquals(1, runs(it)) }
        assertEquals(evening, publisher.ensureCurrentEdition())
        assertEquals(2, db.editionDao().count())
        assertEquals(at(11, "06:30"), publisher.nextReleaseAt())

        // Later that night.
        val night = (0 until 3).map { addPost(wire, at(10, "22:00")) } + addPosts(1, at(10, "22:00"), "Night")

        db.viewPages(evening, at(10, "19:00"))
        clock.now = at(11, "07:00")
        val next = publisher.ensureCurrentEdition()!!
        val nextEdition = db.editionDao().getById(next)!!
        assertEquals(EditionKind.MORNING, nextEdition.kind)
        assertEquals(3, nextEdition.number)

        val nextIds = idsOf(next)
        // Nothing lost: every post of the day is in the evening paper or the next morning's.
        (fast + slow + night).forEach { assertTrue("post $it is in neither edition", it in idsOf(evening) || it in nextIds) }
        // Nothing twice: what the evening printed does not come back at all, and what it only
        // listed is offered to the morning paper as new.
        assertTrue(eveningPrinted.none { it in nextIds })
        assertEquals((fast - eveningPrinted + slow + night + essay).toSet(), nextIds)
        assertEquals(itemsOf(next).size, nextIds.size)
        assertTrue(slow.all { it in printedIn(next) })

        // Counters: each superseded edition counted once. The held-over essay ran in the
        // morning paper only (never in the evening one), so it comes back with one run.
        eveningPrinted.forEach { assertEquals(1, runs(it)) }
        morningPrinted.forEach { assertEquals(1, runs(it)) }
        assertFalse(essay in idsOf(evening))
        assertEquals(1, runs(essay))
        assertTrue(db.editionDao().getItems(next).single { it.item.articleId == essay }.item.reason!!.startsWith("Held over"))

        // On the newsstand and in back issues: three editions with their kinds, newest first.
        val summaries = RoomEditionRepository(db, publisher, clock).observeReleasedEditions().first()
        assertEquals(listOf(EditionKind.MORNING, EditionKind.EVENING, EditionKind.MORNING), summaries.map { it.kind })
        assertEquals(listOf(3, 2, 1), summaries.map { it.number })
    }

    @Test
    fun theEveningEditionIsOffByDefaultAndNeedsFastPosts() = runTest {
        assertFalse(AppSettings().eveningEditionEnabled)
        addPosts(6, at(10, "06:40"), "Tue")
        val morning = publisher.ensureCurrentEdition()!!
        val wire = addFeed("Wire", CadenceTier.FIREHOSE)
        addPost(wire, at(10, "12:00"))

        clock.now = at(10, "19:00")
        assertEquals(morning, publisher.ensureCurrentEdition())
        assertEquals(at(11, "06:30"), publisher.nextReleaseAt())
        assertEquals(1, db.editionDao().count())

        // Switched on, but with only a weekly post since the morning there is nothing to print.
        val quiet = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        try {
            val other = RoomEditionPublisher(quiet, settings, clock, zone = { ZoneOffset.UTC })
            enableEvening()
            clock.now = at(10, "07:00")
            val feed = quiet.feedDao().insert(FeedEntity(url = "https://example.com/w.xml", title = "W", addedAt = at(1, "00:00")))
            fun post(n: Int, seenAt: Instant) =
                ArticleEntity(feedId = feed, guid = "w$n", url = "https://example.com/w$n", title = "W $n", publishedAt = seenAt, firstSeenAt = seenAt)
            quiet.articleDao().insertNew(post(1, at(10, "06:00")))
            val first = other.ensureCurrentEdition()!!
            quiet.articleDao().insertNew(post(2, at(10, "12:00")))
            clock.now = at(10, "19:00")
            assertEquals(first, other.ensureCurrentEdition())
            assertEquals(1, quiet.editionDao().count())
            // The morning paper was not superseded by an edition that never existed.
            assertEquals(0, quiet.engagementDao().get(quiet.editionDao().getItems(first).first().item.articleId)?.editionsRunUnopened ?: 0)
        } finally {
            quiet.close()
        }
    }

    @Test
    fun theEveningEditionCanBeBuiltEarlyAndFollowsItsOwnTimeSetting() = runTest {
        enableEvening()
        settings.setEveningEditionTime(LocalTime.of(20, 15))
        addPosts(6, at(10, "06:40"), "Tue")
        val morning = publisher.ensureCurrentEdition()!!
        val wire = addFeed("Wire", CadenceTier.FIREHOSE)
        val inTime = (0 until 4).map { addPost(wire, at(10, "18:00")) }

        clock.now = at(10, "18:45")
        assertEquals(at(10, "20:15"), publisher.nextReleaseAt())
        val early = publisher.buildNextEdition()!!
        val edition = db.editionDao().getById(early)!!
        assertEquals(EditionKind.EVENING, edition.kind)
        assertEquals(at(10, "20:15"), edition.releaseAt)
        assertEquals(early, publisher.buildNextEdition())
        // Hidden until its release time.
        assertEquals(morning, publisher.ensureCurrentEdition())
        val tooLate = addPost(wire, at(10, "19:30"))

        clock.now = at(10, "20:15")
        assertEquals(early, publisher.ensureCurrentEdition())
        assertEquals(inTime.toSet(), idsOf(early))

        // The straggler is tomorrow morning's.
        clock.now = at(11, "07:00")
        val next = publisher.ensureCurrentEdition()!!
        assertEquals(EditionKind.MORNING, db.editionDao().getById(next)!!.kind)
        assertEquals(setOf(tooLate), idsOf(next))
    }

    @Test
    fun openingOnlyInTheEveningBuildsTheMorningPaperFirst() = runTest {
        enableEvening()
        addPosts(6, at(10, "06:40"), "Tue")
        publisher.ensureCurrentEdition()!!
        val wire = addFeed("Wire", CadenceTier.FIREHOSE)
        (0 until 4).map { addPost(wire, at(11, "09:00")) }
        addPosts(2, at(11, "09:00"), "Wed")

        // Wednesday, first opened at 19:00 with no early builds: one morning paper with
        // everything; the evening paper would have nothing new to print.
        clock.now = at(11, "19:00")
        val current = publisher.ensureCurrentEdition()!!

        assertEquals(EditionKind.MORNING, db.editionDao().getById(current)!!.kind)
        assertEquals(LocalDate.of(2026, 11, 11), db.editionDao().getById(current)!!.date)
        assertEquals(2, db.editionDao().count())
    }

    // --- Cross-feed deduplication ------------------------------------------------------------

    @Test
    fun aSyndicatedStoryAppearsOnceFromTheHigherAffinityFeed() = runTest {
        addPosts(5, at(10, "06:40"), "Tue")
        val original = addFeed("Original", slider = SliderStep.MORE)
        val aggregator = addFeed("Aggregator", CadenceTier.DAILY)
        val mirror = addFeed("Mirror", CadenceTier.DAILY)
        val kept = addPost(original, at(10, "06:00"), title = "How the canal was drained", url = "https://original.example/canal/")
        val copy = addPost(
            aggregator, at(10, "06:30"),
            title = "Original: How the canal was drained", url = "http://www.original.example/canal?utm_source=aggregator",
        )
        val retitledLink = addPost(mirror, at(10, "06:35"), title = "How the Canal Was Drained", url = "https://mirror.example/p/991")
        val unrelated = addPost(aggregator, at(10, "06:30"), title = "How the canal was dredged", url = "https://aggregator.example/dredged")

        val first = publisher.ensureCurrentEdition()!!

        val ids = idsOf(first)
        assertTrue(kept in printedIn(first))
        assertFalse(copy in ids)
        assertFalse(retitledLink in ids)
        assertTrue(unrelated in ids)
        val story = render(first).pages.flatMap { it.stories }.single { it.articleId == kept }
        assertTrue(story.reason, story.reason!!.endsWith("; also in 2 other feeds"))
        // The dropped copies are placed nowhere, and stay readable on their source pages.
        assertTrue(db.editionDao().getItemsForArticle(copy).isEmpty())
        assertNotNull(db.articleDao().getById(copy))

        // They do not come back as new the next day either.
        clock.now = at(11, "07:00")
        val nextDay = addPosts(4, at(11, "06:40"), "Wed")
        val second = publisher.ensureCurrentEdition()!!
        // (The kept copy returns held over: its feed is one the reader asked to see more of.)
        assertEquals((nextDay + kept).toSet(), idsOf(second))
    }

    @Test
    fun aLateCopyOfAStoryTheEveningPaperPrintedIsNotNewInTheMorning() = runTest {
        enableEvening()
        addPosts(6, at(10, "06:40"), "Tue")
        publisher.ensureCurrentEdition()!!
        val wire = addFeed("Wire", CadenceTier.FIREHOSE)
        val printed = addPost(wire, at(10, "15:00"), title = "Storm closes the northern ports", url = "https://wire.example/storm")
        clock.now = at(10, "18:10")
        val evening = publisher.ensureCurrentEdition()!!
        assertTrue(printed in printedIn(evening))

        // A weekly blog syndicates the same piece overnight.
        val lateCopy = addPost(addFeed("Blog"), at(10, "23:00"), title = "Storm closes the northern ports", url = "https://blog.example/storm")
        val fresh = addPosts(3, at(10, "23:00"), "Night")
        clock.now = at(11, "07:00")
        val next = publisher.ensureCurrentEdition()!!

        assertEquals(fresh.toSet(), idsOf(next))
        assertFalse(lateCopy in idsOf(next))
    }

    // --- Welcome back ------------------------------------------------------------------------

    @Test
    fun aFeedQuietForOverNinetyDaysLeadsWithItsLabelWhenItPostsAgain() = runTest {
        val dormant = addFeed("Dormant")
        val lastPost = at(10, "07:00").minus(Duration.ofDays(800))
        addPost(dormant, seenAt = lastPost, publishedAt = lastPost)
        val active = addFeed("Active")
        addPost(active, at(4, "09:00"))
        addPosts(6, at(10, "06:40"), "Tue")
        publisher.ensureCurrentEdition()!!

        // The next day's news is strong: well-liked daily feeds, all posting within the hour.
        clock.now = at(11, "07:00")
        (0 until 8).forEach { addPost(addFeed("Liked $it", CadenceTier.DAILY, SliderStep.MORE), at(11, "06:50")) }
        val comeback = addPost(dormant, at(11, "02:00"), publishedAt = at(10, "20:00"))
        val later = addPost(dormant, at(11, "02:00"), publishedAt = at(10, "21:00"))
        val ordinary = addPost(active, at(11, "02:00"), publishedAt = at(10, "20:00"))

        val edition = render(publisher.ensureCurrentEdition()!!)

        val front = edition.pages.first() as EditionPage.Front
        assertEquals(comeback, front.lead?.articleId)
        assertEquals("Back after 2 years", front.lead?.comebackLabel)
        assertEquals("Back after 2 years: Dormant has posted again", front.lead?.reason)
        // Only the first post back is the comeback, and an active feed gets no label.
        val stories = edition.pages.flatMap { it.stories }.associateBy { it.articleId }
        assertNull(stories.getValue(later).comebackLabel)
        assertNull(stories.getValue(ordinary).comebackLabel)
        assertTrue(stories.getValue(comeback).score > 1.5 * stories.getValue(ordinary).score * 0.99)
    }

    @Test
    fun aNewlyAddedFeedIsNotAComeback() = runTest {
        addPosts(6, at(10, "06:40"), "Tue")
        publisher.ensureCurrentEdition()!!

        // Its whole history arrives at once: an old post, then one from yesterday.
        clock.now = at(11, "07:00")
        val added = addFeed("Just added")
        addPost(added, at(11, "06:00"), publishedAt = at(11, "06:00").minus(Duration.ofDays(400)))
        val recent = addPost(added, at(11, "06:00"), publishedAt = at(10, "20:00"))

        val edition = render(publisher.ensureCurrentEdition()!!)

        val story = edition.pages.flatMap { it.stories }.single { it.articleId == recent }
        assertNull(story.comebackLabel)
        assertFalse(story.reason!!.startsWith("Back after"))
    }

    // --- Extra edition -----------------------------------------------------------------------

    @Test
    fun anExtraEditionPrintsTheNewSourcesWithoutDisturbingTheSchedule() = runTest {
        val existing = addFeed("Existing")
        addPost(existing, at(10, "06:00"))
        addPosts(6, at(10, "06:40"), "Tue")
        val morning = publisher.ensureCurrentEdition()!!
        val morningItems = itemsOf(morning)
        val morningRow = db.editionDao().getById(morning)
        val nextRelease = publisher.nextReleaseAt()

        // 10:00: two new sources are added and synced; an existing feed posts as well.
        clock.now = at(10, "10:00")
        val elsewhere = addPost(existing, at(10, "09:00"))
        val essays = addFeed("New essays", CadenceTier.RARE, SliderStep.FAVOURITE)
        val essayPosts = listOf(1L, 40L, 200L).map { days -> addPost(essays, clock.now, publishedAt = clock.now.minus(Duration.ofDays(days))) }
        val wire = addFeed("New wire", CadenceTier.FIREHOSE)
        val wirePosts = (0 until 40).map { addPost(wire, clock.now, publishedAt = clock.now.minus(Duration.ofMinutes(10L * it))) }

        val extra = publisher.publishExtraEdition(setOf(essays, wire))!!

        val edition = db.editionDao().getById(extra)!!
        assertEquals(EditionKind.EXTRA, edition.kind)
        assertEquals(LocalDate.of(2026, 11, 10), edition.date)
        assertEquals(2, edition.number)
        assertEquals(clock.now, edition.releaseAt)
        // Only the chosen feeds; a quiet feed's newest posts are printed however old they are.
        assertEquals((essayPosts + wirePosts).toSet(), idsOf(extra))
        assertTrue(elsewhere !in idsOf(extra))
        val extraPrinted = printedIn(extra)
        assertTrue(essayPosts.take(2).all { it in extraPrinted })
        val listedOnly = wirePosts.filter { it !in extraPrinted }
        assertTrue(listedOnly.isNotEmpty() && listedOnly.size < wirePosts.size)
        assertTrue(render(extra).pages.first() is EditionPage.Front)

        // The schedule is as it was: the morning cutoff, today's paper, its counters, the next release.
        assertEquals(at(10, "07:00"), db.editionDao().getLatestByCutoffOf(listOf(EditionKind.MORNING, EditionKind.EVENING))!!.cutoffAt)
        assertEquals(morningItems, itemsOf(morning))
        assertEquals(morningRow, db.editionDao().getById(morning))
        morningItems.filter { it.kind != ItemKind.INDEX }.forEach { assertEquals(0, runs(it.articleId)) }
        assertEquals(nextRelease, publisher.nextReleaseAt())
        assertEquals(setOf(morning, extra), db.editionDao().getPending().map { it.id }.toSet())
        // It is on the stand as the newest edition, labelled by its kind.
        val summaries = RoomEditionRepository(db, publisher, clock).observeReleasedEditions().first()
        assertEquals(listOf(EditionKind.EXTRA, EditionKind.MORNING), summaries.map { it.kind })
        assertEquals(extra, publisher.ensureCurrentEdition())
        assertEquals(2, db.editionDao().count())

        // Asked again for a feed with nothing to print, the Extra is left exactly as it was.
        val another = addFeed("Another")
        clock.now = at(10, "11:00")
        assertNull(publisher.publishExtraEdition(setOf(another)))
        assertEquals(2, db.editionDao().count())
        assertEquals(edition, db.editionDao().getById(extra))
        assertEquals(extraPrinted, printedIn(extra))

        // Tomorrow morning: what the Extra printed is not new again; what it only listed is.
        // The reader turned through both papers.
        db.viewPages(morning, at(10, "10:30"))
        db.viewPages(extra, at(10, "10:30"))
        clock.now = at(11, "07:00")
        val next = publisher.ensureCurrentEdition()!!
        assertEquals(EditionKind.MORNING, db.editionDao().getById(next)!!.kind)
        assertEquals(3, db.editionDao().getById(next)!!.number)
        val nextItems = db.editionDao().getItems(next)
        val nextIds = nextItems.map { it.item.articleId }.toSet()
        assertTrue(elsewhere in nextIds)
        assertTrue(listedOnly.all { it in nextIds })
        assertTrue(wirePosts.filter { it in extraPrinted }.none { it in nextIds })
        // The favourite slow feed's untouched posts are held over, as after any edition.
        essayPosts.take(2).forEach { id ->
            assertTrue(nextItems.single { it.item.articleId == id }.item.reason!!.startsWith("Held over"))
        }
        // The window ran from Tuesday's morning cutoff, not from the Extra.
        assertTrue(db.articleDao().getById(elsewhere)!!.firstSeenAt.isBefore(edition.cutoffAt))
        // Both superseded editions are counted once.
        morningItems.filter { it.kind != ItemKind.INDEX }.forEach { assertEquals(1, runs(it.articleId)) }
        extraPrinted.forEach { assertEquals(1, runs(it)) }
        assertEquals(listOf(next), db.editionDao().getPending().map { it.id })
    }

    @Test
    fun anExtraEditionIsReprintedInPlaceWhenSourcesAreAddedLaterThatDay() = runTest {
        addPosts(6, at(10, "06:40"), "Tue")
        val morning = publisher.ensureCurrentEdition()!!
        val morningItems = itemsOf(morning)
        val morningRow = db.editionDao().getById(morning)
        val nextRelease = publisher.nextReleaseAt()

        // 10:00: a first Extra from two new sources.
        clock.now = at(10, "10:00")
        val essays = addFeed("New essays", CadenceTier.RARE)
        val essayPosts = (1L..3L).map { addPost(essays, clock.now, publishedAt = clock.now.minus(Duration.ofDays(it))) }
        val notes = addFeed("New notes", CadenceTier.WEEKLY)
        val notePosts = (1L..3L).map { addPost(notes, clock.now, publishedAt = clock.now.minus(Duration.ofHours(it))) }
        val extra = publisher.publishExtraEdition(setOf(essays, notes))!!
        val first = db.editionDao().getById(extra)!!
        val firstLead = itemsOf(extra).single { it.kind == ItemKind.LEAD }.articleId
        assertEquals((essayPosts + notePosts).toSet(), printedIn(extra))

        // The reader opens the Extra, reads one story to the end, Likes another and opens a third.
        clock.now = at(10, "10:30")
        open(extra)
        db.editionDao().markFinished(extra, clock.now)
        val finished = notePosts[0]
        db.engagementDao().markOpened(finished, clock.now)
        db.engagementDao().markRead(finished, clock.now)
        val liked = essayPosts[0]
        db.engagementDao().setLiked(liked, clock.now)
        val opened = notePosts[1]
        db.engagementDao().markOpened(opened, clock.now)

        // 12:00: another source is added and synced. Today already has an Extra: it is reprinted.
        clock.now = at(10, "12:00")
        val added = addFeed("Added later")
        val addedPosts = (1L..2L).map { addPost(added, clock.now, publishedAt = clock.now.minus(Duration.ofMinutes(it))) }
        val reprint = publisher.publishExtraEdition(setOf(added))

        // The same edition: same ID, number, date and kind; a new cutoff and release time.
        assertEquals(extra, reprint)
        assertEquals(2, db.editionDao().count())
        val second = db.editionDao().getById(extra)!!
        // It keeps when it was first opened and loses its "read" tick.
        assertEquals(
            first.copy(cutoffAt = clock.now, releaseAt = clock.now, builtAt = clock.now, openedAt = at(10, "10:30"), finishedAt = null),
            second,
        )
        // Its own feeds and the new one, set again as one paper.
        val printed = printedIn(extra)
        assertTrue(addedPosts.all { it in printed })
        assertTrue((essayPosts + notePosts - finished).all { it in printed })
        assertTrue("a story read to the end is not printed again", finished !in printed)
        assertEquals(1, itemsOf(extra).count { it.kind == ItemKind.LEAD })
        assertTrue(render(extra).pages.first() is EditionPage.Front)
        // What the reader did stays with the stories.
        assertNotNull(db.engagementDao().get(finished)!!.readAt)
        assertNotNull(db.engagementDao().get(liked)!!.likedAt)
        assertNotNull(db.engagementDao().get(opened)!!.openedAt)
        // A story that led both printings has led once.
        val lead = itemsOf(extra).single { it.kind == ItemKind.LEAD }.articleId
        assertEquals(1, db.engagementDao().get(lead)!!.leadCount)
        if (lead != firstLead) assertEquals(1, db.engagementDao().get(firstLead)!!.leadCount)

        // The schedule is as it was.
        assertEquals(morningItems, itemsOf(morning))
        assertEquals(morningRow, db.editionDao().getById(morning))
        assertEquals(nextRelease, publisher.nextReleaseAt())
        assertEquals(at(10, "07:00"), db.editionDao().getLatestByCutoffOf(listOf(EditionKind.MORNING, EditionKind.EVENING))!!.cutoffAt)
        assertEquals(setOf(morning, extra), db.editionDao().getPending().map { it.id }.toSet())
        (morningItems.map { it.articleId } + essayPosts + notePosts + addedPosts).forEach { assertEquals(0, runs(it)) }
        // It is the newest paper on the stand.
        assertEquals(extra, publisher.ensureCurrentEdition())

        // Tomorrow morning supersedes it once: untouched stories of the reprint count one run,
        // and the story the reprint dropped counts none.
        addPost(added, at(11, "06:00"))
        // The reader turned through the reprint (the first printing's views went with it).
        db.viewPages(extra, at(10, "12:30"))
        clock.now = at(11, "07:00")
        val next = publisher.ensureCurrentEdition()!!
        assertEquals(3, db.editionDao().getById(next)!!.number)
        addedPosts.forEach { assertEquals(1, runs(it)) }
        assertEquals(1, runs(essayPosts[1]))
        assertEquals(0, runs(finished))
        assertEquals("opened, so not left unopened", 0, runs(opened))
        // What the reprint printed is not new in the morning paper.
        val nextNew = db.editionDao().getItems(next).filter { it.item.reason?.startsWith("Held over") != true }.map { it.item.articleId }
        assertTrue(addedPosts.none { it in nextNew })
    }

    @Test
    fun aReprintThatDropsAStoryLeavesItWithoutARun() = runTest {
        addPosts(6, at(10, "06:40"), "Tue")
        publisher.ensureCurrentEdition()!!
        // A wire fills the first Extra's pages; most of its posts are only listed.
        clock.now = at(10, "10:00")
        val wire = addFeed("Wire", CadenceTier.FIREHOSE)
        val wirePosts = (0 until 60).map { addPost(wire, clock.now, publishedAt = clock.now.minus(Duration.ofMinutes(5L * it))) }
        val extra = publisher.publishExtraEdition(setOf(wire))!!
        val firstPrinted = printedIn(extra)

        // Favourites added later take places from it.
        clock.now = at(10, "12:00")
        val favourites = (0 until 12).map { addFeed("Favourite $it", CadenceTier.RARE, SliderStep.FAVOURITE) }
        favourites.forEach { addPost(it, clock.now) }
        assertEquals(extra, publisher.publishExtraEdition(favourites.toSet()))
        val dropped = firstPrinted - printedIn(extra)
        assertTrue("the reprint is a new printing, not an addition", dropped.isNotEmpty())
        assertTrue(wirePosts.containsAll(dropped))

        addPost(wire, at(11, "06:00"))
        // The reader turned through the reprint, so its stories count as seen.
        db.viewPages(extra, at(10, "12:30"))
        clock.now = at(11, "07:00")
        publisher.ensureCurrentEdition()!!
        dropped.forEach { assertEquals(0, runs(it)) }
    }

    @Test
    fun anExtraEditionWithNothingToPrintIsNotStored() = runTest {
        addPosts(6, at(10, "06:40"), "Tue")
        val morning = publisher.ensureCurrentEdition()!!
        clock.now = at(10, "10:00")
        val empty = addFeed("Empty")
        val alreadyPrinted = db.editionDao().getItems(morning).first { it.item.kind == ItemKind.LEAD }.row.article

        assertNull(publisher.publishExtraEdition(emptySet()))
        assertNull(publisher.publishExtraEdition(setOf(empty)))
        assertNull(publisher.publishExtraEdition(setOf(9999L)))
        // A feed whose recent posts today's paper already printed has nothing to add.
        assertNull(publisher.publishExtraEdition(setOf(alreadyPrinted.feedId)))
        assertEquals(1, db.editionDao().count())

        // With nothing stored, an Extra can still be printed later the same day.
        addPost(empty, at(10, "10:30"))
        clock.now = at(10, "11:00")
        assertNotNull(publisher.publishExtraEdition(setOf(empty)))
    }

    @Test
    fun anExtraEditionDoesNotCountTodaysPaperAgainstItsFeeds() = runTest {
        val feed = addFeed("Blog")
        (0 until 12).forEach { addPost(feed, at(10, "06:00"), publishedAt = at(10, "06:00").minus(Duration.ofHours(it.toLong()))) }
        publisher.ensureCurrentEdition()!!
        val before = db.feedDao().getSubscribed().single { it.id == feed }.affinity

        clock.now = at(10, "10:00")
        val added = addFeed("Added")
        addPost(added, clock.now)
        assertNotNull(publisher.publishExtraEdition(setOf(added)))

        assertEquals(before, db.feedDao().getSubscribed().single { it.id == feed }.affinity, 0.0)
    }
}
