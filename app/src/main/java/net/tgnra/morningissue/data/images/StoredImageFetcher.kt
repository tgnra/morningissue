package net.tgnra.morningissue.data.images

import coil3.Extras
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.Options
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.sink

/**
 * How display finds stored images. Registered with the app's Coil loader ahead of the network
 * fetcher, it answers every request for an `http(s)` address: with the stored file when there
 * is one, and with nothing when there is not, at which Coil moves on to the next fetcher, the
 * network one, and its own disk cache. So every `AsyncImage` in the app reads the store first
 * without knowing it exists.
 *
 * The lookup is one file check on Coil's fetcher thread (the file's name is derived from the
 * address, `ImageFiles.nameFor`); the database is not read. A request marked [ORIGINAL] is not
 * answered, so that "tap to see the original" really loads the original.
 */
class StoredImageFetcher(private val file: File) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        if (!file.isFile) return null
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val files: ImageFiles) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (options.getExtra(ORIGINAL)) return null
            return StoredImageFetcher(files.fileFor(data.toString()))
        }
    }

    companion object {
        /** Set on a request that must be given the image as its site serves it, not the stored copy. */
        val ORIGINAL: Extras.Key<Boolean> = Extras.Key(default = false)

        /** Marks the request as one for the original, with a memory-cache key of its own. */
        fun ImageRequest.Builder.original(): ImageRequest.Builder = apply {
            extras[ORIGINAL] = true
            memoryCacheKeyExtra("original", "true")
        }
    }
}

/**
 * Coil's disk cache as an [OriginalSource]: an image the reader has just been shown over the
 * network is stored from the bytes already on the device, and Coil's copy is then dropped so
 * the picture is held once.
 */
class CoilDiskOriginals(private val diskCache: () -> DiskCache?) : OriginalSource {
    override fun copyTo(url: String, target: File): Boolean = try {
        val cache = diskCache()
        cache?.openSnapshot(url)?.use { snapshot ->
            cache.fileSystem.source(snapshot.data).use { source ->
                target.sink().buffer().use { it.writeAll(source) }
            }
            true
        } ?: false
    } catch (_: Exception) {
        false
    }

    override fun forget(url: String) {
        runCatching { diskCache()?.remove(url) }
    }
}
