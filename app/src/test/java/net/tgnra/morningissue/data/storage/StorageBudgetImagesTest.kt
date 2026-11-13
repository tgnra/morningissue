package net.tgnra.morningissue.data.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.StoredImageEntity
import net.tgnra.morningissue.data.db.T0
import net.tgnra.morningissue.data.db.article
import net.tgnra.morningissue.data.db.feed
import net.tgnra.morningissue.data.images.FileImageStore
import net.tgnra.morningissue.data.images.ImageFiles
import net.tgnra.morningissue.data.images.ImageSweepReport
import net.tgnra.morningissue.edition.FakeSettings
import net.tgnra.morningissue.edition.MutableClock
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.RichText
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The storage budget with images in it: what is counted, and what goes first. */
@RunWith(AndroidJUnit4::class)
class StorageBudgetImagesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = MutableClock(T0)
    private val settings = FakeSettings(AppSettings(storageBudgetMb = AppSettings.MIN_STORAGE_BUDGET_MB))
    private lateinit var db: MorningIssueDatabase
    private lateinit var files: ImageFiles
    private lateinit var images: FileImageStore
    private lateinit var storage: RoomStorageRepository
    private var feedId = 0L

    /** About a megabyte of body per post (half in content, half in the index), as in `StorageRepositoryTest`. */
    private val bodyText = "zeppelin ".repeat(1024 * 1024 / 9 / 2)
    private val blocksJson = ContentBlocks.encode(listOf(ContentBlock.Paragraph(RichText(bodyText))))

    @Before
    fun setUp() = runBlocking {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        files = ImageFiles(File(folder.root, "images"))
        images = FileImageStore(db, files, { OkHttpClient() }, clock)
        storage = RoomStorageRepository(db, settings, clock, images = images)
        feedId = db.feedDao().insert(feed())
    }

    @After
    fun tearDown() = db.close()

    private fun day(n: Int): Instant = T0.minus(Duration.ofDays(100L - n))

    private suspend fun post(guid: String, day: Int): Long =
        db.articleDao().insertNew(article(feedId, guid, summary = "Summary $guid", firstSeenAt = day(day)), blocksJson, bodyText)!!

    /** An image of [megabytes] in the store for [articleId], as the store itself would leave it. */
    private suspend fun image(articleId: Long, megabytes: Int, name: String = "lead"): String {
        val url = "https://img.example/$articleId/$name.jpg"
        files.directory.mkdirs()
        RandomAccessFile(files.fileFor(url), "rw").use { it.setLength(megabytes * MB) }
        db.storedImageDao().upsert(StoredImageEntity(url, articleId, files.nameFor(url), megabytes * MB, megabytes * MB * 4, 1280, 720, T0))
        return url
    }

    private suspend fun edition(day: Int, vararg articleIds: Long): Long = db.editionDao().insertEditionWithItems(
        EditionEntity(
            date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()), kind = EditionKind.MORNING, cutoffAt = day(day),
            releaseAt = day(day), builtAt = day(day), length = EditionLength.STANDARD, number = day,
        ),
        articleIds.mapIndexed { slot, id -> EditionItemEntity(0, id, 1, null, slot, if (slot == 0) ItemKind.LEAD else ItemKind.FRONT, 1.0) },
    )

    private suspend fun hasBody(articleId: Long) = db.articleDao().getContent(articleId) != null

    private fun hasImage(url: String) = files.fileFor(url).isFile

    @Test
    fun usageCountsTextAndImagesAndSaysHowMuchIsEach() = runBlocking<Unit> {
        val ids = (1..4).map { post("p$it", it) }
        image(ids[0], 3)
        image(ids[1], 2)

        val usage = storage.usage()

        assertEquals(5 * MB, usage.imageBytes)
        assertTrue("about 4 MB of text: ${usage.textBytes}", usage.textBytes in 3_600_000L..4_600_000L)
        assertEquals(usage.textBytes + usage.imageBytes, usage.usedBytes)
        assertFalse(usage.overBudget)
        assertEquals(TrimReport(), storage.trimToBudget())
    }

    @Test
    fun overBudgetTheOldestEditionsImagesGoFirstAndNoTextIsTouchedWhileImagesAreEnough() = runBlocking<Unit> {
        // 12 posts: 12 MB of text and 12 images of 4 MB. 60 MB against a budget of 50.
        val ids = (1..12).map { post("p$it", it) }
        val urls = ids.map { image(it, 4) }
        (1..12).forEach { edition(it, ids[it - 1]) }
        // The oldest post is Liked and the second saved: their pictures stay.
        db.engagementDao().setLiked(ids[0], T0)
        db.engagementDao().setSaved(ids[1], T0)
        assertTrue(storage.usage().overBudget)

        val report = storage.trimToBudget()

        // Down to 45 MB: 15 MB to free, which is four images of 4 MB. The oldest four that may go.
        assertEquals(4, report.imagesTrimmed)
        assertEquals(0, report.articlesTrimmed)
        assertEquals(16 * MB, report.bytesFreed)
        assertEquals(listOf(2, 3, 4, 5), urls.indices.filterNot { hasImage(urls[it]) })
        assertTrue(ids.all { hasBody(it) })
        assertEquals(8, db.storedImageDao().count())
        val usage = storage.usage()
        assertEquals(32 * MB, usage.imageBytes)
        assertTrue(usage.usedBytes <= 45 * MB)
        // Rows and files went together.
        assertEquals(ImageSweepReport(), images.sweep())
        assertEquals(TrimReport(), storage.trimToBudget())
    }

    @Test
    fun textIsTrimmedOnlyOnceEveryImageThatMayGoHasGone() = runBlocking<Unit> {
        // 58 MB of text and two 3 MB images on the two oldest posts, plus one on a Liked post.
        val ids = (1..58).map { post("p$it", it) }
        val first = image(ids[0], 3)
        val second = image(ids[1], 3)
        val liked = image(ids[2], 3)
        db.engagementDao().setLiked(ids[2], T0)
        (1..58).forEach { edition(it, ids[it - 1]) }

        val report = storage.trimToBudget()

        assertEquals(2, report.imagesTrimmed)
        assertFalse(hasImage(first))
        assertFalse(hasImage(second))
        // 67 MB to 45: 6 MB came from images, the rest from the oldest bodies.
        assertTrue("${report.articlesTrimmed}", report.articlesTrimmed in 15..18)
        val trimmed = ids.filterNot { hasBody(it) }
        assertEquals((ids.take(2) + ids.drop(3)).take(report.articlesTrimmed), trimmed)
        // The Liked post keeps both.
        assertTrue(hasImage(liked))
        assertTrue(hasBody(ids[2]))
        assertTrue(storage.usage().usedBytes <= 45 * MB)
    }

    @Test
    fun exportAndFreeUpSpaceDeletesEveryImageThatMayGoFilesAndRows() = runBlocking<Unit> {
        val old = (1..3).map { post("old$it", it) }
        val oldImages = old.map { image(it, 1) } + image(old[0], 1, name = "body")
        val saved = post("saved", 4)
        val savedImage = image(saved, 1)
        db.engagementDao().setSaved(saved, T0)
        val previous = post("previous", 97)
        val previousImage = image(previous, 1)
        val latest = post("latest", 98)
        val latestImage = image(latest, 1)
        // Not in an edition yet: no longer kept for the next one, which the server prints.
        val unprinted = post("unprinted", 99)
        val unprintedImage = image(unprinted, 1)
        edition(5, old[0], old[1], old[2], saved)
        edition(97, previous)
        edition(98, latest)

        val report = storage.trimAll()

        assertEquals(5, report.imagesTrimmed)
        assertEquals(4, report.articlesTrimmed)
        assertTrue((oldImages + unprintedImage).none { hasImage(it) })
        // Saved, or in the two newest editions: kept.
        assertTrue(listOf(savedImage, previousImage, latestImage).all { hasImage(it) })
        assertEquals(3, db.storedImageDao().count())
        assertEquals(3, files.directory.listFiles().orEmpty().count { it.isFile })
        assertEquals(3 * MB, storage.usage().imageBytes)
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
