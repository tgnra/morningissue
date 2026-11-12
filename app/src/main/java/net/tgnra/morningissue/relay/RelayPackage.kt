package net.tgnra.morningissue.relay

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.tgnra.morningissue.kobo.BundleExport
import net.tgnra.morningissue.kobo.KoboExporter
import net.tgnra.morningissue.kobo.KoboProtocol
import net.tgnra.morningissue.kobo.Manifest
import net.tgnra.morningissue.kobo.PreparedImage

/** One line of `images.json`. */
@Serializable
data class PackageImage(val url: String, val w: Int, val file: String, val type: String)

/** What [RelayPackageWriter.write] made. */
class BuiltPackage(
    val file: File,
    /** Of the envelope's bytes, for the upload's signature. */
    val sha256: ByteArray,
    val size: Long,
    val editions: Int,
    val saved: Int,
    val images: Int,
)

/**
 * Writes a relay package (`docs/relay-sync.md`, "Package contents") as a MIR1 envelope to a
 * file, in one pass: the zip is encrypted as it is written, so neither the plaintext nor the
 * whole package is ever held in memory or on disk.
 *
 * The zip holds `manifest.json` first, then the edition bundles and the saved shelf in protocol
 * v1's formats, then each image at each width, then `images.json`. JSON is deflated;
 * images, already compressed, are stored.
 */
class RelayPackageWriter(
    /** Prepares [url] at each of the widths, as `GET /v1/images` would (`KoboImages.prepare`). */
    private val prepareImage: suspend (url: String, widths: List<Int>) -> Map<Int, PreparedImage>,
    private val parallel: Int = 4,
) {
    /**
     * [acked] is every relay device's `ackedSeq` by `deviceId`; [widths] the distinct image widths
     * of those devices. [onImages] is told how many addresses are done of how many.
     */
    suspend fun write(
        snapshot: KoboExporter.Snapshot,
        acked: Map<String, Long>,
        widths: List<Int>,
        builtAt: Instant,
        packageKey: ByteArray,
        packageId: String,
        target: File,
        onImages: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BuiltPackage {
        val digest = MessageDigest.getInstance("SHA-256")
        val counting = CountingStream(FileOutputStream(target))
        var imageCount = 0
        val sealing = Mir1.SealingOutputStream(
            BufferedOutputStream(DigestOutputStream(counting, digest), 1 shl 16),
            packageKey,
            Mir1.KIND_PACKAGE,
            Mir1.packageContext(packageId),
        )
        ZipOutputStream(sealing).use { zip ->
            zip.putJson("manifest.json", manifestJson(snapshot, acked, builtAt))
            for ((id, bundle) in snapshot.editions) zip.putJson("editions/$id.json", bundle)
            zip.putJson("saved.json", snapshot.saved)

            val urls = BundleExport.imageUrls(snapshot.articles)
            val sizes = widths.distinct().sorted()
            val lines = ArrayList<PackageImage>()
            val lock = Mutex()
            var done = 0
            onImages(0, urls.size)
            if (sizes.isNotEmpty()) {
                coroutineScope {
                    val permits = Semaphore(parallel)
                    for (url in urls) {
                        launch {
                            val prepared = permits.withPermit { prepareImage(url, sizes) }
                            lock.withLock {
                                for (w in sizes) {
                                    val image = prepared[w] ?: continue
                                    val file = imageFile(url, w)
                                    zip.putStored(file, image.bytes)
                                    lines += PackageImage(url, w, file, image.format.contentType)
                                }
                                done++
                                onImages(done, urls.size)
                            }
                        }
                    }
                }
            }
            imageCount = lines.size
            zip.putJson("images.json", RelayJson.json.encodeToString(lines.sortedWith(compareBy({ it.url }, { it.w }))))
        }
        return BuiltPackage(
            file = target,
            sha256 = digest.digest(),
            size = counting.count,
            editions = snapshot.editions.size,
            saved = snapshot.manifest.saved.count,
            images = imageCount,
        )
    }

    companion object {
        const val PROTOCOL = "relay/1"

        /** `images/<sha256 hex of url>-<w>`. */
        fun imageFile(url: String, width: Int): String = "images/${RelayCrypto.hex(RelayCrypto.sha256(url.toByteArray(Charsets.UTF_8)))}-$width"

        /** The v1 manifest without `ackedSeq`, plus `protocol`, `builtAt` and `acked`. */
        fun manifestJson(snapshot: KoboExporter.Snapshot, acked: Map<String, Long>, builtAt: Instant): String {
            val v1 = KoboProtocol.json.encodeToJsonElement(Manifest.serializer(), snapshot.manifest).jsonObject
            val manifest = buildJsonObject {
                for ((key, value) in v1) if (key != "ackedSeq") put(key, value)
                put("protocol", PROTOCOL)
                put("builtAt", BundleExport.time(builtAt))
                put("acked", JsonObject(acked.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            }
            return KoboProtocol.json.encodeToString(JsonObject.serializer(), manifest)
        }
    }
}

private fun ZipOutputStream.putJson(name: String, text: String) {
    putNextEntry(ZipEntry(name))
    write(text.toByteArray(Charsets.UTF_8))
    closeEntry()
}

private fun ZipOutputStream.putStored(name: String, bytes: ByteArray) {
    val entry = ZipEntry(name)
    entry.method = ZipEntry.STORED
    entry.size = bytes.size.toLong()
    entry.compressedSize = bytes.size.toLong()
    entry.crc = CRC32().apply { update(bytes) }.value
    putNextEntry(entry)
    write(bytes)
    closeEntry()
}

private class CountingStream(out: OutputStream) : java.io.FilterOutputStream(out) {
    var count = 0L
        private set

    override fun write(b: Int) {
        out.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        count += len
    }
}
