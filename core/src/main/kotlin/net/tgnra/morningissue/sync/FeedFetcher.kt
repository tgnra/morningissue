package net.tgnra.morningissue.sync

import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

sealed interface FetchResult {
    /** A 2xx answer with its body. */
    class Success(
        val body: ByteArray,
        val status: Int,
        /** The media type without parameters, lower-cased; null when the server sent none. */
        val contentType: String?,
        /** The charset named in the Content-Type header, if any. */
        val charset: Charset?,
        /** Where the body finally came from, after any redirects. */
        val finalUrl: String,
        override val permanentUrl: String?,
        /** Validators for the next conditional request. */
        val etag: String?,
        val lastModified: String?,
    ) : FetchResult, Answered {
        /** The body as text; see [XmlText.decode] for how the charset is chosen. */
        fun text(): String = XmlText.decode(body, charset)
    }

    /** 304: the document has not changed since the validators were issued. */
    data class NotModified(override val permanentUrl: String?) : FetchResult, Answered

    /** The server answered with an error status. */
    data class HttpError(val status: Int) : FetchResult

    /** No usable answer: offline, DNS, TLS, timeout, or a body over the size cap. */
    data class NetworkError(val message: String) : FetchResult

    interface Answered {
        /**
         * Set when the request was permanently redirected (301 or 308): the address the
         * server says to use from now on. Temporary redirects are followed but not reported.
         */
        val permanentUrl: String?
    }
}

/**
 * HTTP GET for feeds and pages on the shared OkHttp client: conditional requests with ETag and
 * Last-Modified, a body size cap, redirect tracking, and errors as values. Never throws except
 * on cancellation, which also cancels the call.
 */
class FeedFetcher(client: OkHttpClient) {
    private val client: OkHttpClient = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        // The shared client allows 60 s for a whole call; a feed check should give up sooner.
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(
        url: String,
        etag: String? = null,
        lastModified: String? = null,
        accept: String = ACCEPT_FEED,
        maxBytes: Long = MAX_FEED_BYTES,
    ): FetchResult {
        val httpUrl = url.trim().toHttpUrlOrNull() ?: return FetchResult.NetworkError("Not a web address")
        val result = execute(httpUrl, etag, lastModified, accept, maxBytes)
        // Android refuses plain http unless the manifest allows it; most such hosts also serve https.
        if (result is FetchResult.NetworkError && result.message == CLEARTEXT_BLOCKED) {
            return execute(httpUrl.newBuilder().scheme("https").build(), etag, lastModified, accept, maxBytes)
        }
        return result
    }

    private suspend fun execute(url: HttpUrl, etag: String?, lastModified: String?, accept: String, maxBytes: Long): FetchResult {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .apply {
                if (!etag.isNullOrBlank()) header("If-None-Match", etag)
                if (!lastModified.isNullOrBlank()) header("If-Modified-Since", lastModified)
            }
            .build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(failure(e, url))
                }

                override fun onResponse(call: Call, response: Response) {
                    // The body is read here, on OkHttp's thread, so nothing is left to close later.
                    val result = try {
                        response.use { read(it, maxBytes) }
                    } catch (e: IOException) {
                        failure(e, url)
                    }
                    continuation.resume(result)
                }
            })
        }
    }

    private fun read(response: Response, maxBytes: Long): FetchResult {
        val permanentUrl = permanentRedirectTarget(response)
        if (response.code == 304) return FetchResult.NotModified(permanentUrl)
        if (!response.isSuccessful) return FetchResult.HttpError(response.code)

        val body = response.body
        val buffer = Buffer()
        val source = body.source()
        while (buffer.size <= maxBytes) {
            if (source.read(buffer, 16 * 1024L) == -1L) break
        }
        if (buffer.size > maxBytes) return FetchResult.NetworkError("The document is too large")

        val mediaType = body.contentType()
        return FetchResult.Success(
            body = buffer.readByteArray(),
            status = response.code,
            contentType = mediaType?.let { "${it.type}/${it.subtype}".lowercase() },
            charset = runCatching { mediaType?.charset() }.getOrNull(),
            finalUrl = response.request.url.toString(),
            permanentUrl = permanentUrl,
            etag = response.header("ETag"),
            lastModified = response.header("Last-Modified"),
        )
    }

    /**
     * Follows the chain of redirects from the first request: while each hop is permanent
     * (301/308) the target moves along; the first temporary hop ends it.
     */
    private fun permanentRedirectTarget(response: Response): String? {
        val chain = generateSequence(response) { it.priorResponse }.toList().asReversed()
        var target: String? = null
        for (i in 0 until chain.size - 1) {
            if (chain[i].code != 301 && chain[i].code != 308) break
            target = chain[i + 1].request.url.toString()
        }
        return target
    }

    private fun failure(e: IOException, url: HttpUrl): FetchResult = when (e) {
        is UnknownServiceException -> FetchResult.NetworkError(if (url.isHttps) "Could not connect to ${url.host}" else CLEARTEXT_BLOCKED)
        is UnknownHostException -> FetchResult.NetworkError("Could not find ${url.host}")
        is InterruptedIOException -> FetchResult.NetworkError("${url.host} took too long to answer")
        is SSLException -> FetchResult.NetworkError("Could not make a secure connection to ${url.host}")
        else -> FetchResult.NetworkError("Could not connect to ${url.host}")
    }

    companion object {
        const val ACCEPT_FEED =
            "application/rss+xml, application/atom+xml, application/feed+json, application/xml;q=0.9, " +
                "text/xml;q=0.9, application/json;q=0.8, */*;q=0.5"
        const val ACCEPT_PAGE = "text/html, application/xhtml+xml;q=0.9, */*;q=0.5"

        /** Feeds are small; anything bigger is a mistake or an attack on memory. */
        const val MAX_FEED_BYTES = 8L * 1024 * 1024
        const val MAX_PAGE_BYTES = 5L * 1024 * 1024

        private const val CLEARTEXT_BLOCKED = "Insecure http addresses are not allowed"
        private const val CALL_TIMEOUT_SECONDS = 45L
        private const val USER_AGENT = "MorningIssue/1.0 (Android; RSS reader)"
    }
}
