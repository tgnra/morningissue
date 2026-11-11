package net.tgnra.morningissue.data.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.T0
import net.tgnra.morningissue.data.db.article
import net.tgnra.morningissue.data.db.feed
import net.tgnra.morningissue.edition.FakeSettings
import net.tgnra.morningissue.edition.MutableClock
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.RichText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageRepositoryTest {
    private val clock = MutableClock(T0)
    private val settings = FakeSettings(AppSettings(storageBudgetMb = AppSettings.MIN_STORAGE_BUDGET_MB))
    private lateinit var db: MorningIssueDatabase
    private lateinit var storage: RoomStorageRepository
    private var feedId = 0L

    /** About a megabyte of body per article, so a 50 MB budget is a manageable number of them. */
    private val word = "zeppelin"
    private val bodyText = (word + " ").repeat(1024 * 1024 / (word.length + 1) / 2)
    private val blocksJson = ContentBlocks.encode(listOf(ContentBlock.Paragraph(RichText(bodyText))))

    @Before
    fun setUp() = runBlocking {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        storage = RoomStorageRepository(db, settings, clock)
        feedId = db.feedDao().insert(feed())
    }

    @After
    fun tearDown() = db.close()

    private fun day(n: Int): Instant = T0.minus(Duration.ofDays(100L - n))

    /** A post first seen on [day] with a body of about a megabyte (half in content, half in the index). */
    private suspend fun post(guid: String, day: Int, title: String = "Title $guid"): Long =
        db.articleDao().insertNew(article(feedId, guid, title = title, summary = "Summary $guid", firstSeenAt = day(day)), blocksJson, bodyText)!!

    private suspend fun edition(day: Int, vararg articleIds: Long): Long = db.editionDao().insertEditionWithItems(
        EditionEntity(
            date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()), kind = EditionKind.MORNING, cutoffAt = day(day),
            releaseAt = day(day), builtAt = day(day), length = EditionLength.STANDARD, number = day,
        ),
        articleIds.mapIndexed { slot, id -> EditionItemEntity(0, id, 1, null, slot, if (slot == 0) ItemKind.LEAD else ItemKind.FRONT, 1.0) },
    )

    private suspend fun hasBody(articleId: Long) = db.articleDao().getContent(articleId) != null

    private suspend fun search(term: String): Set<Long> =
        db.articleDao().search("\"$term*\"", null, null, null, null, likedOnly = false, savedOnly = false, limit = 500)
            .map { it.row.article.id }.toSet()

    @Test
    fun underBudgetNothingIsTrimmed() = runBlocking<Unit> {
        val ids = (1..10).map { post("p$it", it) }
        val usage = storage.usage()
        assertEquals(50L * 1024 * 1024, usage.budgetBytes)
        assertTrue("about 10 MB: ${usage.usedBytes}", usage.usedBytes in 9_000_000L..11_500_000L)
        assertEquals(TrimReport(), storage.trimToBudget())
        assertTrue(ids.all { hasBody(it) })
    }

    @Test
    fun overBudgetTheOldestEditionsPostsAreTrimmedFirstAndLikedAndSavedAreKept() = runBlocking<Unit> {
        // 60 posts of about 1 MB over 60 days, oldest first; the budget is 50 MB.
        val ids = (1..60).map { post("p$it", it) }
        val liked = ids[0]
        val saved = ids[1]
        db.engagementDao().setLiked(liked, T0)
        db.engagementDao().setSaved(saved, T0)
        // Opened and read, but neither Liked nor saved: not protected.
        db.engagementDao().markRead(ids[2], T0)
        (1..60).forEach { edition(it, ids[it - 1]) }

        assertTrue(storage.usage().overBudget)
        val report = storage.trimToBudget()

        // Down to 90% of the budget: 45 MB of 60, so about 15 posts went.
        val usage = storage.usage()
        assertTrue("under the target: ${usage.usedBytes}", usage.usedBytes <= 45L * 1024 * 1024)
        assertTrue("and not far under it: ${usage.usedBytes}", usage.usedBytes > 43L * 1024 * 1024)
        assertTrue("${report.articlesTrimmed}", report.articlesTrimmed in 15..17)
        assertTrue(report.bytesFreed > 14L * 1024 * 1024)

        // Liked and saved are the two oldest posts and still have their text.
        assertTrue(hasBody(liked))
        assertTrue(hasBody(saved))
        // The trimmed ones are exactly the oldest unprotected posts, with no gaps.
        val trimmed = ids.filterNot { hasBody(it) }
        assertEquals(ids.drop(2).take(report.articlesTrimmed), trimmed)

        // A second pass has nothing to do.
        assertEquals(TrimReport(), storage.trimToBudget())
    }

    @Test
    fun aTrimmedPostKeepsItsHeadlineSummaryAndEditionAndLosesItsBodyFromSearchToo() = runBlocking<Unit> {
        val ids = (1..60).map { post("p$it", it, title = "Headline number$it") }
        (1..60).forEach { edition(it, ids[it - 1]) }
        assertEquals(60, search(word).size)

        storage.trimToBudget()
        val trimmed = ids.filterNot { hasBody(it) }
        assertTrue(trimmed.isNotEmpty())
        val gone = trimmed.first()

        // The row, its headline and summary, and its place in its edition are untouched.
        val article = db.articleDao().getById(gone)!!
        assertEquals("Headline number1", article.title)
        assertEquals("Summary p1", article.summary)
        assertEquals(60, db.editionDao().count())
        assertEquals(1, db.editionDao().getItemsForArticle(gone).size)
        assertNull(db.articleDao().getContentSource(gone))

        // Search: the body no longer matches for trimmed posts, the headline and summary still do.
        assertEquals(ids.toSet() - trimmed.toSet(), search(word))
        assertTrue(gone in search("number1"))
        assertTrue(gone in search("Summary"))
        assertEquals(60, search("Headline").size)

        // The index holds no more body text than the content table does.
        assertEquals((60 - trimmed.size).toLong() * bodyText.length, db.articleDao().indexedBodySize())

        // Reading it again stores the text and makes it searchable as before.
        db.articleDao().storeContent(gone, blocksJson, bodyText, 10, ContentSource.FETCHED, T0)
        assertTrue(gone in search(word))
    }

    @Test
    fun theNewestEditionsAndPostsWaitingForTheNextOneAreNeverTrimmed() = runBlocking<Unit> {
        val old = (1..3).map { post("old$it", it) }
        // A held-over post: first seen long ago, printed in the latest edition.
        val heldOver = post("held", 4)
        val previous = post("previous", 97)
        val latest = post("latest", 98)
        val waiting = post("waiting", 99)
        edition(5, old[0], old[1], old[2])
        edition(97, previous)
        edition(98, latest, heldOver)

        val report = storage.trimAll()
        assertEquals(3, report.articlesTrimmed)
        assertTrue(old.none { hasBody(it) })
        for (kept in listOf(heldOver, previous, latest, waiting)) assertNotNull(db.articleDao().getContent(kept))

        // Free up space never touches Liked or saved posts either.
        // With its edition gone, the saved post has only its save to protect it.
        db.editionDao().delete(db.editionDao().getRecent(3)[1].id)
        db.engagementDao().setSaved(previous, T0)
        assertEquals(0, storage.trimAll().articlesTrimmed)
    }

    @Test
    fun loweringTheBudgetTrimsDownToIt() = runBlocking<Unit> {
        settings.setStorageBudgetMb(100)
        val ids = (1..60).map { post("p$it", it) }
        (1..60).forEach { edition(it, ids[it - 1]) }
        assertEquals(TrimReport(), storage.trimToBudget())

        settings.setStorageBudgetMb(50)
        assertTrue(storage.trimToBudget().articlesTrimmed > 0)
        assertTrue(!storage.usage().overBudget)
    }
}
