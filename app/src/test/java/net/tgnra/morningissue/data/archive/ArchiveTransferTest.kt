package net.tgnra.morningissue.data.archive

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.data.db.ArticleEmbeddingEntity
import net.tgnra.morningissue.data.db.ArticleSignalDao
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.T0
import net.tgnra.morningissue.data.db.article
import net.tgnra.morningissue.data.signals.TraitsBackfill
import net.tgnra.morningissue.data.storage.RoomStorageRepository
import net.tgnra.morningissue.edition.FakeSettings
import net.tgnra.morningissue.edition.MutableClock
import net.tgnra.morningissue.model.ArticleCategories
import net.tgnra.morningissue.model.ArticleCategory
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.ArticleTraits
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.model.SliderStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import net.tgnra.morningissue.model.FeedStatus
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveTransferTest {
    private val clock = MutableClock(T0)
    private lateinit var source: MorningIssueDatabase
    private lateinit var target: MorningIssueDatabase

    @Before
    fun setUp() {
        source = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        target = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
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

    private suspend fun import(db: MorningIssueDatabase, file: ByteArray): ImportReport =
        RoomArchiveTransfer(db, clock).import({ ByteArrayInputStream(file) })

    private suspend fun search(db: MorningIssueDatabase, term: String): Int =
        db.articleDao().search("\"$term*\"", null, null, null, null, likedOnly = false, savedOnly = false, limit = 100).size

    /** Everything that should survive a round trip, keyed without database IDs. */
    private suspend fun snapshot(db: MorningIssueDatabase): Map<String, Any?> {
        val feeds = db.feedDao().getSubscribedWithDetails()
        val urlOf = feeds.associate { it.feed.id to it.feed.url }
        val articles = db.archiveDao().articlesAfter(0, 500)
        val keyOf = articles.associate { it.id to "${urlOf[it.feedId]}#${it.guid}" }
        return mapOf(
            "feeds" to feeds.map { Triple(it.feed.url, it.feed.title, it.tags.map { tag -> tag.name }.sorted() to it.feed.sliderStep) }.sortedBy { it.first },
            "articles" to articles.map { it.copy(id = 0, feedId = 0) to keyOf[it.id] }.sortedBy { it.second },
            "content" to articles.mapNotNull { a -> db.articleDao().getContent(a.id)?.let { keyOf[a.id] to it.copy(articleId = 0) } }.sortedBy { it.first },
            "engagement" to articles.mapNotNull { a -> db.engagementDao().get(a.id)?.let { keyOf[a.id] to it.copy(articleId = 0) } }.sortedBy { it.first },
            "editions" to db.archiveDao().releasedEditions(T0).map { edition ->
                edition.copy(id = 0) to db.archiveDao().items(edition.id).map { it.copy(editionId = 0, articleId = 0) to keyOf[it.articleId] }
            },
        )
    }

    @Test
    fun roundTripIntoAnEmptyDatabase() = runBlocking<Unit> {
        seed(source)
        val (file, exported) = export(source)
        assertEquals(ExportReport(feeds = 2, posts = 12, editions = 3), exported)

        val progress = mutableListOf<ArchiveProgress>()
        val report = RoomArchiveTransfer(target, clock).import({ ByteArrayInputStream(file) }) { progress += it }
        assertEquals(ImportReport(postsImported = 12, postsSkipped = 0, editionsImported = 3, feedsAdded = 2), report)
        assertEquals(ArchiveProgress(12, 12), progress.last())

        assertEquals(snapshot(source), snapshot(target))
        // The search index was rebuilt: bodies, headlines and the fetched full text.
        assertEquals(10, search(target, "marmalade"))
        assertEquals(1, search(target, "quince"))
        assertEquals(12, search(target, "Headline"))
        // Tags came with their feed.
        assertEquals(listOf("Essays", "Tech"), target.tagDao().getAll().map { it.name })
    }

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
    fun importingTwiceChangesNothing() = runBlocking<Unit> {
        seed(source)
        val (file, _) = export(source)
        import(target, file)
        val before = snapshot(target)

        val again = import(target, file)
        assertEquals(ImportReport(postsImported = 0, postsSkipped = 12, editionsImported = 0, editionsSkipped = 3, feedsAdded = 0), again)
        assertEquals(before, snapshot(target))
        assertEquals(12, target.articleDao().count())
        assertEquals(3, target.editionDao().count())
        assertEquals(2, target.feedDao().countSubscribed())
    }

    @Test
    fun mergingIntoADatabaseThatHoldsPartOfTheDataAddsOnlyWhatIsMissing() = runBlocking<Unit> {
        seed(source)
        val (file, _) = export(source)

        // The target subscribes to the news feed under a differently written address, has
        // synced its first four posts itself, and built the edition of 11 October on its own.
        val news = target.feedDao().insert(FeedEntity(url = "https://news.example/rss/", title = "News (renamed)", addedAt = day(5)))
        target.tagDao().setFeedTags(news, listOf("World"))
        val local = (1..4).associate { n ->
            val text = "Local body of news-$n"
            "news-$n" to target.articleDao().insertNew(article(news, "news-$n", title = "Local headline $n", firstSeenAt = day(20)), body(text), text)!!
        }
        val ownEdition = target.editionDao().insertEditionWithItems(
            EditionEntity(
                date = LocalDate.of(2026, 10, 11), kind = EditionKind.MORNING, cutoffAt = day(20), releaseAt = day(20),
                builtAt = day(20), length = EditionLength.SHORT, number = 40,
            ),
            listOf(EditionItemEntity(0, local.getValue("news-3"), 1, null, 0, ItemKind.LEAD, 0.5)),
        )
        // Local engagement, newer than the archive's: news-1 was Liked here (the archive has it
        // saved); news-2 was opened here at a different time and read.
        target.engagementDao().apply {
            setLiked(local.getValue("news-1"), day(25))
            markOpened(local.getValue("news-2"), day(26))
            markRead(local.getValue("news-2"), day(26))
        }

        val report = import(target, file)
        assertEquals(
            ImportReport(postsImported = 8, postsSkipped = 4, textsRestored = 0, editionsImported = 2, editionsSkipped = 1, feedsAdded = 1),
            report,
        )

        // No duplicates: one news feed, 12 posts, each (feed, guid) once.
        assertEquals(2, target.feedDao().countSubscribed())
        assertEquals(12, target.articleDao().count())
        val feed = target.feedDao().getById(news)!!
        assertEquals("the local feed keeps its own address and name", "https://news.example/rss/" to "News (renamed)", feed.url to feed.title)
        assertEquals(listOf("World"), target.tagDao().getForFeed(news).map { it.name })
        // The feed that was missing came back with its tags and slider.
        val blog = target.feedDao().getByUrl("https://blog.example/feed.xml")!!
        assertEquals(SliderStep.FAVOURITE, blog.sliderStep)
        assertEquals(listOf("Essays", "Tech"), target.tagDao().getForFeed(blog.id).map { it.name })

        // Posts already held are left exactly as they were, text included.
        val kept = target.articleDao().getById(local.getValue("news-1"))!!
        assertEquals("Local headline 1", kept.title)
        assertEquals(day(20), kept.firstSeenAt)
        assertEquals(body("Local body of news-1"), target.articleDao().getContent(kept.id)!!.blocksJson)
        // Posts that were missing arrive under the local feed.
        assertNotNull(target.articleDao().getByGuid(news, "news-6"))

        // Engagement: nothing set locally is overwritten; what was missing is filled in.
        val one = target.engagementDao().get(local.getValue("news-1"))!!
        assertEquals("the local Like stands", day(25), one.likedAt)
        assertEquals("the archive's save fills the gap", day(3), one.savedAt)
        val two = target.engagementDao().get(local.getValue("news-2"))!!
        assertEquals("the local open time is not replaced by the archive's", day(26), two.openedAt)
        assertEquals(day(26), two.readAt)
        val liked = target.engagementDao().get(target.articleDao().getByGuid(blog.id, "blog-1")!!.id)!!
        assertEquals(day(2), liked.likedAt)
        assertEquals(0.9f, liked.readFraction)

        // Editions: the one for 11 October is the target's own, untouched; the others came in
        // with their items pointing at the right posts.
        assertEquals(3, target.editionDao().count())
        val own = target.editionDao().getById(ownEdition)!!
        assertEquals(EditionLength.SHORT to 40, own.length to own.number)
        assertEquals(listOf(local.getValue("news-3")), target.editionDao().getItems(ownEdition).map { it.item.articleId })
        val third = target.editionDao().getByDate(LocalDate.of(2026, 10, 13), EditionKind.MORNING)!!
        assertEquals(
            listOf("blog-3" to ItemKind.LEAD, "news-3" to ItemKind.FRONT, "news-4" to ItemKind.INDEX),
            target.editionDao().getItems(third.id).map { it.row.article.guid to it.item.kind },
        )
        // The imported items of a post the target already held point at the target's own row.
        assertEquals(local.getValue("news-3"), target.editionDao().getItems(third.id)[1].row.article.id)
    }

    @Test
    fun exportAndFreeUpSpaceThenImportBringsTheTextBack() = runBlocking<Unit> {
        val library = seed(source)
        // Two later editions, so the three seeded ones are old enough to trim.
        for (n in 10L..11L) {
            source.editionDao().insertEditionWithItems(
                EditionEntity(
                    date = LocalDate.of(2026, 10, 10).plusDays(n), kind = EditionKind.MORNING, cutoffAt = day(n), releaseAt = day(n),
                    builtAt = day(n), length = EditionLength.STANDARD, number = n.toInt(),
                ),
                emptyList(),
            )
        }
        val before = snapshot(source)
        val (file, _) = export(source)

        // "Free up space": everything that may go, goes. Liked and saved posts keep their text.
        val freed = RoomStorageRepository(source, FakeSettings(), clock).trimAll()
        assertEquals(9, freed.articlesTrimmed)
        assertNotNull(source.articleDao().getContent(library.posts.getValue("blog-1")))
        assertNotNull(source.articleDao().getContent(library.posts.getValue("news-1")))
        assertNull(source.articleDao().getContent(library.posts.getValue("news-2")))
        assertEquals(12, source.articleDao().count())
        assertEquals(5, source.editionDao().count())
        assertEquals(2, search(source, "marmalade"))

        val report = import(source, file)
        assertEquals(ImportReport(postsImported = 0, postsSkipped = 3, textsRestored = 9, editionsSkipped = 5), report)
        assertEquals(before, snapshot(source))
        assertEquals(10, search(source, "marmalade"))
        assertEquals(1, search(source, "quince"))
    }

    @Test
    fun aRemovedLikeComesBackOnlyWhereNothingLocalSaysOtherwise() {
        val archived = ArchiveEngagement(openedAt = 1000, readFraction = 0.4f, readAt = 2000, likedAt = 3000, editionsRunUnopened = 1, leadCount = 2)
        val fresh = RoomArchiveTransfer.mergeEngagement(7, null, archived)
        assertEquals(Instant.ofEpochMilli(3000), fresh.likedAt)
        assertEquals(7, fresh.articleId)

        val local = fresh.copy(openedAt = Instant.ofEpochMilli(500), readFraction = 0.8f, likedAt = Instant.ofEpochMilli(9000), savedAt = Instant.ofEpochMilli(9500), leadCount = 1)
        val merged = RoomArchiveTransfer.mergeEngagement(7, local, archived)
        assertEquals(local.copy(leadCount = 2), merged)
    }

    @Test
    fun aFileThatIsNotAnArchiveIsRefusedAndNothingIsImported() = runBlocking<Unit> {
        suspend fun reasonFor(file: ByteArray): ArchiveFormatException.Reason? = try {
            import(target, file)
            fail("imported")
            null
        } catch (e: ArchiveFormatException) {
            e.reason
        }
        fun zipOf(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                for ((name, text) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()

        assertEquals(ArchiveFormatException.Reason.NOT_AN_ARCHIVE, reasonFor("<opml/>".toByteArray()))
        assertEquals(ArchiveFormatException.Reason.NOT_AN_ARCHIVE, reasonFor(ByteArray(0)))
        assertEquals(ArchiveFormatException.Reason.NOT_AN_ARCHIVE, reasonFor(zipOf("notes.txt" to "hello")))
        assertEquals(
            ArchiveFormatException.Reason.NOT_AN_ARCHIVE,
            reasonFor(zipOf("manifest.json" to """{"format":"something-else","version":1,"exportedAt":0}""")),
        )
        assertEquals(
            ArchiveFormatException.Reason.NEWER_VERSION,
            reasonFor(zipOf("manifest.json" to """{"format":"morning-issue-archive","version":3,"exportedAt":0}""")),
        )
        assertEquals(0, target.feedDao().countSubscribed())
    }

    @Test
    fun unknownFieldsAndDamagedLinesAreToleratedSoLaterVersionsCanAddToTheFormat() = runBlocking<Unit> {
        val file = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                fun entry(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
                entry("manifest.json", """{"format":"morning-issue-archive","version":1,"exportedAt":0,"posts":3,"madeBy":"1.4"}""")
                entry("feeds.jsonl", """{"n":1,"url":"https://a.example/feed","title":"A","addedAt":0,"colour":"red"}""")
                entry(
                    "posts.jsonl",
                    """{"n":1,"feed":1,"guid":"g1","title":"One","publishedAt":0,"firstSeenAt":0,"mood":"sunny"}""" + "\n" +
                        "{ this line was cut off\n" +
                        """{"n":3,"feed":9,"guid":"orphan","title":"No such feed","publishedAt":0,"firstSeenAt":0}""" + "\n",
                )
                entry(
                    "editions.jsonl",
                    """{"date":"2026-01-02","kind":"MORNING","cutoffAt":0,"releaseAt":0,"builtAt":0,"length":"STANDARD","number":1,""" +
                        """"items":[{"post":1,"page":1,"slot":0,"kind":"LEAD","score":1.0},{"post":3,"page":1,"slot":1,"kind":"FRONT","score":0.5},""" +
                        """{"post":1,"page":2,"slot":0,"kind":"HOLOGRAM","score":0.5}]}""" + "\n" +
                        """{"date":"2026-01-03","kind":"MIDNIGHT","cutoffAt":0,"releaseAt":0,"builtAt":0,"length":"STANDARD","number":2}""",
                )
            }
        }.toByteArray()

        val report = import(target, file)
        assertEquals(ImportReport(postsImported = 1, postsSkipped = 2, editionsImported = 1, editionsSkipped = 1, feedsAdded = 1), report)
        val edition = target.editionDao().getByDate(LocalDate.of(2026, 1, 2), EditionKind.MORNING)!!
        assertEquals(listOf(ItemKind.LEAD), target.editionDao().getItems(edition.id).map { it.item.kind })
        assertTrue(target.articleDao().getContent(target.editionDao().getItems(edition.id).single().item.articleId) == null)
    }

    // --- Categories and enclosures ---------------------------------------------------------------

    private fun category(name: String) = ArticleCategories.of(name)!!

    private suspend fun categoriesOf(db: MorningIssueDatabase, articleId: Long): List<ArticleCategory> =
        db.articleSignalDao().categoriesOf(listOf(articleId)).map { ArticleCategory(it.key, it.label) }

    private suspend fun idOf(db: MorningIssueDatabase, feedUrl: String, guid: String): Long =
        db.articleDao().getByGuid(db.feedDao().getByUrl(feedUrl)!!.id, guid)!!.id

    @Test
    fun categoriesAndEnclosureKindsRoundTripAndTheRestIsRebuilt() = runBlocking<Unit> {
        val library = seed(source)
        val signals = source.articleSignalDao()
        val blog1 = library.posts.getValue("blog-1")
        val blog2 = library.posts.getValue("blog-2")
        signals.addCategories(blog1, listOf(category("Tech"), category("Long Reads")))
        signals.addCategories(library.posts.getValue("news-1"), listOf(category("World")))
        signals.upsertTraits(ArticleSignalDao.traitsRow(blog1, ArticleTraits(4, 2, true, EnclosureKind.AUDIO, false, "podcast")))
        signals.upsertTraits(ArticleSignalDao.traitsRow(blog2, ArticleTraits(1, 0, false, EnclosureKind.NONE, true, null)))
        signals.upsertEmbeddings(listOf(ArticleEmbeddingEntity(blog1, 1, byteArrayOf(1, 2, 3))))

        val (file, _) = export(source)
        import(target, file)
        assertEquals(snapshot(source), snapshot(target))

        val imported = target.articleSignalDao()
        val blogUrl = "https://blog.example/feed.xml"
        val newBlog1 = idOf(target, blogUrl, "blog-1")
        assertEquals(listOf(ArticleCategory("long reads", "Long Reads"), ArticleCategory("tech", "Tech")), categoriesOf(target, newBlog1))
        assertEquals(listOf(ArticleCategory("world", "World")), categoriesOf(target, idOf(target, "http://www.news.example/rss", "news-1")))
        assertEquals(3, imported.categoryCount())

        // Traits and embeddings are not in the file. Only the enclosure, which nothing stored
        // could give back, comes across, in a row that still awaits its traits.
        assertEquals(0, imported.embeddingCount())
        assertEquals(1, imported.traitsCount())
        val pending = imported.getTraits(newBlog1)!!
        assertEquals(ArticleTraitRules.VERSION_PENDING, pending.traitsVersion)
        assertEquals(EnclosureKind.AUDIO, pending.enclosure)

        assertEquals(12, TraitsBackfill(target).run())
        val rebuilt = imported.getTraits(newBlog1)!!
        assertEquals(ArticleTraitRules.VERSION, rebuilt.traitsVersion)
        assertEquals(EnclosureKind.AUDIO, rebuilt.enclosure)
        assertEquals(12, imported.traitsCount())

        // A second import adds nothing and disturbs nothing.
        import(target, file)
        assertEquals(3, imported.categoryCount())
        assertEquals(rebuilt, imported.getTraits(newBlog1))
    }

    @Test
    fun importedCategoriesAndEnclosuresFillInWhatAHeldPostLacksAndReplaceNothing() = runBlocking<Unit> {
        val library = seed(source)
        val signals = source.articleSignalDao()
        for (n in 1..3) {
            val id = library.posts.getValue("blog-$n")
            signals.addCategories(id, listOf(category("Archived")))
            signals.upsertTraits(ArticleSignalDao.traitsRow(id, ArticleTraits(0, 0, false, EnclosureKind.AUDIO, false, null)))
        }
        val (file, _) = export(source)

        // The target holds the same three posts: one filed and with a video, one computed without an enclosure, one bare.
        val blog = target.feedDao().insert(FeedEntity(url = "https://blog.example/feed.xml", title = "A Blog", addedAt = day(0)))
        val held = (1..3).map { n -> target.articleDao().insertNew(article(blog, "blog-$n", firstSeenAt = day(n.toLong())))!! }
        val local = target.articleSignalDao()
        local.addCategories(held[0], listOf(category("Local")))
        local.upsertTraits(ArticleSignalDao.traitsRow(held[0], ArticleTraits(7, 0, false, EnclosureKind.VIDEO, false, null)))
        local.upsertTraits(ArticleSignalDao.traitsRow(held[1], ArticleTraits(7, 0, false, EnclosureKind.NONE, false, null)))

        import(target, file)

        assertEquals(listOf(ArticleCategory("local", "Local")), categoriesOf(target, held[0]))
        assertEquals(EnclosureKind.VIDEO, local.getTraits(held[0])!!.enclosure)
        assertEquals(listOf(ArticleCategory("archived", "Archived")), categoriesOf(target, held[1]))
        // The computed row keeps its numbers and learns the enclosure.
        assertEquals(ArticleSignalDao.traitsRow(held[1], ArticleTraits(7, 0, false, EnclosureKind.AUDIO, false, null)), local.getTraits(held[1]))
        assertEquals(listOf(ArticleCategory("archived", "Archived")), categoriesOf(target, held[2]))
        assertEquals(ArticleTraitRules.VERSION_PENDING, local.getTraits(held[2])!!.traitsVersion)
        assertEquals(EnclosureKind.AUDIO, local.getTraits(held[2])!!.enclosure)
    }

    @Test
    fun anArchiveWrittenBeforeCategoriesImportsAndForeignCategoriesAreNormalised() = runBlocking<Unit> {
        val file = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                fun entry(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
                entry("manifest.json", """{"format":"morning-issue-archive","version":1,"exportedAt":0,"posts":3}""")
                entry("feeds.jsonl", """{"n":1,"url":"https://a.example/feed","title":"A","addedAt":0}""")
                entry(
                    "posts.jsonl",
                    // As version 1 of the app wrote a post: no categories, no enclosure.
                    """{"n":1,"feed":1,"guid":"old","title":"Old","publishedAt":0,"firstSeenAt":0,"wordCount":3}""" + "\n" +
                        // As another program might: untidy keys, a blank one, repeats, an unknown enclosure kind.
                        """{"n":2,"feed":1,"guid":"untidy","title":"Untidy","publishedAt":0,"firstSeenAt":0,"categories":[""" +
                        """{"key":"  Long   READS ","label":"Long Reads"},{"key":"","label":"Blank"},{"key":"long reads","label":"Again"}],"enclosure":"HOLOGRAM"}""" + "\n" +
                        """{"n":3,"feed":1,"guid":"episode","title":"Episode","publishedAt":0,"firstSeenAt":0,"categories":[],"enclosure":"NONE"}""" + "\n",
                )
                entry("editions.jsonl", "")
            }
        }.toByteArray()

        assertEquals(ImportReport(postsImported = 3, feedsAdded = 1), import(target, file))
        val url = "https://a.example/feed"
        assertTrue(categoriesOf(target, idOf(target, url, "old")).isEmpty())
        assertEquals(listOf(ArticleCategory("long reads", "Long Reads")), categoriesOf(target, idOf(target, url, "untidy")))
        assertEquals(0, target.articleSignalDao().traitsCount())
        assertEquals(3, TraitsBackfill(target).run())
    }

    // --- Feed status (archive version 2) -----------------------------------------------------------

    @Test
    fun aVersion1ArchiveImportsEveryFeedAsASubscription() = runBlocking<Unit> {
        val file = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                fun entry(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
                entry("manifest.json", """{"format":"morning-issue-archive","version":1,"exportedAt":0,"posts":0}""")
                entry("feeds.jsonl", """{"n":1,"url":"https://a.example/feed","title":"A","addedAt":0}""" + "\n" + """{"n":2,"url":"https://b.example/feed","title":"B","addedAt":0}""")
                entry("posts.jsonl", "")
                entry("editions.jsonl", "")
            }
        }.toByteArray()

        assertEquals(ImportReport(feedsAdded = 2), import(target, file))
        assertEquals(listOf(FeedStatus.SUBSCRIBED, FeedStatus.SUBSCRIBED), target.feedDao().getUrls().map { target.feedDao().getById(it.id)!!.status })
    }

    @Test
    fun aDiscoveredFeedRoundTripsAsDiscoveredAndASubscriptionInTheFileSubscribesIt() = runBlocking<Unit> {
        seed(source)
        val peek = source.feedDao().insert(
            FeedEntity(url = "https://peek.example/feed", title = "Peek", addedAt = day(5), status = FeedStatus.DISCOVERED, discoveredAt = day(5)),
        )
        val saved = source.articleDao().insertNew(article(peek, "peek-1", title = "A saved find", summary = null, firstSeenAt = day(5)))!!
        source.engagementDao().setSaved(saved, day(5))

        val (file, _) = export(source)
        val report = import(target, file)
        // Only subscriptions count as feeds added.
        assertEquals(2, report.feedsAdded)
        val imported = target.feedDao().getByUrl("https://peek.example/feed")!!
        assertEquals(FeedStatus.DISCOVERED, imported.status)
        assertEquals(day(5), imported.discoveredAt)
        assertEquals(2, target.feedDao().countSubscribed())
        assertNotNull(target.engagementDao().get(idOf(target, "https://peek.example/feed", "peek-1"))!!.savedAt)

        // The reader subscribed on the other device: importing that subscribes the local row.
        source.feedDao().subscribeDiscovered(peek, day(6))
        val (later, _) = export(source)
        assertEquals(1, import(target, later).feedsAdded)
        assertEquals(FeedStatus.SUBSCRIBED, target.feedDao().getById(imported.id)!!.status)
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
}
