package net.tgnra.morningissue.server.editions

import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.ListSerializer
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.ApiLimits
import net.tgnra.morningissue.api.KoboImageEntry
import net.tgnra.morningissue.api.KoboImagesRequest
import net.tgnra.morningissue.kobo.GreyFormat
import net.tgnra.morningissue.kobo.PreparedImage
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.KoboImageService

/**
 * `POST /kobo/images` (4.8): the zip of prepared pictures a Kobo installs, and the warm-up
 * after a build. Entries are stored, not deflated (JPEG and PNG do not shrink): one per
 * picture, `images/<sha256(url)>.<jpg|png>`, then `images.json` listing them. A URL that
 * cannot be got or decoded is left out.
 */
class KoboImageServiceImpl(
    private val database: ServerDatabase,
    private val images: KoboImages,
    private val bundles: EditionBundles,
) : KoboImageService {
    private val editionDao = database.editionDao()

    override suspend fun writeZip(userId: Long, request: KoboImagesRequest, output: OutputStream) {
        if (request.urls.size > ApiLimits.MAX_IMAGE_URLS) throw ApiFailure.badRequest("At most ${ApiLimits.MAX_IMAGE_URLS} URLs per request")
        val width = request.width.coerceIn(ApiLimits.MIN_KOBO_WIDTH, ApiLimits.MAX_KOBO_WIDTH)
        val urls = request.urls.distinct()
        val prepared = coroutineScope {
            val permits = Semaphore(PARALLEL)
            urls.map { url -> async { permits.withPermit { url to images.prepare(url, width) } } }.awaitAll()
        }
        runInterruptible(Dispatchers.IO) { write(prepared, output) }
    }

    override suspend fun prepare(editionId: Long, widths: Set<Int>) {
        if (widths.isEmpty()) return
        val userId = editionDao.ownerOf(editionId) ?: return
        val edition = editionDao.getById(userId, editionId) ?: return
        val urls = BundleExport.imageUrls(bundles.bundleUnreleased(userId, edition).articles.values)
        val sized = widths.map { it.coerceIn(ApiLimits.MIN_KOBO_WIDTH, ApiLimits.MAX_KOBO_WIDTH) }.distinct()
        coroutineScope {
            val permits = Semaphore(PARALLEL)
            urls.map { url -> async { permits.withPermit { images.prepare(url, sized) } } }.awaitAll()
        }
    }

    companion object {
        /** Pictures being prepared at once for one request; the downloads and decodes have their own limits. */
        private const val PARALLEL = 6

        /** The zip's name for [url]'s picture. */
        fun fileName(url: String, format: GreyFormat): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val extension = if (format == GreyFormat.PNG) "png" else "jpg"
            return "images/$hash.$extension"
        }

        /** Writes the zip of [prepared] (URLs without a picture are left out) to [output], which is not closed. */
        fun write(prepared: List<Pair<String, PreparedImage?>>, output: OutputStream) {
            val zip = ZipOutputStream(output)
            val listed = ArrayList<KoboImageEntry>()
            for ((url, image) in prepared) {
                image ?: continue
                val name = fileName(url, image.format)
                val size = dimensions(image) ?: continue
                zip.putNextEntry(stored(name, image.bytes))
                zip.write(image.bytes)
                zip.closeEntry()
                listed += KoboImageEntry(url = url, file = name, type = image.format.contentType, width = size.first, height = size.second)
            }
            val index = ApiJson.json.encodeToString(ListSerializer(KoboImageEntry.serializer()), listed).toByteArray()
            zip.putNextEntry(stored(INDEX, index))
            zip.write(index)
            zip.closeEntry()
            zip.finish()
            zip.flush()
        }

        const val INDEX = "images.json"

        private fun stored(name: String, bytes: ByteArray) = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }

        /** The pixel size of a prepared JPEG or PNG, read from its header. */
        fun dimensions(image: PreparedImage): Pair<Int, Int>? {
            val b = image.bytes
            fun u16(at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
            fun u32(at: Int) = (u16(at) shl 16) or u16(at + 2)
            return when (image.format) {
                GreyFormat.PNG -> if (b.size >= 24) u32(16) to u32(20) else null
                GreyFormat.JPEG -> {
                    var i = 2
                    while (i + 9 < b.size) {
                        if (b[i].toInt() and 0xFF != 0xFF) return null
                        val marker = b[i + 1].toInt() and 0xFF
                        if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                            return u16(i + 7) to u16(i + 5)
                        }
                        i += 2 + u16(i + 2)
                    }
                    null
                }
            }
        }
    }
}
