package net.tgnra.morningissue.embedding

import android.content.Context
import android.content.res.AssetManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The app's one [StaticEmbedder], loaded from the bundled asset the first time it is asked for.
 * Hold one instance per process.
 *
 * Loading is lazy because most launches never embed anything: only the sync job does, for new
 * posts. It runs on [dispatcher], never on the caller's thread, and happens once; callers that
 * arrive while it is under way wait for the same result.
 *
 * The asset is **memory-mapped** straight out of the APK, which is why `app/build.gradle.kts`
 * stores `.bin` assets uncompressed. Mapped, the 7.5 MB of vectors cost no Java heap, are paged
 * in only where a post's words touch them, and can be dropped by the system under memory
 * pressure and read again. Only the vocabulary, about 1 MB, is built on the heap. If the asset
 * cannot be mapped (it was compressed after all), it is read onto the heap instead, about 8 MB;
 * slower to load and heavier, but correct.
 *
 * A failed load is not remembered: the next call tries again and throws again.
 */
class EmbedderAsset(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val assets: AssetManager = context.applicationContext.assets
    private val mutex = Mutex()

    @Volatile
    private var loaded: StaticEmbedder? = null

    /**
     * The embedder, loading it first if need be.
     *
     * @throws IOException if the asset is missing or unreadable.
     * @throws IllegalArgumentException if it is not the asset for [StaticEmbedder.MODEL_ID].
     */
    suspend fun get(): StaticEmbedder = loaded ?: mutex.withLock {
        loaded ?: withContext(dispatcher) { StaticEmbedder.load(map() ?: read()) }.also { loaded = it }
    }

    /** The asset mapped read-only, or null if it is stored compressed and has no file region. */
    private fun map(): ByteBuffer? = try {
        assets.openFd(StaticEmbedder.ASSET_PATH).use { region ->
            // The mapping outlives the stream and the descriptor it was made from.
            region.createInputStream().use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, region.startOffset, region.declaredLength)
            }
        }
    } catch (_: IOException) {
        null
    }

    private fun read(): ByteBuffer =
        assets.open(StaticEmbedder.ASSET_PATH).use { ByteBuffer.wrap(it.readBytes()) }
}
