package net.tgnra.morningissue.data.images

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.tgnra.morningissue.data.db.ArticleSignalDao
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.db.StoredImageEntity
import net.tgnra.morningissue.data.db.StoredImageFileRow
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import okhttp3.OkHttpClient
import okhttp3.Request

/** What one call to [ImageStore.storeForArticles] did. */
data class ImageStoreReport(
    /** Images written by this call, re-encoded or as they came. */
    val stored: Int = 0,
    /** Of [stored], those kept as their original bytes. */
    val keptUnchanged: Int = 0,
    /** Images the store already held. */
    val alreadyStored: Int = 0,
    /** Images left to the network: too large to keep as they are, not an image, unreachable, too slow. */
    val skipped: Int = 0,
    val bytesDownloaded: Long = 0,
    val bytesStored: Long = 0,
)

data class ImageTrimReport(val imagesTrimmed: Int = 0, val bytesFreed: Long = 0)

data class ImageSweepReport(val rowsWithoutFile: Int = 0, val filesWithoutRow: Int = 0)

/**
 * Where the store's files are, and what each is called. A file's name is derived from the
 * image's address alone, so display can ask "is this address stored?" with one file check and
 * no database read.
 */
class ImageFiles(val directory: File) {
    /** Half-written downloads, apart from the stored files so that a listing of [directory] is the store. */
    val tempDirectory: File = File(directory, "tmp")

    fun nameFor(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(20).joinToString("") { "%02x".format(it) }

    fun fileFor(url: String): File = File(directory, nameFor(url))

    fun file(name: String): File = File(directory, name)

    fun newTemp(): File {
        tempDirectory.mkdirs()
        return File(tempDirectory, UUID.randomUUID().toString())
    }

    companion object {
        /** In the app's files, not its cache: Android clears the cache when the device runs short. */
        fun of(context: Context): ImageFiles = ImageFiles(File(context.filesDir, "images"))
    }
}

/**
 * Somewhere an image's original bytes may already be on the device, so that storing it needs
 * no second download. In the app this is Coil's disk cache (`CoilDiskOriginals`).
 */
interface OriginalSource {
    /** Copies the bytes for [url] to [target] and returns true, or returns false having written nothing useful. */
    fun copyTo(url: String, target: File): Boolean

    /** Called once [url] is in the store, so the source can stop keeping its own copy. */
    fun forget(url: String) = Unit

    object None : OriginalSource {
        override fun copyTo(url: String, target: File): Boolean = false
    }
}

/**
 * The image store: pictures kept in the app's files for display, re-encoded to fit
 * (`ImageRecompressor`), with a row each in `stored_image`. Implemented by [FileImageStore].
 *
 * Images are stored for the printed stories of an edition when it is prefetched and for an
 * article when it is opened in the reader. A picture merely passed in a list (a source page,
 * search, the Saved shelf) is not stored; Coil's own disk cache holds it for a while. Display
 * finds a stored image through `StoredImageFetcher` and falls back to the network.
 */
interface ImageStore {
    /**
     * Stores the lead image and the body images of each article that the store does not hold
     * yet: every lead image first, in the order given, then the body images. Never throws
     * except on cancellation; an image that fails is skipped.
     */
    suspend fun storeForArticles(articleIds: List<Long>): ImageStoreReport

    /** Bytes of every stored image, for the storage budget. */
    suspend fun totalBytes(): Long

    /**
     * Deletes stored images, the oldest editions' posts first, until [bytesToFree] are freed
     * or nothing more may go. Never an image of a Liked or saved post, of a post first seen at
     * or after [protectFrom], or of a story in [keepEditionIds].
     */
    suspend fun trim(protectFrom: Instant, keepEditionIds: List<Long>, bytesToFree: Long): ImageTrimReport

    /** Deletes the rows and files of every image of [feedId]'s posts; call before the feed is deleted. */
    suspend fun deleteForFeed(feedId: Long)

    /**
     * Deletes the rows and files of every image of [articleIds]; call before the posts are
     * deleted. At most `ArticleSignalDao.MAX_IDS` per call.
     */
    suspend fun deleteForArticles(articleIds: List<Long>) = Unit

    /** Removes rows whose file is gone, files that have no row, and stale half-written downloads. */
    suspend fun sweep(): ImageSweepReport
}

class FileImageStore(
    database: MorningIssueDatabase,
    private val files: ImageFiles,
    /** Asked for on first use, so that building the store does not build the client. */
    private val client: () -> OkHttpClient,
    private val clock: Clock,
    private val originals: OriginalSource = OriginalSource.None,
    private val recompressor: ImageRecompressor = ImageRecompressor(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageStore {
    private val dao = database.storedImageDao()

    /** Held while a file is moved into place and its row written, and by the sweep, so the sweep never sees one without the other. */
    private val commit = Mutex()
    private val downloads = Semaphore(ImageStoreConfig.PARALLEL_DOWNLOADS)
    private val recompressions = Semaphore(ImageStoreConfig.PARALLEL_RECOMPRESSIONS)

    private val imageClient: OkHttpClient by lazy {
        client().newBuilder().callTimeout(ImageStoreConfig.DOWNLOAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).build()
    }

    private enum class Outcome { RECOMPRESSED, UNCHANGED, SKIPPED }

    private class Result(val outcome: Outcome, val downloaded: Long = 0, val stored: Long = 0)

    override suspend fun storeForArticles(articleIds: List<Long>): ImageStoreReport = withContext(dispatcher) {
        val wanted = wantedImages(articleIds)
        if (wanted.isEmpty()) return@withContext ImageStoreReport()
        val held = wanted.keys.chunked(ArticleSignalDao.MAX_IDS).flatMap { dao.stored(it) }
            .filter { files.file(it.fileName).isFile }.mapTo(HashSet()) { it.url }
        val results = coroutineScope {
            wanted.filterKeys { it !in held }.map { (url, articleId) -> async { storeOne(url, articleId) } }.awaitAll()
        }
        ImageStoreReport(
            stored = results.count { it.outcome != Outcome.SKIPPED },
            keptUnchanged = results.count { it.outcome == Outcome.UNCHANGED },
            alreadyStored = held.size,
            skipped = results.count { it.outcome == Outcome.SKIPPED },
            bytesDownloaded = results.sumOf { it.downloaded },
            bytesStored = results.sumOf { it.stored },
        )
    }

    /** Address to owning article: lead images first, then body images; the first article to name an address owns it. */
    private suspend fun wantedImages(articleIds: List<Long>): Map<String, Long> {
        val ids = articleIds.distinct()
        val sources = ids.chunked(ArticleSignalDao.MAX_IDS).flatMap { dao.imageSources(it) }.associateBy { it.articleId }
        val wanted = LinkedHashMap<String, Long>()
        for (id in ids) sources[id]?.imageUrl?.takeIf(::isWebAddress)?.let { wanted.putIfAbsent(it, id) }
        for (id in ids) {
            val blocks = sources[id]?.blocksJson?.let(ContentBlocks::decode).orEmpty()
            blocks.asSequence().filterIsInstance<ContentBlock.Image>()
                .filter { (it.width ?: ImageStoreConfig.MIN_BODY_IMAGE_WIDTH_PX) >= ImageStoreConfig.MIN_BODY_IMAGE_WIDTH_PX }
                .map { it.url }.filter(::isWebAddress).distinct()
                .take(ImageStoreConfig.MAX_BODY_IMAGES_PER_ARTICLE)
                .forEach { wanted.putIfAbsent(it, id) }
        }
        return wanted
    }

    private fun isWebAddress(url: String): Boolean = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    private suspend fun storeOne(url: String, articleId: Long): Result {
        val original = files.newTemp()
        val encoded = files.newTemp()
        try {
            return fetchAndStore(url, articleId, original, encoded)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result(Outcome.SKIPPED)
        } finally {
            original.delete()
            encoded.delete()
        }
    }

    private suspend fun fetchAndStore(url: String, articleId: Long, original: File, encoded: File): Result {
        val fromCache = originals.copyTo(url, original) && original.length() in 1..ImageStoreConfig.MAX_DOWNLOAD_BYTES
        // The clock starts once the download has its turn, not while it waits behind others.
        val fetched = fromCache || downloads.withPermit {
            withTimeoutOrNull(ImageStoreConfig.DOWNLOAD_TIMEOUT_MILLIS) { runInterruptible { download(url, original) } } ?: false
        }
        if (!fetched) return Result(Outcome.SKIPPED)
        val downloaded = original.length()

        val head = original.inputStream().use { it.readNBytes(ImageFormats.HEAD_BYTES) }
        val kind = ImageFormats.kindOf(head)
        if (kind == ImageKind.NOT_AN_IMAGE) return Result(Outcome.SKIPPED, downloaded)
        // What cannot or must not be re-encoded is kept as it came, if it is small enough.
        val asItCame = kind == ImageKind.SVG || ImageFormats.isAnimated(kind, head, original)
        val recompressed = if (asItCame) null else recompressions.withPermit { runInterruptible { recompressor.recompress(original, encoded) } }
        // Not decodable here (an AVIF on an old Android): nothing in the app could show it either.
        if (!asItCame && recompressed == null) return Result(Outcome.SKIPPED, downloaded)

        val keepOriginal = recompressed == null || encoded.length() >= downloaded
        if (keepOriginal && downloaded > ImageStoreConfig.MAX_UNCHANGED_BYTES) return Result(Outcome.SKIPPED, downloaded)
        val chosen = if (keepOriginal) original else encoded
        val size = chosen.length()
        val (width, height) = when {
            recompressed == null -> 0 to 0
            keepOriginal -> boundsOf(original)
            else -> recompressed.width to recompressed.height
        }
        val row = StoredImageEntity(url, articleId, files.nameFor(url), size, downloaded, width, height, clock.instant())
        commit.withLock {
            val target = files.file(row.fileName)
            move(chosen, target)
            try {
                dao.upsert(row)
            } catch (e: Exception) {
                // The post went while its picture was on the way (its feed was removed).
                target.delete()
                throw e
            }
        }
        originals.forget(url)
        return Result(if (keepOriginal) Outcome.UNCHANGED else Outcome.RECOMPRESSED, downloaded, size)
    }

    private fun boundsOf(file: File): Pair<Int, Int> {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth.coerceAtLeast(0) to bounds.outHeight.coerceAtLeast(0)
    }

    /** Streams [url] to [target]; false for an error status, an empty body or one over the size limit. */
    private fun download(url: String, target: File): Boolean {
        // No Accept header: the bytes are the ones display would have been given.
        val request = Request.Builder().url(url).build()
        imageClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val stated = response.body.contentLength()
            if (stated > ImageStoreConfig.MAX_DOWNLOAD_BYTES) return false
            var total = 0L
            target.outputStream().use { out ->
                val input = response.body.byteStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > ImageStoreConfig.MAX_DOWNLOAD_BYTES) return false
                    out.write(buffer, 0, read)
                }
            }
            return total > 0
        }
    }

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return
        from.copyTo(to, overwrite = true)
        from.delete()
        if (!to.isFile) throw IOException("Could not store ${to.name}")
    }

    override suspend fun totalBytes(): Long = withContext(dispatcher) { dao.totalBytes() }

    override suspend fun trim(protectFrom: Instant, keepEditionIds: List<Long>, bytesToFree: Long): ImageTrimReport = withContext(dispatcher) {
        var freed = 0L
        var trimmed = 0
        while (freed < bytesToFree) {
            val batch = dao.trimCandidates(protectFrom, keepEditionIds, TRIM_BATCH)
            if (batch.isEmpty()) break
            val take = ArrayList<StoredImageFileRow>(batch.size)
            for (row in batch) {
                if (freed >= bytesToFree) break
                take += row
                freed += row.byteSize
            }
            delete(take)
            trimmed += take.size
        }
        ImageTrimReport(trimmed, freed)
    }

    override suspend fun deleteForFeed(feedId: Long) = withContext(dispatcher) { delete(dao.ofFeed(feedId)) }

    override suspend fun deleteForArticles(articleIds: List<Long>) = withContext(dispatcher) {
        if (articleIds.isNotEmpty()) delete(dao.ofArticles(articleIds))
    }

    /** Rows first: a row without a file is a failed lookup, a file without a row is space nobody counts. */
    private suspend fun delete(rows: List<StoredImageFileRow>) {
        if (rows.isEmpty()) return
        commit.withLock {
            rows.map { it.url }.chunked(ArticleSignalDao.MAX_IDS).forEach { dao.delete(it) }
            rows.forEach { files.file(it.fileName).delete() }
        }
    }

    override suspend fun sweep(): ImageSweepReport = withContext(dispatcher) {
        commit.withLock {
            val rows = dao.all()
            val gone = rows.filter { !files.file(it.fileName).isFile }
            gone.map { it.url }.chunked(ArticleSignalDao.MAX_IDS).forEach { dao.delete(it) }
            val known = rows.mapTo(HashSet()) { it.fileName }
            val strays = files.directory.listFiles().orEmpty().filter { it.isFile && it.name !in known }
            strays.forEach { it.delete() }
            // File times are the system's, whatever clock the store was given.
            val staleBefore = System.currentTimeMillis() - ImageStoreConfig.TEMP_MAX_AGE_MILLIS
            files.tempDirectory.listFiles().orEmpty().filter { it.lastModified() < staleBefore }.forEach { it.delete() }
            ImageSweepReport(rowsWithoutFile = gone.size, filesWithoutRow = strays.size)
        }
    }

    private companion object {
        const val TRIM_BATCH = 200
    }
}
