package net.tgnra.morningissue.server.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.api.ReleaseTimesRequest
import net.tgnra.morningissue.api.SettingsPatch
import net.tgnra.morningissue.data.archive.ArchiveContent
import net.tgnra.morningissue.data.archive.ArchiveEngagement
import net.tgnra.morningissue.data.archive.ArchiveFormat
import net.tgnra.morningissue.data.archive.ArchivePageView
import net.tgnra.morningissue.data.archive.ArchiveReleaseChange
import net.tgnra.morningissue.data.archive.ArchiveSettings
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.server.Cli
import net.tgnra.morningissue.server.ServerContainer
import net.tgnra.morningissue.server.config.ConfigLoader
import net.tgnra.morningissue.server.db.ArticleCategoryEntity
import net.tgnra.morningissue.server.db.ArticleTraitsEntity
import net.tgnra.morningissue.server.db.EditionEntity
import net.tgnra.morningissue.server.db.EditionItemEntity
import net.tgnra.morningissue.server.editions.SettingsServiceImpl
import net.tgnra.morningissue.server.editions.TestWorld
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export back to the phone app: one user's library written as a legacy archive, read back
 * with core's `ArchiveFormat` and checked against what the phone app's importer (58c93ad)
 * accepts ([PhoneArchiveReader]).
 */
class ArchiveExportTest {
    private val world = TestWorld(T0)
    private val db = world.db
    private val exporter = ArchiveExporter(db, world.settings, world.clock)
    private val importer = ArchiveImporter(db, world.settings, world.clock)
    private val alice = runBlocking { world.addUser("alice") }
    private val bob = runBlocking { world.addUser("bob") }

    @After
    fun tearDown() = world.close()

    private suspend fun export(userId: Long, exporter: ArchiveExporter = this.exporter): ByteArray =
        ByteArrayOutputStream().also { exporter.export(userId, it) }.toByteArray()

    private suspend fun exportChecked(userId: Long, exporter: ArchiveExporter = this.exporter): ParsedArchive =
        PhoneArchiveReader.assertPhoneImports(export(userId, exporter))

    private suspend fun edition(
        userId: Long,
        date: LocalDate,
        number: Int,
        items: List<EditionItemEntity>,
        released: Boolean = true,
        tracked: Boolean = true,
    ): Long {
        val releaseAt = if (released) world.clock.now.minus(Duration.ofDays(1)) else world.clock.now.plus(Duration.ofHours(3))
        return db.editionDao().insertEditionWithItems(
            EditionEntity(
                userId = userId, date = date, kind = EditionKind.MORNING, cutoffAt = releaseAt.minusSeconds(3600),
                releaseAt = releaseAt, builtAt = releaseAt.minusSeconds(1800), length = EditionLength.STANDARD, number = number,
                pageViewsTracked = tracked,
            ),
            items,
        )
    }

    private fun item(articleId: Long, page: Int, slot: Int, kind: ItemKind, section: String? = null, reason: String? = null) =
        EditionItemEntity(0, articleId, page, section, slot, kind, 1.0 - slot * 0.1, reason)

    // --- A seeded account ---------------------------------------------------------------------

    @Test
    fun aSeededAccountIsWrittenWithEverythingThePhoneKeeps() = runBlocking<Unit> {
        val now = world.clock.now
        val muted = now.plus(Duration.ofDays(3))
        val blog = world.addFeed(alice, "Blog", sliderStep = SliderStep.FAVOURITE, tags = listOf("Tech", "Essays"), muteUntil = muted)
        db.subscriptionDao().updateTitle(alice, blog, "My Blog")
        val found = world.addFeed(alice, "Found", status = FeedStatus.DISCOVERED, addedAt = now.minus(Duration.ofDays(2)))
        val lead = world.addPost(blog, now.minus(Duration.ofDays(5)), wordCount = 120, feedWordCount = 40, blocksJson = body("Lead body"), plainText = "Lead body")
        val second = world.addPost(blog, now.minus(Duration.ofDays(4)))
        world.storeBody(second, body("Fetched text"), "Fetched text", 300)
        val kept = world.addPost(found, now.minus(Duration.ofDays(2)))
        db.signalDao().insertCategories(listOf(ArticleCategoryEntity(lead, "politics", "Politics")))
        db.signalDao().upsertTraits(ArticleTraitsEntity(lead, ArticleTraitRules.VERSION, 0, 0, false, EnclosureKind.AUDIO, false, null))
        db.signalDao().upsertTraits(ArticleTraitsEntity(second, ArticleTraitRules.VERSION, 1, 0, false, EnclosureKind.NONE, false, null))
        world.open(alice, lead, now.minus(Duration.ofDays(1)))
        world.readFraction(alice, lead, 0.75f)
        world.read(alice, lead, now.minus(Duration.ofDays(1)))
        world.like(alice, lead, now.minus(Duration.ofHours(20)))
        world.save(alice, kept, now.minus(Duration.ofHours(10)))

        val first = edition(
            alice, LocalDate.of(2026, 11, 9), 1,
            listOf(item(lead, 1, 0, ItemKind.LEAD, reason = "Because you like Blog"), item(second, 2, 0, ItemKind.SECTION, section = "Tech")),
        )
        world.openEdition(alice, first, now.minus(Duration.ofHours(23)))
        db.editionDao().recordPageView(alice, first, 1, 12_000, now.minus(Duration.ofHours(23)))
        edition(alice, LocalDate.of(2026, 11, 10), 2, listOf(item(second, 1, 0, ItemKind.LEAD)), tracked = false)
        // Built but not yet released: the phone never saw it.
        edition(alice, LocalDate.of(2026, 11, 11), 3, listOf(item(second, 1, 0, ItemKind.LEAD)), released = false)

        val file = exportChecked(alice)

        // Feeds: the user's own name, tags, slider, mute, status.
        assertEquals(2, file.feeds.size)
        val blogLine = file.feeds.first { it.n == blog }
        assertEquals("My Blog", blogLine.title)
        assertEquals(listOf("Essays", "Tech"), blogLine.tags)
        assertEquals("FAVOURITE", blogLine.sliderStep)
        assertEquals("SUBSCRIBED", blogLine.status)
        assertEquals(muted.toEpochMilli(), blogLine.muteUntil)
        assertEquals(now.minus(Duration.ofDays(30)).toEpochMilli(), blogLine.addedAt)
        val foundLine = file.feeds.first { it.n == found }
        assertEquals("DISCOVERED", foundLine.status)
        assertEquals(now.minus(Duration.ofDays(2)).toEpochMilli(), foundLine.discoveredAt)
        assertNull(foundLine.muteUntil)

        // Posts with bodies, signals and engagement.
        assertEquals(3, file.posts.size)
        val leadLine = file.posts.first { it.n == lead }
        assertEquals(blog, leadLine.feed)
        assertEquals(ArchiveContent(body("Lead body"), "FEED", now.minus(Duration.ofDays(5)).toEpochMilli()), leadLine.content)
        assertEquals(120, leadLine.wordCount)
        assertEquals(40, leadLine.feedWordCount)
        assertEquals(listOf(Archive.category("politics", "Politics")), leadLine.categories)
        assertEquals("AUDIO", leadLine.enclosure)
        val engagement = leadLine.engagement!!
        assertEquals(now.minus(Duration.ofDays(1)).toEpochMilli(), engagement.openedAt)
        assertEquals(now.minus(Duration.ofHours(20)).toEpochMilli(), engagement.likedAt)
        assertTrue(engagement.readFraction >= 0.75f)
        assertEquals(now.minus(Duration.ofDays(1)).toEpochMilli(), engagement.readAt)
        val secondLine = file.posts.first { it.n == second }
        assertEquals("FETCHED", secondLine.content!!.source)
        assertNull(secondLine.enclosure)
        assertNull(secondLine.engagement)
        assertEquals(now.minus(Duration.ofHours(10)).toEpochMilli(), file.posts.first { it.n == kept }.engagement!!.savedAt)

        // Released editions only, with their items, reasons and page views.
        assertEquals(listOf(1, 2), file.editions.map { it.number })
        val e1 = file.editions[0]
        assertEquals("2026-11-09", e1.date)
        assertEquals(now.minus(Duration.ofHours(23)).toEpochMilli(), e1.openedAt)
        assertEquals(listOf(lead to "LEAD", second to "SECTION"), e1.items.map { it.post to it.kind })
        assertEquals("Because you like Blog", e1.items[0].reason)
        assertEquals("Tech", e1.items[1].section)
        assertEquals(true, e1.pageViewsTracked)
        assertEquals(listOf(ArchivePageView(1, 12_000, now.minus(Duration.ofHours(23)).toEpochMilli())), e1.pageViews)
        assertEquals(false, file.editions[1].pageViewsTracked)
        assertNull(file.editions[1].pageViews)

        // An account on the defaults has no settings of its own to carry.
        assertNull(file.settings)
        assertFalse(ArchiveFormat.SETTINGS in file.entries)
    }

    @Test
    fun theAccountsSettingsAreWrittenOnceTheUserHasSetAny() = runBlocking<Unit> {
        val settings = SettingsServiceImpl(db, world.settings, world.clock)
        settings.setReleaseTimes(alice, ReleaseTimesRequest(resetTime = LocalTime.of(6, 15)))
        settings.patch(alice, SettingsPatch(editionLength = EditionLength.LONG, timeZone = "Europe/Berlin"))
        val stored = world.settings.get(alice)

        val written = exportChecked(alice).settings!!
        assertEquals(stored.resetTime, written.resetTime)
        assertTrue(stored.resetChange != null || stored.resetTime == "06:15")
        assertEquals("LONG", written.editionLength)
        assertEquals("Europe/Berlin", written.timeZone)
        assertEquals(stored.eveningEnabled, written.eveningEnabled)
        assertEquals(stored.resetChange, written.resetChange)
        // And the importer takes back exactly what was written.
        assertEquals(stored, ArchiveImporter.toStored(written, world.settings.defaults()))
    }

    @Test
    fun postsHeldWithoutASubscriptionComeWithTheirFeedAsDiscovered() = runBlocking<Unit> {
        // A post saved from a feed the user does not subscribe to (on the phone, it would be
        // a discovered feed): without its feed line the phone would drop the post.
        val elsewhere = world.addGlobalFeed("Elsewhere", url = "https://elsewhere.example/feed")
        val post = world.addPost(elsewhere, world.clock.now.minus(Duration.ofDays(1)))
        world.save(alice, post)
        val file = exportChecked(alice)
        val feed = file.feed("https://elsewhere.example/feed")
        assertEquals("DISCOVERED", feed.status)
        assertEquals(elsewhere, feed.n)
        assertEquals(listOf(post), file.posts.map { it.n })
        assertTrue(file.posts.single().engagement!!.savedAt != null)
    }

    @Test
    fun anEmptyAccountIsAnEmptyArchive() = runBlocking<Unit> {
        val file = exportChecked(alice)
        assertEquals(ArchiveFormat.VERSION, file.manifest.version)
        assertEquals(T0.toEpochMilli(), file.manifest.exportedAt)
        assertTrue(file.feeds.isEmpty() && file.posts.isEmpty() && file.editions.isEmpty())
    }

    // --- Scoping ------------------------------------------------------------------------------

    @Test
    fun anotherUsersDataNeverAppears() = runBlocking<Unit> {
        val now = world.clock.now
        // A feed both read, each with their own tags and slider.
        val shared = world.addGlobalFeed("Shared", url = "https://shared.example/feed")
        world.subscribe(alice, shared, tags = listOf("Mine"))
        world.subscribe(bob, shared, sliderStep = SliderStep.MORE, tags = listOf("Bobs"), muteUntil = now.plus(Duration.ofDays(1)))
        db.subscriptionDao().updateTitle(bob, shared, "Bob's name for it")
        val sharedPost = world.addPost(shared, now.minus(Duration.ofDays(2)))
        // Bob's own feed, a post he saved from elsewhere, and his engagement on the shared post.
        val bobsFeed = world.addFeed(bob, "Bob only")
        val bobsPost = world.addPost(bobsFeed, now.minus(Duration.ofDays(1)))
        val elsewhere = world.addGlobalFeed("Elsewhere", url = "https://elsewhere.example/feed")
        val bobsFind = world.addPost(elsewhere, now.minus(Duration.ofDays(1)))
        world.save(bob, bobsFind)
        world.like(bob, sharedPost)
        world.open(bob, sharedPost)
        edition(bob, LocalDate.of(2026, 11, 9), 1, listOf(item(sharedPost, 1, 0, ItemKind.LEAD), item(bobsPost, 1, 1, ItemKind.FRONT)))
        SettingsServiceImpl(db, world.settings, world.clock).patch(bob, SettingsPatch(editionLength = EditionLength.SHORT))
        // Alice opened the shared post; that is all hers.
        world.open(alice, sharedPost, now.minus(Duration.ofHours(5)))

        val file = exportChecked(alice)
        assertEquals(listOf("https://shared.example/feed"), file.feeds.map { it.url })
        val feed = file.feeds.single()
        assertEquals("Shared", feed.title)
        assertEquals(listOf("Mine"), feed.tags)
        assertEquals("AUTO", feed.sliderStep)
        assertNull(feed.muteUntil)
        assertEquals(listOf(sharedPost), file.posts.map { it.n })
        assertEquals(ArchiveEngagement(openedAt = now.minus(Duration.ofHours(5)).toEpochMilli()), file.posts.single().engagement)
        assertTrue(file.editions.isEmpty())
        assertNull(file.settings)

        // And Bob's file holds his side, none of Alice's.
        val bobs = exportChecked(bob)
        assertEquals(setOf("https://shared.example/feed", bobsFeedUrl(), "https://elsewhere.example/feed"), bobs.feeds.map { it.url }.toSet())
        assertEquals("Bob's name for it", bobs.feed("https://shared.example/feed").title)
        assertEquals(listOf("Bobs"), bobs.feed("https://shared.example/feed").tags)
        val bobsEngagement = bobs.posts.first { it.n == sharedPost }.engagement!!
        assertEquals(now.toEpochMilli(), bobsEngagement.openedAt)
        assertEquals(now.toEpochMilli(), bobsEngagement.likedAt)
        assertEquals(setOf(sharedPost, bobsPost, bobsFind), bobs.posts.map { it.n }.toSet())
        assertEquals(1, bobs.editions.size)
        assertEquals("SHORT", bobs.settings!!.editionLength)
    }

    private fun bobsFeedUrl() = "https://example.com/Bob-only.xml"

    // --- Round trips --------------------------------------------------------------------------

    /** What a file says, without the numbers that only mean something inside it. */
    private fun normalised(file: ParsedArchive): Map<String, Any?> = mapOf(
        "feeds" to file.feeds.map { it.copy(n = 0, tags = it.tags.sorted()) }.sortedBy { it.url },
        "posts" to file.posts.map { post -> post.copy(n = 0, feed = 0) to file.feeds.first { it.n == post.feed }.url }.sortedBy { it.first.guid },
        "editions" to file.editions.map { e -> e.copy(items = emptyList()) to e.items.map { it.copy(post = 0) to file.guidOf(it.post) } },
        "settings" to file.settings,
    )

    private val phone = ArchiveSettings(
        resetTime = "05:45",
        editionLength = "LONG",
        eveningEnabled = true,
        eveningTime = "19:15",
        timeZone = "Europe/London",
        onboardingDone = true,
        nothingNewFor = listOf(ms(28)),
        resetChange = ArchiveReleaseChange(settled = ms(10), kept = null, from = ms(11)),
    )

    @Test
    fun aPhoneArchiveImportedThenExportedComesBackAsItWas() = runBlocking<Unit> {
        val original = Archive.library(settings = phone)
        importer.import(alice, { ByteArrayInputStream(original.bytes()) })
        val file = exportChecked(alice)
        val source = PhoneArchiveReader.parse(original.bytes())

        // Every feed, post and edition, field for field, as the phone wrote them.
        assertEquals(normalised(source)["feeds"], normalised(file)["feeds"])
        assertEquals(normalised(source)["posts"], normalised(file)["posts"])
        assertEquals(phone, file.settings)
        // Editions too, except that the server records "tracked" only for an edition that
        // came with views (the third said tracked but had none).
        val expected = source.copy(editions = source.editions.map { it.copy(pageViewsTracked = it.pageViews != null) })
        assertEquals(normalised(expected)["editions"], normalised(file)["editions"])
        assertEquals(12, file.posts.size)
        assertEquals(day(2).toEpochMilli(), file.post("blog-1").engagement!!.likedAt)
        assertEquals(ContentSource.FETCHED.name, file.post("blog-2").content!!.source)
        assertNull(file.post("news-5").content)
    }

    @Test
    fun anExportImportedIntoAnotherServerExportsTheSame() = runBlocking<Unit> {
        importer.import(alice, { ByteArrayInputStream(Archive.library(settings = phone).bytes()) })
        world.addFeed(alice, "Native", tags = listOf("Later"))
        val first = export(alice)

        val other = TestWorld(T0)
        try {
            val carol = other.addUser("carol")
            ArchiveImporter(other.db, other.settings, other.clock).import(carol, { ByteArrayInputStream(first) })
            val second = exportChecked(carol, ArchiveExporter(other.db, other.settings, other.clock))
            assertEquals(normalised(PhoneArchiveReader.parse(first)), normalised(second))
        } finally {
            other.close()
        }
    }

    @Test
    fun theFileIsAVersionedZipOfJsonLinesInThePhonesOrder() = runBlocking<Unit> {
        importer.import(alice, { ByteArrayInputStream(Archive.library(settings = phone).bytes()) })
        val file = exportChecked(alice)
        assertEquals(
            listOf(ArchiveFormat.MANIFEST, ArchiveFormat.FEEDS, ArchiveFormat.POSTS, ArchiveFormat.EDITIONS, ArchiveFormat.SETTINGS),
            file.entries,
        )
        assertEquals(ArchiveFormat.FORMAT, file.manifest.format)
        assertEquals(2, file.manifest.feeds)
        assertEquals(12, file.manifest.posts)
        assertEquals(3, file.manifest.editions)
    }

    // --- The command line ---------------------------------------------------------------------

    @Test
    fun theCliImportsAndExportsWithTheServerStopped() {
        val dataDir = Files.createTempDirectory("export-cli").toFile()
        try {
            val env = mapOf("MORNINGISSUE_DATA_DIR" to dataDir.path, "MORNINGISSUE_USERS" to "dana")
            ServerContainer(ConfigLoader.load(null, env)).use { c -> runBlocking { c.services.auth.bootstrapUsers(listOf("dana")) } }
            val archive = File(dataDir, "phone.zip").apply { writeBytes(Archive.library(settings = phone).bytes()) }
            val quiet = PrintStream(ByteArrayOutputStream())
            assertEquals(0, Cli(::ServerContainer, env, quiet, quiet).run(arrayOf("import", "dana", archive.path)))

            val target = File(dataDir, "back-to-the-phone.zip")
            val out = ByteArrayOutputStream()
            assertEquals(0, Cli(::ServerContainer, env, PrintStream(out), quiet).run(arrayOf("export", "dana", target.path)))
            assertTrue(out.toString(), out.toString().contains("2 feeds, 12 posts and 3 editions"))
            assertFalse(File(dataDir, "back-to-the-phone.zip.part").exists())
            val file = PhoneArchiveReader.assertPhoneImports(target.readBytes())
            assertEquals(phone, file.settings)

            assertEquals(1, Cli(::ServerContainer, env, quiet, quiet).run(arrayOf("export", "nobody", target.path)))
            assertEquals(2, Cli(::ServerContainer, env, quiet, quiet).run(arrayOf("export", "dana")))
        } finally {
            dataDir.deleteRecursively()
        }
    }
}
