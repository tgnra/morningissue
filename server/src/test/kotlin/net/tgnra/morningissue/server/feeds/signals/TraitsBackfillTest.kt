package net.tgnra.morningissue.server.feeds.signals

import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.server.db.SignalDao
import net.tgnra.morningissue.server.db.ArticleTraitsEntity
import net.tgnra.morningissue.server.feeds.exec
import net.tgnra.morningissue.server.feeds.feedEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.feeds.T0
import net.tgnra.morningissue.server.feeds.article
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.ArticleTraits
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.model.InlineSpan
import net.tgnra.morningissue.model.InlineStyle
import net.tgnra.morningissue.model.RichText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The backfill against an in-memory database holding posts as version 1 stored them: no traits. */
class TraitsBackfillTest {
    private lateinit var db: ServerDatabase
    private lateinit var dao: SignalDao

    @Before
    fun setUp() {
        db = ServerDatabase.inMemory()
        dao = db.signalDao()
    }

    @After
    fun tearDown() = db.close()

    private fun linked(text: String, href: String) = ContentBlock.Paragraph(RichText(text, listOf(InlineSpan(0, 4, InlineStyle.LINK, href))))

    private suspend fun blog(): Long =
        db.feedDao().insert(feedEntity("https://blog.example/feed.xml", "A blog", T0, siteUrl = "https://blog.example/"))

    private suspend fun post(feedId: Long, guid: String, title: String, url: String?, blocks: List<ContentBlock>? = null): Long {
        val row = article(feedId, guid, title = title).copy(url = url)
        val text = blocks?.let(ContentBlocks::plainText)
        return db.articleDao().insertNew(row, blocks?.let(ContentBlocks::encode), text)!!
    }

    @Test
    fun computesTraitsFromTheStoredTitleLinkSiteAndBody() = runBlocking<Unit> {
        val feedId = blog()
        val essay = post(
            feedId, "essay", "An essay", "https://blog.example/essay",
            listOf(
                linked("Text with a link out", "https://one.example/"),
                linked("More text, another link", "https://two.example/"),
                ContentBlock.Image("https://blog.example/a.png"),
                ContentBlock.Preformatted("val x = 1"),
            ),
        )
        val pointer = post(
            feedId, "pointer", "Links: something good", "https://blog.example/links/1",
            listOf(linked("Read this piece.", "https://elsewhere.example/piece")),
        )
        val outbound = post(feedId, "outbound", "Show HN: A thing", "https://thing.example/")

        assertEquals(3, TraitsBackfill(db).run())

        assertEquals(ArticleTraits(2, 1, true, EnclosureKind.NONE, isLinkPost = false, titlePrefix = null), dao.getTraits(essay)!!.toTraits())
        assertEquals(ArticleTraits(1, 0, false, EnclosureKind.NONE, isLinkPost = true, titlePrefix = "links"), dao.getTraits(pointer)!!.toTraits())
        // No stored body: the title and the link are all there is to go by.
        assertEquals(ArticleTraits(0, 0, false, EnclosureKind.NONE, isLinkPost = true, titlePrefix = "show hn"), dao.getTraits(outbound)!!.toTraits())
        assertEquals(ArticleTraitRules.VERSION, dao.getTraits(essay)!!.traitsVersion)
    }

    @Test
    fun usesFetchedFullTextWhenThatIsWhatIsStoredAndTheBareTitleOnceTheBodyIsTrimmed() = runBlocking<Unit> {
        val feedId = blog()
        val id = post(feedId, "a", "A post", "https://blog.example/a", listOf(linked("An excerpt…", "https://blog.example/a")))
        val full = listOf(linked("Full text", "https://one.example/"), linked("goes on", "https://two.example/"), ContentBlock.Image("https://blog.example/i.png"))
        db.articleDao().storeContent(id, ContentBlocks.encode(full), ContentBlocks.plainText(full), 4, ContentSource.FETCHED, T0)
        val trimmed = post(feedId, "b", "Podcast: Episode 3", "https://blog.example/b", listOf(linked("Show notes", "https://one.example/")))
        // As the app's retention trim leaves it: the row without its body.
        db.exec("DELETE FROM article_content WHERE articleId = $trimmed")

        assertEquals(2, TraitsBackfill(db).run())
        assertEquals(ArticleTraits(2, 1, false, EnclosureKind.NONE, isLinkPost = false, titlePrefix = null), dao.getTraits(id)!!.toTraits())
        assertEquals(ArticleTraits(0, 0, false, EnclosureKind.NONE, isLinkPost = false, titlePrefix = "podcast"), dao.getTraits(trimmed)!!.toTraits())
    }

    @Test
    fun aSecondPassDoesNothingAndLaterPostsArePickedUp() = runBlocking<Unit> {
        val feedId = blog()
        val first = post(feedId, "a", "One", "https://blog.example/a")
        assertEquals(1, TraitsBackfill(db).run())
        val before = dao.getTraits(first)

        assertEquals(0, TraitsBackfill(db).run())
        assertEquals(before, dao.getTraits(first))

        post(feedId, "b", "Two", "https://blog.example/b")
        assertEquals(1, TraitsBackfill(db).run())
        assertEquals(2, dao.traitsCount())
    }

    @Test
    fun worksThroughALibraryLargerThanOneBatch() = runBlocking<Unit> {
        val feedId = blog()
        val ids = (1..7).map { post(feedId, "p$it", "Note: number $it", "https://blog.example/$it") }
        assertEquals(7, TraitsBackfill(db, batchSize = 3).run())
        assertEquals(7, dao.traitsCount())
        ids.forEach { assertEquals("note", dao.getTraits(it)!!.titlePrefix) }
    }

    @Test
    fun rowsFromOlderRulesAreRecomputedAndKeepTheirEnclosure() = runBlocking<Unit> {
        val feedId = blog()
        val outdated = post(feedId, "old", "Podcast: Episode 1", "https://blog.example/1", listOf(linked("Show notes", "https://blog.example/notes")))
        val pending = post(feedId, "imported", "Episode 2", "https://blog.example/2")
        val current = post(feedId, "current", "Links: today", "https://blog.example/3")
        // Written by rules that no longer hold: every number is wrong, the enclosure is right.
        dao.upsertTraits(ArticleTraitsEntity(outdated, ArticleTraitRules.VERSION - 1, 99, 99, true, EnclosureKind.AUDIO, true, "stale"))
        dao.insertPendingEnclosure(pending, EnclosureKind.VIDEO)
        // A current row is trusted as it stands, even where the backfill would compute otherwise.
        val trusted = SignalDao.traitsRow(current, ArticleTraits(5, 5, true, EnclosureKind.OTHER, false, "kept"))
        dao.upsertTraits(trusted)

        assertEquals(2, TraitsBackfill(db).run())
        assertEquals(
            ArticleTraitsEntity(outdated, ArticleTraitRules.VERSION, 1, 0, false, EnclosureKind.AUDIO, false, "podcast"),
            dao.getTraits(outdated),
        )
        assertEquals(ArticleTraitsEntity(pending, ArticleTraitRules.VERSION, 0, 0, false, EnclosureKind.VIDEO, false, null), dao.getTraits(pending))
        assertEquals(trusted, dao.getTraits(current))
    }

    @Test
    fun aFeedWithoutASiteAddressAndABodyThatCannotBeReadStillGetARow() = runBlocking<Unit> {
        val feedId = db.feedDao().insert(feedEntity("https://bare.example/feed", "Bare", T0))
        val id = db.articleDao().insertNew(article(feedId, "a", title = "Plain").copy(url = null), "not json at all", "text")!!
        assertEquals(1, TraitsBackfill(db).run())
        assertEquals(ArticleTraits(0, 0, false, EnclosureKind.NONE, isLinkPost = false, titlePrefix = null), dao.getTraits(id)!!.toTraits())
        assertNull(dao.getTraits(id)!!.titlePrefix)
    }
}
