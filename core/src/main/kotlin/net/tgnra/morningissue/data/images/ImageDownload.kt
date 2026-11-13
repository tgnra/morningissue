package net.tgnra.morningissue.data.images

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * How a picture is downloaded, with the store's limits: the image store's own fetches and the
 * Kobo sync's (`kobo/KoboImages`) both come through here, so a Kobo is never sent something
 * the reader would not have loaded.
 */
object ImageDownload {
    /** [shared] with the store's whole-download timeout. */
    fun client(shared: OkHttpClient): OkHttpClient =
        shared.newBuilder().callTimeout(ImageStoreConfig.DOWNLOAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).build()

    fun isWebAddress(url: String): Boolean = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    /**
     * Streams [url] to [target] with [client]; false for an error status, an empty body or one
     * over `ImageStoreConfig.MAX_DOWNLOAD_BYTES`. Throws on a network failure. Cancelling the
     * coroutine cancels the call, since a thread blocked reading a socket ignores interruption.
     */
    suspend fun toFile(client: OkHttpClient, url: String, target: File): Boolean = coroutineScope {
        // No Accept header: the bytes are the ones display would have been given.
        val call = client.newCall(Request.Builder().url(url).build())
        val canceller = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            runInterruptible(Dispatchers.IO) { read(call, target) }
        } finally {
            canceller.cancel()
        }
    }

    private fun read(call: Call, target: File): Boolean {
        call.execute().use { response ->
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
}
