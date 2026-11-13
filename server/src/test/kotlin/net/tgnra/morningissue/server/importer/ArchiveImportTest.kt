package net.tgnra.morningissue.server.importer

import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.api.ImportReportDto
import net.tgnra.morningissue.api.ReleaseTimesRequest
import net.tgnra.morningissue.api.SettingsPatch
import net.tgnra.morningissue.data.archive.ArchiveEngagement
import net.tgnra.morningissue.data.archive.ArchiveFeed
import net.tgnra.morningissue.data.archive.ArchiveReleaseChange
import net.tgnra.morningissue.data.archive.ArchiveSettings
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.server.db.EngagementEntity
import net.tgnra.morningissue.server.editions.SettingsServiceImpl
import net.tgnra.morningissue.server.editions.TestWorld
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The import half of the app's `ArchiveTransferTest`, into one user's account on the server
 * (4.9, 4.13): feeds become subscriptions to shared feeds, posts are global, engagement,
 * editions and settings are the user's.
 */
class ArchiveImportTest {
    private val world = TestWorld(T0)
    private val db = world.db
    private val importer = ArchiveImporter(db, world.settings, world.clock)
    private val user = runBlocking { world.addUser() }

    @After
    fun tearDown() = world.close()

    private suspend fun import(file: ByteArray, userId: Long = user, progress: ImportProgress = ImportProgress { _, _ -> }): ImportReportDto =
        importer.import(userId, { ByteArrayInputStream(file) }, progress)

    private suspend fun feedId(url: String): Long = db.importDao().feedIdByUrl(url)!!

    private suspend fun articleId(feedUrl: String, guid: String): Long = db.importDao().articleByGuid(feedId(feedUrl), guid)!!.id

    private suspend fun blogPost(guid: String) = articleId(BLOG, guid)

    private suspend fun newsPost(guid: String) = articleId(NEWS, guid)

    /** What a user holds, without database IDs. */
    private suspend fun snapshot(userId: Long = user): Map<String, Any?> {
        val feeds = db.editionArticleDao().subscribedFeeds(userId)
        val links = db.editionArticleDao().tagLinks(userId)
        val posts = feeds.flatMap { feed -> (1..6).mapNotNull { n -> listOf("blog", "news").firstNotNullOfOrNull { db.importDao().articleByGuid(feed.feedId, "$it-$n") } } }
        return mapOf(
            "feeds" to feeds.map { f -> Triple(f.title, f.sliderStep, links.filter { it.feedId == f.feedId }.map { it.name }.sorted()) }.sortedBy { it.first },
            "engagement" to posts.mapNotNull { a -> db.engagementDao().get(userId, a.id)?.let { a.guid to it.copy(userId = 0, changeSeq = 0) } }.sortedBy { it.first },
            "editions" to db.editionDao().getRecent(userId, 50).map { e ->
                e.copy(id = 0, userId = 0, changeSeq = 0) to db.editionDao().getItems(userId, e.id).map { it.row.article.guid to it.item.copy(editionId = 0, articleId = 0) }
            },
        )
    }

    @Test
    fun intoAnEmptyAccount() = runBlocking<Unit> {
        val progress = mutableListOf<Pair<Int, Int>>()
        val report = import(Archive.library().bytes()) { done, total -> progress += done to total }
        assertEquals(ImportReportDto(feeds = 2, posts = 12, skippedPosts = 0, editions = 3, skippedEditions = 0), report)
        assertEquals(12 to 12, progress.last())

        // Subscriptions to the shared feeds, with the file's tags and slider.
        val feeds = db.editionArticleDao().subscribedFeeds(user).associateBy { it.title }
        assertEquals(setOf("A Blog", "The News"), feeds.keys)
        assertEquals(SliderStep.FAVOURITE, feeds.getValue("A Blog").sliderStep)
        assertEquals(day(0), feeds.getValue("A Blog").addedAt)
        assertEquals(listOf("Essays", "Tech"), db.editionArticleDao().tagLinks(user).map { it.name }.sorted())

        // Bodies, the fetched full text and engagement came too.
        assertEquals(ContentSource.FETCHED, db.importDao().contentSource(blogPost("blog-2")))
        assertEquals(ContentSource.FEED, db.importDao().contentSource(blogPost("blog-3")))
        assertNull(db.importDao().contentSource(newsPost("news-5")))
        val liked = db.engagementDao().get(user, blogPost("blog-1"))!!
        assertEquals(day(2), liked.likedAt)
        assertEquals(0.9f, liked.readFraction)
        assertEquals(day(3), db.engagementDao().get(user, newsPost("news-1"))!!.savedAt)

        // Editions with their numbers, items and reading state.
        val third = db.editionDao().getByDate(user, LocalDate.of(2026, 10, 13), EditionKind.MORNING)!!
        assertEquals(3, third.number)
        assertEquals(
            listOf("blog-3" to ItemKind.LEAD, "news-3" to ItemKind.FRONT, "news-4" to ItemKind.INDEX),
            db.editionDao().getItems(user, third.id).map { it.row.article.guid to it.item.kind },
        )
        val first = db.editionDao().getByDate(user, LocalDate.of(2026, 10, 11), EditionKind.MORNING)!!
        assertEquals(day(1).plusSeconds(600), first.finishedAt)
        // Imported affinity learning ran: the Liked blog has moved off the start.
        assertTrue(db.importDao().subscription(user, feedId(BLOG))!!.affinity != 0.5)
    }

    @Test
    fun anEditionRecordsPageViewsOnlyWhenItsLineCarriesThem() = runBlocking<Unit> {
        import(Archive.library().bytes())
        val editions = (1L..3L).map { db.editionDao().getByDate(user, LocalDate.of(2026, 10, 10).plusDays(it), EditionKind.MORNING)!! }
        // The first carries views; the second says untracked; the third says tracked but has none.
        assertEquals(listOf(true, false, false), editions.map { it.pageViewsTracked })
        val views = db.editionDao().getPageViews(user, editions[0].id)
        assertEquals(listOf(1 to 12_000L, 2 to 3_000L), views.map { it.page to it.msVisible })
        assertEquals(day(1).plusSeconds(61), views[0].firstSeenAt)
    }

    @Test
    fun importingTwiceChangesNothing() = runBlocking<Unit> {
        val file = Archive.library().bytes()
        import(file)
        val before = snapshot()
        val seq = db.userStateDao().changeSeq(user)

        val again = import(file)
        assertEquals(ImportReportDto(feeds = 0, posts = 0, skippedPosts = 12, editions = 0, skippedEditions = 3), again)
        assertEquals(before, snapshot())
        assertEquals(3, db.editionDao().count(user))
        assertEquals(2, db.editionArticleDao().countSubscribed(user))
        // Nothing the engagement delta shows was changed.
        assertTrue(db.engagementDao().changedSince(user, seq).isEmpty())
    }

    @Test
    fun mergingIntoAnAccountThatHoldsPartOfTheDataAddsOnlyWhatIsMissing() = runBlocking<Unit> {
        // The account subscribes to the news feed under a differently written address, which the
        // server has polled itself, and built the edition of 11 October on its own.
        val news = world.addGlobalFeed("News (renamed)", url = "https://news.example/rss/")
        world.subscribe(user, news, addedAt = day(5), tags = listOf("World"))
        val local = (1..4).associate { n ->
            "news-$n" to db.importDao().insertNew(
                net.tgnra.morningissue.server.db.ArticleEntity(
                    feedId = news, guid = "news-$n", url = "https://news.example/news-$n", title = "Local headline $n",
                    publishedAt = day(20), firstSeenAt = day(20),
                ),
                body("Local body of news-$n"),
                "Local body of news-$n",
            )!!
        }
        val ownEdition = db.editionDao().insertEditionWithItems(
            net.tgnra.morningissue.server.db.EditionEntity(
                userId = user, date = LocalDate.of(2026, 10, 11), kind = EditionKind.MORNING, cutoffAt = day(20), releaseAt = day(20),
                builtAt = day(20), length = EditionLength.SHORT, number = 40,
            ),
            listOf(net.tgnra.morningissue.server.db.EditionItemEntity(0, local.getValue("news-3"), 1, null, 0, ItemKind.LEAD, 0.5)),
        )
        // Local engagement: news-1 was Liked here (the archive has it saved); news-2 opened and read here.
        world.like(user, local.getValue("news-1"), day(25))
        world.open(user, local.getValue("news-2"), day(26))
        world.read(user, local.getValue("news-2"), day(26))

        val report = import(Archive.library().bytes())
        assertEquals(ImportReportDto(feeds = 1, posts = 8, skippedPosts = 4, editions = 2, skippedEditions = 1), report)

        // No duplicates: the news feed is the one already held, keeping its address, name and tags.
        assertEquals(2, db.editionArticleDao().countSubscribed(user))
        val feed = db.editionArticleDao().feed(user, news)!!
        assertEquals("News (renamed)", feed.title)
        assertNull(db.importDao().feedIdByUrl(NEWS))
        assertEquals(listOf("World"), db.editionArticleDao().tagLinks(user).filter { it.feedId == news }.map { it.name })
        // The missing feed came with its tags and slider.
        val blog = db.editionArticleDao().feed(user, feedId(BLOG))!!
        assertEquals(SliderStep.FAVOURITE, blog.sliderStep)

        // Posts already held are left as they were, text included; missing ones arrive under the held feed.
        val kept = db.importDao().article(local.getValue("news-1"))!!
        assertEquals("Local headline 1" to day(20), kept.title to kept.firstSeenAt)
        assertEquals(ContentSource.FEED, db.importDao().contentSource(kept.id))
        assertNotNull(db.importDao().articleByGuid(news, "news-6"))

        // Engagement: nothing set locally is overwritten; what was missing is filled in.
        val one = db.engagementDao().get(user, local.getValue("news-1"))!!
        assertEquals("the local Like stands", day(25), one.likedAt)
        assertEquals("the archive's save fills the gap", day(3), one.savedAt)
        val two = db.engagementDao().get(user, local.getValue("news-2"))!!
        assertEquals(day(26), two.openedAt)
        assertEquals(day(26), two.readAt)

        // Editions: the account's own for 11 October is untouched; the others point at the right posts.
        assertEquals(3, db.editionDao().count(user))
        val own = db.editionDao().getById(user, ownEdition)!!
        assertEquals(EditionLength.SHORT to 40, own.length to own.number)
        val third = db.editionDao().getByDate(user, LocalDate.of(2026, 10, 13), EditionKind.MORNING)!!
        assertEquals(local.getValue("news-3"), db.editionDao().getItems(user, third.id)[1].row.article.id)
    }

    @Test
    fun postsAreSharedButEngagementEditionsAndSubscriptionsAreTheUsers() = runBlocking<Unit> {
        val other = world.addUser()
        val file = Archive.library().bytes()
        import(file)
        val second = import(file, userId = other)
        // The posts were already on the server; the other user still gets their subscriptions and editions.
        assertEquals(ImportReportDto(feeds = 2, posts = 0, skippedPosts = 12, editions = 3, skippedEditions = 0), second)
        assertEquals(snapshot(user), snapshot(other))
        // Changing one user's state leaves the other's alone.
        world.save(other, blogPost("blog-4"), day(10))
        assertNull(db.engagementDao().get(user, blogPost("blog-4")))
        val theirs = db.editionDao().getRecent(other, 10).map { it.id }.toSet()
        assertTrue(db.editionDao().getRecent(user, 10).none { it.id in theirs })
    }

    @Test
    fun aFetchedTextFillsInWhatTheServerLacks() = runBlocking<Unit> {
        // Another user's subscription brought news-2 in with only its feed excerpt, and news-5 with none.
        val other = world.addUser()
        val news = world.addGlobalFeed("The News", url = NEWS)
        world.subscribe(other, news)
        val excerpt = db.importDao().insertNew(
            net.tgnra.morningissue.server.db.ArticleEntity(feedId = news, guid = "news-2", url = null, title = "Excerpt", publishedAt = day(2), firstSeenAt = day(2)),
            body("Excerpt"),
            "Excerpt",
        )!!
        val bare = db.importDao().insertNew(
            net.tgnra.morningissue.server.db.ArticleEntity(feedId = news, guid = "news-5", url = null, title = "Bare", publishedAt = day(5), firstSeenAt = day(5)),
        )!!
        val archive = Archive.library()
        val file = archive.copy(
            posts = archive.posts.map {
                if (it.guid == "news-2") it.copy(content = net.tgnra.morningissue.data.archive.ArchiveContent(body("Full text of news-2"), "FETCHED", ms(4))) else it
            },
        ).bytes()

        import(file)
        assertEquals(ContentSource.FETCHED, db.importDao().contentSource(excerpt))
        // The archive has no body for news-5 either.
        assertNull(db.importDao().contentSource(bare))
        // A feed excerpt in the archive does not replace a fetched text held.
        val fetchedFirst = archive.copy(
            posts = archive.posts.map {
                if (it.guid == "news-2") it.copy(content = net.tgnra.morningissue.data.archive.ArchiveContent(body("Another excerpt"), "FEED", ms(9))) else it
            },
        ).bytes()
        import(fetchedFirst)
        assertEquals(ContentSource.FETCHED, db.importDao().contentSource(excerpt))
    }

    @Test
    fun aRemovedLikeComesBackOnlyWhereNothingLocalSaysOtherwise() {
        val archived = ArchiveEngagement(openedAt = 1000, readFraction = 0.4f, readAt = 2000, likedAt = 3000, editionsRunUnopened = 1, leadCount = 2)
        val fresh = ArchiveImporter.mergeEngagement(5, 7, null, archived)
        assertEquals(Instant.ofEpochMilli(3000), fresh.likedAt)
        assertEquals(7L to 5L, fresh.articleId to fresh.userId)

        val local = fresh.copy(openedAt = Instant.ofEpochMilli(500), readFraction = 0.8f, likedAt = Instant.ofEpochMilli(9000), savedAt = Instant.ofEpochMilli(9500), leadCount = 1)
        assertEquals(local.copy(leadCount = 2), ArchiveImporter.mergeEngagement(5, 7, local, archived))
        assertEquals(EngagementEntity(userId = 5, articleId = 7, readFraction = 1f), ArchiveImporter.mergeEngagement(5, 7, null, ArchiveEngagement(readFraction = 3f)))
    }

    @Test
    fun aFileThatIsNotAnArchiveIsRefusedAndNothingIsImported() = runBlocking<Unit> {
        suspend fun reasonFor(file: ByteArray): ArchiveFormatException.Reason? = try {
            import(file)
            fail("imported")
            null
        } catch (e: ArchiveFormatException) {
            e.reason
        }
        assertEquals(ArchiveFormatException.Reason.NOT_AN_ARCHIVE, reasonFor("<opml/>".toByteArray()))
        assertEquals(ArchiveFormatException.Reason.NOT_AN_ARCHIVE, reasonFor(ByteArray(0)))
        assertEquals(ArchiveFormatException.Reason.NOT_AN_ARCHIVE, reasonFor(Archive.zipOf("notes.txt" to "hello")))
        assertEquals(
            ArchiveFormatException.Reason.NOT_AN_ARCHIVE,
            reasonFor(Archive.zipOf("manifest.json" to """{"format":"something-else","version":1,"exportedAt":0}""")),
        )
        assertEquals(ArchiveFormatException.Reason.NEWER_VERSION, reasonFor(Archive.library().copy(version = 3).bytes()))
        assertFalse(db.importDao().hasData(user))
    }

    @Test
    fun unknownFieldsAndDamagedLinesAreToleratedSoLaterVersionsCanAddToTheFormat() = runBlocking<Unit> {
        val file = Archive.zipOf(
            "manifest.json" to """{"format":"morning-issue-archive","version":1,"exportedAt":0,"posts":3,"madeBy":"1.4"}""",
            "feeds.jsonl" to """{"n":1,"url":"https://a.example/feed","title":"A","addedAt":0,"colour":"red"}""",
            "posts.jsonl" to """{"n":1,"feed":1,"guid":"g1","title":"One","publishedAt":0,"firstSeenAt":0,"mood":"sunny"}""" + "\n" +
                "{ this line was cut off\n" +
                """{"n":3,"feed":9,"guid":"orphan","title":"No such feed","publishedAt":0,"firstSeenAt":0}""" + "\n",
            "editions.jsonl" to """{"date":"2026-01-02","kind":"MORNING","cutoffAt":0,"releaseAt":0,"builtAt":0,"length":"STANDARD","number":1,""" +
                """"items":[{"post":1,"page":1,"slot":0,"kind":"LEAD","score":1.0},{"post":3,"page":1,"slot":1,"kind":"FRONT","score":0.5},""" +
                """{"post":1,"page":2,"slot":0,"kind":"HOLOGRAM","score":0.5}]}""" + "\n" +
                """{"date":"2026-01-03","kind":"MIDNIGHT","cutoffAt":0,"releaseAt":0,"builtAt":0,"length":"STANDARD","number":2}""",
        )
        val report = import(file)
        assertEquals(ImportReportDto(feeds = 1, posts = 1, skippedPosts = 2, editions = 1, skippedEditions = 1), report)
        val edition = db.editionDao().getByDate(user, LocalDate.of(2026, 1, 2), EditionKind.MORNING)!!
        val items = db.editionDao().getItems(user, edition.id)
        assertEquals(listOf(ItemKind.LEAD), items.map { it.item.kind })
        assertNull(db.importDao().contentSource(items.single().item.articleId))
    }

    @Test
    fun importedCategoriesAndEnclosuresFillInWhatAHeldPostLacksAndReplaceNothing() = runBlocking<Unit> {
        val archive = Archive.library()
        val file = archive.copy(
            posts = archive.posts.map {
                when (it.guid) {
                    "blog-1" -> it.copy(categories = listOf(Archive.category("tech", "Tech"), Archive.category("long-reads", "Long Reads")), enclosure = "AUDIO")
                    "blog-2" -> it.copy(categories = listOf(Archive.category("world", "World")), enclosure = "VIDEO")
                    "blog-3" -> it.copy(enclosure = "NONE")
                    else -> it
                }
            },
        ).bytes()
        import(file)
        val blog1 = blogPost("blog-1")
        assertEquals(2, db.importDao().categoryCount(blog1))
        assertEquals(EnclosureKind.AUDIO, db.importDao().traits(blog1)!!.enclosure)
        // No enclosure, or NONE, writes no traits: W-A's upkeep works them out.
        assertNull(db.importDao().traits(blogPost("blog-3")))

        // Imported again with different signals: what is held stays.
        val again = archive.copy(
            posts = archive.posts.map {
                if (it.guid == "blog-1") it.copy(categories = listOf(Archive.category("cooking", "Cooking")), enclosure = "VIDEO") else it
            },
        ).bytes()
        import(again)
        assertEquals(2, db.importDao().categoryCount(blog1))
        assertEquals(EnclosureKind.AUDIO, db.importDao().traits(blog1)!!.enclosure)
    }

    @Test
    fun aVersion1ArchiveImportsEveryFeedAsASubscription() = runBlocking<Unit> {
        val file = Archive.zipOf(
            "manifest.json" to """{"format":"morning-issue-archive","version":1,"exportedAt":0}""",
            "feeds.jsonl" to """{"n":1,"url":"https://a.example/feed","title":"A","addedAt":0}""" + "\n" +
                """{"n":2,"url":"https://b.example/feed","title":"B","addedAt":0}""",
        )
        assertEquals(2, import(file).feeds)
        assertEquals(2, db.editionArticleDao().countSubscribed(user))
    }

    @Test
    fun aDiscoveredFeedStaysDiscoveredAndASubscriptionInTheFileSubscribesIt() = runBlocking<Unit> {
        val discovered = ArchiveFeed(1, "https://d.example/feed", "D", addedAt = ms(0), status = "DISCOVERED", discoveredAt = ms(2))
        val muted = ArchiveFeed(2, "https://m.example/feed", "M", addedAt = ms(0), muteUntil = ms(40))
        val report = import(Archive(listOf(discovered, muted), emptyList(), emptyList()).bytes())
        assertEquals("a discovered feed is not a subscription", 1, report.feeds)
        val sub = db.importDao().subscription(user, feedId("https://d.example/feed"))!!
        assertEquals(FeedStatus.DISCOVERED, sub.status)
        assertEquals(day(2), sub.discoveredAt)
        assertEquals(day(40), db.importDao().subscription(user, feedId("https://m.example/feed"))!!.muteUntil)

        // The same feed, subscribed in a later file: the account subscribes.
        val promoted = import(Archive(listOf(discovered.copy(status = "SUBSCRIBED")), emptyList(), emptyList()).bytes())
        assertEquals(1, promoted.feeds)
        assertEquals(FeedStatus.SUBSCRIBED, db.importDao().subscription(user, feedId("https://d.example/feed"))!!.status)
    }

    // --- Settings ----------------------------------------------------------------------------

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
    fun anEmptyAccountTakesThePhonesSettings() = runBlocking<Unit> {
        val report = import(Archive.library(settings = phone).bytes())
        assertTrue(report.settingsApplied)
        val stored = world.settings.get(user)
        assertEquals("05:45", stored.resetTime)
        assertEquals(EditionLength.LONG, stored.editionLength)
        assertTrue(stored.eveningEnabled)
        assertEquals("19:15", stored.eveningTime)
        assertEquals("Europe/London", stored.timeZone)
        assertTrue(stored.onboardingDone)
        assertEquals(listOf(ms(28)), stored.nothingNewFor)
        assertEquals(phone.resetChange, stored.resetChange)
        assertTrue(world.settings.isCustomized(user))

        // A second import keeps what the account now has.
        val again = import(Archive.library(settings = phone.copy(resetTime = "08:00")).bytes())
        assertFalse(again.settingsApplied)
        assertEquals("05:45", world.settings.get(user).resetTime)
    }

    @Test
    fun anAccountInUseKeepsItsOwnSettings() = runBlocking<Unit> {
        val settings = SettingsServiceImpl(db, world.settings, world.clock)
        settings.setReleaseTimes(user, ReleaseTimesRequest(resetTime = LocalTime.of(7, 0)))
        settings.patch(user, SettingsPatch(editionLength = EditionLength.SHORT))
        val before = world.settings.get(user)

        val report = import(Archive.library(settings = phone).bytes())
        assertFalse(report.settingsApplied)
        assertEquals(before, world.settings.get(user))
        // The rest of the archive came in all the same.
        assertEquals(12, report.posts)
    }

    @Test
    fun theTimeZoneThePhoneSendsAtLoginDoesNotCountAsSettingsOfItsOwn() = runBlocking<Unit> {
        SettingsServiceImpl(db, world.settings, world.clock).patch(user, SettingsPatch(timeZone = "America/New_York"))
        assertFalse(world.settings.isCustomized(user))
        assertTrue(import(Archive.library(settings = phone).bytes()).settingsApplied)
        assertEquals("Europe/London", world.settings.get(user).timeZone)
    }

    @Test
    fun unreadableSettingsAreNotApplied() = runBlocking<Unit> {
        val report = import(Archive.library(settings = phone.copy(resetTime = "quarter to six")).bytes())
        assertFalse(report.settingsApplied)
        assertFalse(world.settings.isCustomized(user))
        // An unknown zone keeps the account's, the rest applies.
        val zoned = import(Archive.library(settings = phone.copy(timeZone = "Mars/Olympus")).bytes())
        assertTrue(zoned.settingsApplied)
        assertEquals(world.settings.defaults().timeZone, world.settings.get(user).timeZone)
        assertEquals("05:45", world.settings.get(user).resetTime)
    }

    private companion object {
        const val BLOG = "https://blog.example/feed.xml"
        const val NEWS = "http://www.news.example/rss"
    }
}
