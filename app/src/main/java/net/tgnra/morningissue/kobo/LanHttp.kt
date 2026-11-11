package net.tgnra.morningissue.kobo

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/** One HTTP request. Header names are lowercased; [query] is decoded. */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

/** One HTTP response: [body], or the contents of [file] when it is set. */
class HttpResponse(
    val status: Int,
    val contentType: String? = null,
    val body: ByteArray = ByteArray(0),
    val file: File? = null,
) {
    companion object {
        fun json(status: Int, text: String) = HttpResponse(status, "application/json; charset=utf-8", text.toByteArray())

        /** A status with a one-word reason as JSON, for errors; the status is what the Kobo acts on. */
        fun error(status: Int, reason: String) = json(status, "{\"error\":\"$reason\"}")
    }
}

/**
 * Just enough HTTP/1.1 for the Kobo sync: one request per connection (every response says
 * `Connection: close`), bodies sized by `Content-Length` only, a cap on the header block and
 * on the body. Pure stream code, apart from sockets, so it can be tested.
 */
object HttpWire {
    const val MAX_HEADER_BYTES = 16 * 1024

    /** Thrown for a request that cannot be served; [status] is what to answer. */
    class Refused(val status: Int, reason: String) : IOException(reason)

    /**
     * Reads one request from [input]. [beforeBody] is called when the client waits for
     * `100 Continue` before sending the body.
     */
    fun readRequest(input: InputStream, maxBodyBytes: Int, beforeBody: () -> Unit = {}): HttpRequest {
        val lines = readHead(input).split("\r\n")
        val requestLine = lines.first().split(' ')
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) throw Refused(400, "bad request line")
        val headers = lines.drop(1).filter { it.isNotEmpty() }.associate { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) throw Refused(400, "bad header")
            line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) throw Refused(411, "send a Content-Length")
        val length = headers["content-length"]?.let { it.toLongOrNull() ?: throw Refused(400, "bad Content-Length") } ?: 0L
        if (length < 0) throw Refused(400, "bad Content-Length")
        if (length > maxBodyBytes) throw Refused(413, "body too large")
        if (length > 0 && headers["expect"].equals("100-continue", ignoreCase = true)) beforeBody()
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw Refused(400, "body ended early")
            read += n
        }
        val target = requestLine[1]
        val question = target.indexOf('?')
        return HttpRequest(
            method = requestLine[0].uppercase(),
            path = if (question < 0) target else target.substring(0, question),
            query = if (question < 0) emptyMap() else parseQuery(target.substring(question + 1)),
            headers = headers,
            body = body,
        )
    }

    fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }.associate { pair ->
        val equals = pair.indexOf('=')
        val name = if (equals < 0) pair else pair.substring(0, equals)
        val value = if (equals < 0) "" else pair.substring(equals + 1)
        decode(name) to decode(value)
    }

    private fun decode(text: String): String = try {
        URLDecoder.decode(text, Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        throw Refused(400, "bad query")
    }

    /** The request line and headers, up to the blank line, as ISO-8859-1 text. */
    private fun readHead(input: InputStream): String {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val byte = input.read()
            if (byte < 0) throw Refused(400, "request ended early")
            head.write(byte)
            if (head.size() > MAX_HEADER_BYTES) throw Refused(431, "headers too large")
            matched = when {
                byte == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                byte == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                byte == '\r'.code -> 1
                else -> 0
            }
        }
        return head.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
    }

    fun writeContinue(output: OutputStream) {
        output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    fun writeResponse(output: OutputStream, response: HttpResponse) {
        val length = response.file?.length() ?: response.body.size.toLong()
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            response.contentType?.let { append("Content-Type: ").append(it).append("\r\n") }
            append("Content-Length: ").append(length).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        val file = response.file
        if (file != null) file.inputStream().use { it.copyTo(output) } else output.write(response.body)
        output.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        409 -> "Conflict"
        411 -> "Length Required"
        413 -> "Content Too Large"
        431 -> "Request Header Fields Too Large"
        503 -> "Service Unavailable"
        else -> if (status >= 500) "Server Error" else "Status"
    }
}

/**
 * Serves [handler] on [port] on every interface until the calling coroutine is cancelled,
 * each connection on [Dispatchers.IO]. At most [maxConnections] are served at once (a pairing
 * request holds one for up to a minute); one more is answered 503 at once.
 */
class LanHttpServer(
    private val port: Int,
    private val handler: suspend (HttpRequest) -> HttpResponse,
    private val maxConnections: Int = 8,
    private val readTimeoutMillis: Int = 15_000,
    private val maxBodyBytes: Int = KoboProtocol.MAX_BODY_BYTES,
) {
    /** Binds, calls [onListening], and serves until cancelled. Throws if the port cannot be bound. */
    suspend fun run(onListening: () -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
        } catch (e: IOException) {
            server.close()
            throw e
        }
        val slots = Semaphore(maxConnections)
        closingOnCancel(server) {
            onListening()
            while (true) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    ensureActive()
                    throw e
                }
                if (!slots.tryAcquire()) {
                    socket.use { runCatching { HttpWire.writeResponse(it.getOutputStream(), HttpResponse.error(503, "busy")) } }
                    continue
                }
                launch {
                    try {
                        closingOnCancel(socket) { serve(socket) }
                    } finally {
                        slots.release()
                    }
                }
            }
        }
    }

    private suspend fun serve(socket: Socket) = socket.use {
        socket.soTimeout = readTimeoutMillis
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream().buffered()
        val response = try {
            handler(HttpWire.readRequest(input, maxBodyBytes) { HttpWire.writeContinue(output) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpWire.Refused) {
            runCatching {
                HttpWire.writeResponse(output, HttpResponse.error(e.status, "refused"))
                // The client may still be sending the body. Closing with it unread would reset
                // the connection and lose the answer, so read and drop a little of it first.
                socket.shutdownOutput()
                socket.soTimeout = 1_000
                val sink = ByteArray(8 * 1024)
                var left = 4 * maxBodyBytes
                while (left > 0) {
                    val n = input.read(sink)
                    if (n < 0) break
                    left -= n
                }
            }
            return@use
        } catch (_: IOException) {
            // The client went away or stalled; there is no one to answer.
            return@use
        }
        runCatching { HttpWire.writeResponse(output, response) }
    }

    /** Runs [block], closing [closeable] when the coroutine is cancelled so that a blocking accept or read returns. */
    private suspend fun <T> closingOnCancel(closeable: java.io.Closeable, block: suspend CoroutineScope.() -> T): T = coroutineScope {
        val closer = launch {
            try {
                awaitCancellation()
            } finally {
                closeable.close()
            }
        }
        try {
            block()
        } finally {
            closer.cancel()
        }
    }
}
