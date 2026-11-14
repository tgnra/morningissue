package net.tgnra.morningissue.server.importer

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlinx.serialization.decodeFromString
import net.tgnra.morningissue.data.archive.ArchiveEdition
import net.tgnra.morningissue.data.archive.ArchiveFeed
import net.tgnra.morningissue.data.archive.ArchiveFormat
import net.tgnra.morningissue.data.archive.ArchiveManifest
import net.tgnra.morningissue.data.archive.ArchivePost
import net.tgnra.morningissue.data.archive.ArchiveSettings
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/** An archive file, parsed with core's `ArchiveFormat`. */
internal data class ParsedArchive(
    val manifest: ArchiveManifest,
    val feeds: List<ArchiveFeed>,
    val posts: List<ArchivePost>,
    val editions: List<ArchiveEdition>,
    val settings: ArchiveSettings?,
    /** Entry names, in file order. */
    val entries: List<String>,
) {
    fun feed(url: String): ArchiveFeed = feeds.firstOrNull { it.url == url } ?: error("No feed $url in ${feeds.map { it.url }}")

    fun post(guid: String): ArchivePost = posts.firstOrNull { it.guid == guid } ?: error("No post $guid")

    fun guidOf(n: Long): String = posts.first { it.n == n }.guid
}

/**
 * Reads an archive as the phone app's importer did right before the server split
 * (`RoomArchiveTransfer.import` at 58c93ad): one pass over the zip per entry with a
 * `ZipInputStream`, a JSON line at a time, decoding with `ArchiveFormat.json`.
 */
internal object PhoneArchiveReader {
    private val json = ArchiveFormat.json

    fun parse(bytes: ByteArray): ParsedArchive {
        val entries = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) entries += (zip.nextEntry ?: break).name
        }
        val manifest = json.decodeFromString<ArchiveManifest>(lines(bytes, ArchiveFormat.MANIFEST)!!.first())
        return ParsedArchive(
            manifest = manifest,
            feeds = lines(bytes, ArchiveFormat.FEEDS).orEmpty().map { json.decodeFromString<ArchiveFeed>(it) },
            posts = lines(bytes, ArchiveFormat.POSTS).orEmpty().map { json.decodeFromString<ArchivePost>(it) },
            editions = lines(bytes, ArchiveFormat.EDITIONS).orEmpty().map { json.decodeFromString<ArchiveEdition>(it) },
            settings = lines(bytes, ArchiveFormat.SETTINGS)?.firstOrNull()?.let { json.decodeFromString<ArchiveSettings>(it) },
            entries = entries,
        )
    }

    /** The non-blank lines of entry [name], as `readEntry` reads them; null when there is no such entry. */
    private fun lines(bytes: ByteArray, name: String): List<String>? {
        ZipInputStream(BufferedInputStream(ByteArrayInputStream(bytes))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == name) return BufferedReader(InputStreamReader(zip, Charsets.UTF_8)).lineSequence().filter { it.isNotBlank() }.toList()
            }
        }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? = enumValues<T>().firstOrNull { it.name == name }

    /**
     * Asserts that the phone app's importer (58c93ad) would take every line of [bytes]: the
     * manifest it checks, every feed it would add (status and slider it can read, addresses
     * unique once normalised), every post it would keep (its feed is in the file, its body
     * decodes to blocks with a known source, no two share a feed and post ID) and every
     * edition and item it would insert (date, kind, length and item kind it knows, items
     * pointing at posts in the file, no post twice in an edition). Returns the parsed file.
     */
    fun assertPhoneImports(bytes: ByteArray): ParsedArchive {
        val archive = parse(bytes)
        val m = archive.manifest
        assertEquals(ArchiveFormat.FORMAT, m.format)
        assertTrue("version ${m.version} is newer than the phone reads", m.version <= ArchiveFormat.VERSION)
        assertEquals(listOf(ArchiveFormat.MANIFEST, ArchiveFormat.FEEDS, ArchiveFormat.POSTS, ArchiveFormat.EDITIONS), archive.entries.take(4))
        assertEquals("manifest feeds", archive.feeds.size, m.feeds)
        assertEquals("manifest posts", archive.posts.size, m.posts)
        assertEquals("manifest editions", archive.editions.size, m.editions)

        val feedNumbers = HashSet<Long>()
        val keys = HashSet<String>()
        for (feed in archive.feeds) {
            assertNotNull("feed status ${feed.status}", enumOrNull<FeedStatus>(feed.status))
            assertNotNull("slider ${feed.sliderStep}", enumOrNull<SliderStep>(feed.sliderStep))
            assertTrue("feed number ${feed.n} twice", feedNumbers.add(feed.n))
            // Two lines with one address: the phone would map both numbers to one feed, fine, but
            // the server never has two.
            assertTrue("feed ${feed.url} twice", keys.add(FeedUrls.normalize(feed.url)))
            assertTrue(feed.title.isNotBlank())
        }

        val postNumbers = HashSet<Long>()
        val identities = HashSet<Pair<Long, String>>()
        for (post in archive.posts) {
            if (post.feed !in feedNumbers) fail("post ${post.guid} points at feed ${post.feed}, not in the file: the phone would skip it")
            assertTrue("post number ${post.n} twice", postNumbers.add(post.n))
            assertTrue("post ${post.guid} twice in feed ${post.feed}", identities.add(post.feed to post.guid))
            post.content?.let { content ->
                assertTrue("post ${post.guid}: body has no blocks", ContentBlocks.decode(content.blocks).isNotEmpty())
                assertNotNull("post ${post.guid}: source ${content.source}", enumOrNull<ContentSource>(content.source))
            }
            post.engagement?.let { assertTrue(it.readFraction in 0f..1f) }
        }

        val editionKeys = HashSet<Pair<String, String>>()
        for (edition in archive.editions) {
            val date = runCatching { LocalDate.parse(edition.date) }.getOrNull()
            assertNotNull("edition date ${edition.date}", date)
            assertNotNull("edition kind ${edition.kind}", enumOrNull<EditionKind>(edition.kind))
            assertNotNull("edition length ${edition.length}", enumOrNull<EditionLength>(edition.length))
            assertTrue("edition ${edition.date} ${edition.kind} twice", editionKeys.add(edition.date to edition.kind))
            val items = HashSet<Long>()
            for (item in edition.items) {
                assertTrue("edition ${edition.date}: item points at post ${item.post}, not in the file", item.post in postNumbers)
                assertNotNull("item kind ${item.kind}", enumOrNull<ItemKind>(item.kind))
                assertTrue("edition ${edition.date}: post ${item.post} twice", items.add(item.post))
            }
        }
        return archive
    }
}
