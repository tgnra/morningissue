package net.tgnra.morningissue.data.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.HealthFlag
import net.tgnra.morningissue.model.ItemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** DAO tests on an in-memory database, run on the JVM with Robolectric. */
@RunWith(AndroidJUnit4::class)
class MorningIssueDatabaseTest {
    private lateinit var db: MorningIssueDatabase

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun feedUrlIsUnique() = runTest {
        val first = db.feedDao().insert(feed())
        val second = db.feedDao().insert(feed(title = "Same URL"))
        assertTrue(first > 0)
        assertEquals(-1L, second)
        assertEquals(1, db.feedDao().count())
    }

    @Test
    fun tagsAreSharedAndCleanedUp() = runTest {
        val a = db.feedDao().insert(feed("https://a.example/feed"))
        val b = db.feedDao().insert(feed("https://b.example/feed"))
        db.tagDao().setFeedTags(a, listOf("Tech", " personal ", "tech"))
        db.tagDao().setFeedTags(b, listOf("TECH"))

        assertEquals(listOf("personal", "Tech"), db.tagDao().getAll().map { it.name })
        assertEquals(listOf("Tech"), db.feedDao().getWithDetails(b)!!.tags.map { it.name })

        db.tagDao().setFeedTags(a, emptyList())
        assertEquals(listOf("Tech"), db.tagDao().getAll().map { it.name })
    }

    @Test
    fun articleGuidIsUniquePerFeed() = runTest {
        val a = db.feedDao().insert(feed("https://a.example/feed"))
        val b = db.feedDao().insert(feed("https://b.example/feed"))
        val dao = db.articleDao()

        assertNotNull(dao.insertNew(article(a, "1")))
        assertNull("same feed and GUID is skipped", dao.insertNew(article(a, "1", title = "Changed")))
        assertNotNull("same GUID in another feed is a different post", dao.insertNew(article(b, "1")))
        assertEquals(2, dao.count())
        assertEquals("Title 1", dao.getByGuid(a, "1")!!.title)
    }

    @Test
    fun firstSeenWindowIsHalfOpen() = runTest {
        val feedId = db.feedDao().insert(feed())
        val dao = db.articleDao()
        dao.insertNew(article(feedId, "at-previous-cutoff", firstSeenAt = T0))
        dao.insertNew(article(feedId, "inside", firstSeenAt = T0.plusSeconds(60)))
        dao.insertNew(article(feedId, "at-cutoff", firstSeenAt = T0.plusSeconds(120)))
        dao.insertNew(article(feedId, "after", firstSeenAt = T0.plusSeconds(121)))

        val rows = dao.firstSeenBetween(after = T0, upTo = T0.plusSeconds(120))

        assertEquals(listOf("inside", "at-cutoff"), rows.map { it.article.guid })
        assertEquals("Example", rows.first().feedTitle)
        assertNull("no engagement row yet", rows.first().openedAt)
    }

    @Test
    fun searchFindsTitleAndStoredBody() = runTest {
        val feedId = db.feedDao().insert(feed())
        val dao = db.articleDao()
        val byTitle = dao.insertNew(article(feedId, "1", title = "Kotlin coroutines explained"))!!
        val byBody = dao.insertNew(article(feedId, "2", title = "Weekly notes"), blocksJson = "[]", plainText = "nothing yet")!!
        dao.insertNew(article(feedId, "3", title = "Gardening"))

        dao.storeContent(byBody, "[]", "A long read about structured coroutines", 6, ContentSource.FETCHED, T0)

        val hits = dao.search("\"corout*\"", null, null, null, null, likedOnly = false, savedOnly = false, limit = 10)
        assertEquals(setOf(byTitle, byBody), hits.map { it.row.article.id }.toSet())
        assertEquals(6, dao.getById(byBody)!!.wordCount)
        assertEquals(ContentSource.FETCHED, dao.getContentSource(byBody))

        // Replaced body text is no longer indexed.
        val stale = dao.search("\"nothing*\"", null, null, null, null, likedOnly = false, savedOnly = false, limit = 10)
        assertTrue(stale.isEmpty())
    }

    @Test
    fun searchFilters() = runTest {
        val a = db.feedDao().insert(feed("https://a.example/feed"))
        val b = db.feedDao().insert(feed("https://b.example/feed"))
        db.tagDao().setFeedTags(a, listOf("Tech"))
        val dao = db.articleDao()
        val inA = dao.insertNew(article(a, "1", title = "Compose tips"))!!
        val inB = dao.insertNew(article(b, "1", title = "Compose music"))!!
        db.engagementDao().setLiked(inB, T0)

        suspend fun search(feedId: Long? = null, tag: String? = null, likedOnly: Boolean = false) =
            dao.search("\"compose*\"", feedId, tag, null, null, likedOnly, savedOnly = false, limit = 10)
                .map { it.row.article.id }

        assertEquals(setOf(inA, inB), search().toSet())
        assertEquals(listOf(inB), search(feedId = b))
        assertEquals(listOf(inA), search(tag = "tech"))
        assertEquals(listOf(inB), search(likedOnly = true))
    }

    @Test
    fun engagementKeepsFirstOpenAndFurthestRead() = runTest {
        val feedId = db.feedDao().insert(feed())
        val id = db.articleDao().insertNew(article(feedId, "1"))!!
        val dao = db.engagementDao()

        dao.markOpened(id, T0)
        dao.markOpened(id, T0.plusSeconds(60))
        dao.recordReadFraction(id, 0.8f)
        dao.recordReadFraction(id, 0.3f)
        dao.setSaved(id, T0)
        dao.setSaved(id, null)

        val row = dao.get(id)!!
        assertEquals(T0, row.openedAt)
        assertEquals(0.8f, row.readFraction)
        assertNull(row.savedAt)
        assertEquals(0, row.editionsRunUnopened)
    }

    @Test
    fun editionIsStoredWithItemsAndCover() = runTest {
        val feedId = db.feedDao().insert(feed(title = "Daring Fireball"))
        val lead = db.articleDao().insertNew(article(feedId, "lead", title = "The lead"))!!
        val opened = db.articleDao().insertNew(article(feedId, "opened"))!!
        val index = db.articleDao().insertNew(article(feedId, "index"))!!
        db.engagementDao().markOpened(opened, T0)

        val editionId = db.editionDao().insertEditionWithItems(
            EditionEntity(
                date = LocalDate.of(2026, 11, 10),
                kind = EditionKind.MORNING,
                cutoffAt = T0.minusSeconds(5400),
                releaseAt = T0,
                builtAt = T0.minusSeconds(5400),
                length = EditionLength.STANDARD,
                number = 1,
            ),
            listOf(
                EditionItemEntity(0, lead, page = 1, slot = 0, kind = ItemKind.LEAD, score = 0.9, reason = "Rare post"),
                EditionItemEntity(0, opened, page = 1, slot = 1, kind = ItemKind.FRONT, score = 0.5),
                EditionItemEntity(0, index, page = 2, slot = 0, kind = ItemKind.INDEX, score = 0.1),
            ),
        )

        val cover = db.editionDao().observeCovers().first().single()
        assertEquals(editionId, cover.edition.id)
        assertEquals("The lead", cover.leadTitle)
        assertEquals("Daring Fireball", cover.leadFeedTitle)
        assertEquals(2, cover.storyCount)

        val items = db.editionDao().getItems(editionId)
        assertEquals(listOf(lead, opened, index), items.map { it.item.articleId })
        assertEquals("Rare post", items.first().item.reason)
        assertEquals(LocalDate.of(2026, 11, 10), db.editionDao().getById(editionId)!!.date)

        // Only printed, never-opened stories count as an unopened run.
        db.engagementDao().countUnopenedRun(editionId)
        assertEquals(1, db.engagementDao().get(lead)!!.editionsRunUnopened)
        assertEquals(0, db.engagementDao().get(opened)!!.editionsRunUnopened)
        assertNull(db.engagementDao().get(index))

        assertEquals(1, db.editionDao().getRunsSince(T0).count { it.openedAt != null })
    }

    @Test
    fun removingFeedRemovesEverythingUnderIt() = runTest {
        val feedId = db.feedDao().insert(feed())
        val id = db.articleDao().insertNew(article(feedId, "1"), blocksJson = "[]", plainText = "")!!
        db.engagementDao().markOpened(id, T0)
        db.feedHealthDao().recordError(feedId, T0, 500)
        db.feedHealthDao().setFlag(feedId, HealthFlag.BROKEN)
        assertEquals(1, db.feedHealthDao().get(feedId)!!.errorStreak)

        db.feedDao().delete(feedId)

        assertEquals(0, db.articleDao().count())
        assertNull(db.articleDao().getContent(id))
        assertNull(db.engagementDao().get(id))
        assertNull(db.feedHealthDao().get(feedId))
    }
}
