package net.tgnra.morningissue.data.archive

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.data.db.ArticleSignalDao
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.T0
import net.tgnra.morningissue.data.db.article
import net.tgnra.morningissue.edition.MutableClock
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.ArticleCategories
import net.tgnra.morningissue.model.ArticleTraits
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.ReleaseChange
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.model.SliderStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The archive export: a copy of the library to keep, and the upload of an earlier version's library to the server. */
@RunWith(AndroidJUnit4::class)
class ArchiveTransferTest {
    private val clock = MutableClock(T0)
    private lateinit var source: MorningIssueDatabase

    @Before
    fun setUp() {
        source = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        source.close()
    }

    private fun day(n: Long): Instant = T0.minus(Duration.ofDays(30 - n))

    private fun body(text: String) = ContentBlocks.encode(listOf(ContentBlock.Paragraph(RichText(text))))

    private class Library(val blog: Long, val news: Long, val posts: Map<String, Long>, val editions: List<Long>)

    /**
     * Two feeds (one tagged, on Favourite), 12 posts, three editions. `blog-1` is Liked and
     * read, `news-1` saved, `news-2` opened; `blog-2` has fetched full text; `news-5` has no body.
     */
    private suspend fun seed(db: MorningIssueDatabase, posts: Int = 6): Library {
        val blog = db.feedDao().insert(
            FeedEntity(url = "https://blog.example/feed.xml", title = "A Blog", siteUrl = "https://blog.example/", sliderStep = SliderStep.FAVOURITE, addedAt = day(0)),
        )
        val news = db.feedDao().insert(FeedEntity(url = "http://www.news.example/rss", title = "The News", addedAt = day(1)))
        db.tagDao().setFeedTags(blog, listOf("Tech", "Essays"))
        val ids = LinkedHashMap<String, Long>()
        for ((feedId, name) in listOf(blog to "blog", news to "news")) {
            for (n in 1..posts) {
                val guid = "$name-$n"
                val row = article(feedId, guid, title = "Headline $guid", summary = "Summary $guid", firstSeenAt = day(n.toLong()))
                val text = "Body of $guid with the word marmalade"
                ids[guid] = if (guid == "news-5") db.articleDao().insertNew(row)!! else db.articleDao().insertNew(row, body(text), text)!!
            }
        }
        db.articleDao().storeContent(ids.getValue("blog-2"), body("Full text of blog-2 quince"), "Full text of blog-2 quince", 5, ContentSource.FETCHED, day(3))
        db.engagementDao().apply {
            markOpened(ids.getValue("blog-1"), day(2))
            recordReadFraction(ids.getValue("blog-1"), 0.9f)
            markRead(ids.getValue("blog-1"), day(2))
            setLiked(ids.getValue("blog-1"), day(2))
            setSaved(ids.getValue("news-1"), day(3))
            markOpened(ids.getValue("news-2"), day(4))
        }
        val editions = (1L..3L).map { n ->
            val printed = listOf(ids.getValue("blog-$n"), ids.getValue("news-$n"))
            val index = if (n == 3L) listOf(ids.getValue("news-4")) else emptyList()
            db.editionDao().insertEditionWithItems(
                EditionEntity(
                    date = LocalDate.of(2026, 10, 10).plusDays(n), kind = EditionKind.MORNING, cutoffAt = day(n), releaseAt = day(n),
                    builtAt = day(n).minusSeconds(5400), length = EditionLength.STANDARD, number = n.toInt(),
                    openedAt = day(n).plusSeconds(60), finishedAt = if (n == 1L) day(n).plusSeconds(600) else null,
                ),
                printed.mapIndexed { slot, id ->
                    EditionItemEntity(0, id, 1, null, slot, if (slot == 0) ItemKind.LEAD else ItemKind.FRONT, 1.0 - slot * 0.25, "Reason $slot")
                } + index.map { EditionItemEntity(0, it, 2, null, 0, ItemKind.INDEX, 0.1) },
            )
        }
        db.engagementDao().countUnopenedRun(editions[0])
        return Library(blog, news, ids, editions)
    }

    private suspend fun export(db: MorningIssueDatabase): Pair<ByteArray, ExportReport> {
        val out = ByteArrayOutputStream()
        val report = RoomArchiveTransfer(db, clock).export(out)
        return out.toByteArray() to report
    }

    private fun category(name: String) = ArticleCategories.of(name)!!

    @Test
    fun theFileIsAVersionedZipOfJsonLines() = runBlocking<Unit> {
        seed(source)
        val (file, _) = export(source)
        val entries = LinkedHashMap<String, List<String>>()
        ZipInputStream(ByteArrayInputStream(file)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().decodeToString().trim().lines() }
        }
        assertEquals(listOf("manifest.json", "feeds.jsonl", "posts.jsonl", "editions.jsonl"), entries.keys.toList())
        val manifest = ArchiveFormat.json.decodeFromString<ArchiveManifest>(entries.getValue("manifest.json").single())
        assertEquals(ArchiveManifest("morning-issue-archive", 2, T0.toEpochMilli(), feeds = 2, posts = 12, editions = 3), manifest)
        assertEquals(2, entries.getValue("feeds.jsonl").size)
        assertEquals(12, entries.getValue("posts.jsonl").size)
        assertEquals(3, entries.getValue("editions.jsonl").size)
    }

    @Test
    fun aPostsLineCarriesItsCategoriesAndEnclosure() = runBlocking<Unit> {
        val library = seed(source)
        val id = library.posts.getValue("blog-1")
        source.articleSignalDao().addCategories(id, listOf(category("Tech")))
        source.articleSignalDao().upsertTraits(ArticleSignalDao.traitsRow(id, ArticleTraits(0, 0, false, EnclosureKind.VIDEO, false, null)))
        val (file, _) = export(source)
        val posts = ZipInputStream(ByteArrayInputStream(file)).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "posts.jsonl" }
            zip.readBytes().decodeToString().trim().lines().map { ArchiveFormat.json.decodeFromString<ArchivePost>(it) }
        }
        val blog = posts.single { it.guid == "blog-1" }
        assertEquals(listOf(ArchiveCategory("tech", "Tech")), blog.categories)
        assertEquals("VIDEO", blog.enclosure)
        val news = posts.single { it.guid == "news-1" }
        assertTrue(news.categories.isEmpty())
        assertNull(news.enclosure)
    }

    @Test
    fun theExportCarriesMutesPageViewsAndTheSettingsForTheServer() = runBlocking<Unit> {
        val library = seed(source)
        source.feedDao().updateMuteUntil(library.news, day(40))
        source.editionDao().recordPageView(library.editions[0], 1, 4_000, day(1).plusSeconds(70))
        source.editionDao().recordPageView(library.editions[0], 1, 1_500, day(1).plusSeconds(90))
        val settings = AppSettings(
            resetTime = LocalTime.of(5, 45), editionLength = EditionLength.LONG, eveningEditionEnabled = true,
            onboardingDone = true, nothingNewFor = listOf(day(2)),
            resetChange = ReleaseChange(settled = day(1), kept = null, from = day(3)),
        )
        val out = ByteArrayOutputStream()
        RoomArchiveTransfer(source, clock, settings = { settings }, zone = { ZoneId.of("Europe/London") }).export(out)
        val entries = LinkedHashMap<String, List<String>>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().decodeToString().trim().lines() }
        }

        val feeds = entries.getValue("feeds.jsonl").map { ArchiveFormat.json.decodeFromString<ArchiveFeed>(it) }
        assertEquals(day(40).toEpochMilli(), feeds.single { it.n == library.news }.muteUntil)
        assertNull(feeds.single { it.n == library.blog }.muteUntil)

        val editions = entries.getValue("editions.jsonl").map { ArchiveFormat.json.decodeFromString<ArchiveEdition>(it) }
        val first = editions.single { it.number == 1 }
        assertEquals(false, first.pageViewsTracked)
        assertEquals(listOf(ArchivePageView(1, 5_500, day(1).plusSeconds(70).toEpochMilli())), first.pageViews)
        assertNull(editions.single { it.number == 2 }.pageViews)

        val written = ArchiveFormat.json.decodeFromString<ArchiveSettings>(entries.getValue("settings.json").single())
        assertEquals(
            ArchiveSettings(
                resetTime = "05:45", editionLength = "LONG", eveningEnabled = true, eveningTime = "18:00",
                timeZone = "Europe/London", onboardingDone = true, nothingNewFor = listOf(day(2).toEpochMilli()),
                resetChange = ArchiveReleaseChange(settled = day(1).toEpochMilli(), kept = null, from = day(3).toEpochMilli()),
            ),
            written,
        )
        assertEquals(settings.resetChange, written.resetChange!!.toReleaseChange())
    }
}
