package net.tgnra.morningissue.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.T0
import net.tgnra.morningissue.data.db.article
import net.tgnra.morningissue.edition.StoredEditionPublisher
import net.tgnra.morningissue.model.AddFeedResult
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EditionPage
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.NewFeed
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.model.SearchQuery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRepositoriesTest {
    private val clock = Clock.fixed(T0, ZoneOffset.UTC)
    private lateinit var db: MorningIssueDatabase
    private lateinit var feeds: RoomFeedRepository
    private lateinit var articles: RoomArticleRepository
    private lateinit var editions: RoomEditionRepository

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        feeds = RoomFeedRepository(db, clock)
        articles = RoomArticleRepository(db, clock)
        editions = RoomEditionRepository(db, StoredEditionPublisher(db.editionDao(), clock), clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun addFeedSkipsSameNormalisedUrl() = runTest {
        val added = feeds.addFeed(NewFeed("https://www.example.com/feed/", tags = listOf("Tech")))
        val again = feeds.addFeed(NewFeed("http://example.com/feed", title = "Other"))

        assertTrue(added is AddFeedResult.Added)
        assertEquals(AddFeedResult.AlreadySubscribed(added.feedId), again)
        val feed = feeds.getFeeds().single()
        assertEquals("example.com", feed.title)
        assertEquals(listOf("Tech"), feed.tags)
        assertEquals(T0, feed.addedAt)

        feeds.removeFeed(feed.id)
        assertTrue(feeds.getFeeds().isEmpty())
        assertTrue(feeds.observeTags().first().isEmpty())
    }

    @Test
    fun readerArticleFallsBackToSummaryUntilContentIsStored() = runTest {
        val feedId = feeds.addFeed(NewFeed("https://example.com/feed", title = "Example")).feedId
        val id = db.articleDao().insertNew(article(feedId, "1", summary = "A short summary"))!!

        val before = articles.getReaderArticle(id)!!
        assertNull(before.contentSource)
        assertEquals(listOf(ContentBlock.Paragraph(RichText("A short summary"))), before.blocks)

        val blocks = listOf(ContentBlock.Heading(2, RichText("Part one")), ContentBlock.Paragraph(RichText("Full text here")))
        val text = ContentBlocks.plainText(blocks)
        db.articleDao().storeContent(id, ContentBlocks.encode(blocks), text, ContentBlocks.wordCount(text), ContentSource.FETCHED, T0)

        val after = articles.getReaderArticle(id)!!
        assertEquals(ContentSource.FETCHED, after.contentSource)
        assertEquals(blocks, after.blocks)
        assertEquals(5, after.wordCount)
        assertNull(articles.getReaderArticle(id + 1))
    }

    @Test
    fun readingStateIsRecorded() = runTest {
        val feedId = feeds.addFeed(NewFeed("https://example.com/feed")).feedId
        val id = db.articleDao().insertNew(article(feedId, "1", title = "Compose tips"))!!

        articles.setReadFraction(id, 0.4f)
        assertFalse(articles.observeFeedArticles(feedId).first().single().isRead)

        articles.markOpened(id)
        articles.setReadFraction(id, 0.75f)
        articles.setLiked(id, true)
        articles.setSaved(id, true)
        articles.setSaved(id, false)

        val summary = articles.search(SearchQuery("compose tip")).single()
        assertTrue(summary.isOpened && summary.isRead && summary.isLiked)
        assertFalse(summary.isSaved)
        assertEquals(listOf(id), articles.observeLiked().first().map { it.articleId })
        assertTrue(articles.observeSaved().first().isEmpty())
        assertTrue(articles.search(SearchQuery("  ")).isEmpty())
    }

    @Test
    fun editionIsLaidOutAsPagesAndHiddenUntilReleased() = runTest {
        val a = feeds.addFeed(NewFeed("https://a.example/feed", title = "A")).feedId
        val b = feeds.addFeed(NewFeed("https://b.example/feed", title = "B")).feedId
        val ids = (1..7).map { db.articleDao().insertNew(article(if (it % 2 == 0) b else a, "$it"))!! }
        fun item(n: Int, page: Int, slot: Int, kind: ItemKind, section: String? = null) =
            EditionItemEntity(0, ids[n], page = page, section = section, slot = slot, kind = kind, score = 1.0 - n / 10.0)
        fun edition(date: LocalDate, number: Int, releaseOffsetSeconds: Long) = EditionEntity(
            date = date,
            kind = EditionKind.MORNING,
            cutoffAt = T0.plusSeconds(releaseOffsetSeconds - 5400),
            releaseAt = T0.plusSeconds(releaseOffsetSeconds),
            builtAt = T0.plusSeconds(releaseOffsetSeconds - 5400),
            length = EditionLength.STANDARD,
            number = number,
        )

        val today = db.editionDao().insertEditionWithItems(
            edition(LocalDate.of(2026, 11, 10), 1, 0),
            listOf(
                // Deliberately out of order: slot and page decide, not insertion order.
                item(1, page = 1, slot = 1, kind = ItemKind.FRONT),
                item(0, page = 1, slot = 0, kind = ItemKind.LEAD),
                item(2, page = 2, slot = 0, kind = ItemKind.TOP),
                item(3, page = 4, slot = 0, kind = ItemKind.SECTION, section = "Tech"),
                item(4, page = 5, slot = 0, kind = ItemKind.BRIEF),
                item(5, page = 9, slot = 1, kind = ItemKind.INDEX),
                item(6, page = 9, slot = 0, kind = ItemKind.INDEX),
            ),
        )
        db.editionDao().insertEditionWithItems(edition(LocalDate.of(2026, 11, 11), 2, 86_400), emptyList())

        val released = editions.observeReleasedEditions().first()
        assertEquals("tomorrow's edition is built but not released", listOf(today), released.map { it.id })
        assertEquals(5, released.single().storyCount)
        assertEquals("Title 1", released.single().leadHeadline)
        assertEquals(today, editions.ensureCurrentEdition())

        val edition = editions.getEdition(today)!!
        assertEquals(
            listOf(
                EditionPage.Front::class,
                EditionPage.TopStories::class,
                EditionPage.Section::class,
                EditionPage.InBrief::class,
                EditionPage.Back::class,
            ),
            edition.pages.map { it::class },
        )
        assertEquals(listOf(1, 2, 3, 4, 5), edition.pages.map { it.number })
        val front = edition.pages[0] as EditionPage.Front
        assertEquals(ids[0], front.lead!!.articleId)
        assertEquals(listOf(ids[1]), front.secondaries.map { it.articleId })
        assertEquals("Tech", (edition.pages[2] as EditionPage.Section).name)
        val back = edition.pages.last() as EditionPage.Back
        assertEquals(listOf("A" to listOf(ids[6]), "B" to listOf(ids[5])), back.alsoInYourFeeds.map { g -> g.feedTitle to g.stories.map { it.articleId } })

        assertFalse(edition.isRead)
        editions.markEditionFinished(today)
        assertTrue(editions.getEdition(today)!!.isRead)
        assertTrue(editions.observeReleasedEditions().first().single().isRead)
        assertNull(editions.getEdition(999))
    }
}
