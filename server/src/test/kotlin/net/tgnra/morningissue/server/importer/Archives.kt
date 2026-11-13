package net.tgnra.morningissue.server.importer

import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import net.tgnra.morningissue.data.archive.ArchiveCategory
import net.tgnra.morningissue.data.archive.ArchiveContent
import net.tgnra.morningissue.data.archive.ArchiveEdition
import net.tgnra.morningissue.data.archive.ArchiveEngagement
import net.tgnra.morningissue.data.archive.ArchiveFeed
import net.tgnra.morningissue.data.archive.ArchiveFormat
import net.tgnra.morningissue.data.archive.ArchiveItem
import net.tgnra.morningissue.data.archive.ArchiveManifest
import net.tgnra.morningissue.data.archive.ArchivePageView
import net.tgnra.morningissue.data.archive.ArchivePost
import net.tgnra.morningissue.data.archive.ArchiveSettings
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.RichText

internal val T0: Instant = Instant.parse("2026-11-10T12:00:00Z")

internal fun day(n: Long): Instant = T0.minus(Duration.ofDays(30 - n))

internal fun ms(n: Long): Long = day(n).toEpochMilli()

internal fun body(text: String) = ContentBlocks.encode(listOf(ContentBlock.Paragraph(RichText(text))))

/** An archive file, as the app's `RoomArchiveTransfer.export` writes one. */
internal data class Archive(
    val feeds: List<ArchiveFeed>,
    val posts: List<ArchivePost>,
    val editions: List<ArchiveEdition>,
    val settings: ArchiveSettings? = null,
    val version: Int = ArchiveFormat.VERSION,
) {
    fun bytes(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, lines: List<String>) {
                zip.putNextEntry(ZipEntry(name))
                lines.forEach { zip.write((it + "\n").toByteArray()) }
                zip.closeEntry()
            }
            val json = ArchiveFormat.json
            entry(
                ArchiveFormat.MANIFEST,
                listOf(json.encodeToString(ArchiveManifest(ArchiveFormat.FORMAT, version, T0.toEpochMilli(), feeds.size, posts.size, editions.size))),
            )
            entry(ArchiveFormat.FEEDS, feeds.map { json.encodeToString(it) })
            entry(ArchiveFormat.POSTS, posts.map { json.encodeToString(it) })
            entry(ArchiveFormat.EDITIONS, editions.map { json.encodeToString(it) })
            settings?.let { entry(ArchiveFormat.SETTINGS, listOf(json.encodeToString(it))) }
        }
    }.toByteArray()

    companion object {
        /** A zip of the given entries, as text. */
        fun zipOf(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                for ((name, text) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()

        /**
         * The app's test library as a file: two feeds (the blog tagged and on Favourite), 12
         * posts, three editions. `blog-1` is Liked and read, `news-1` saved, `news-2` opened;
         * `blog-2` has fetched full text; `news-5` has no body.
         */
        fun library(posts: Int = 6, settings: ArchiveSettings? = null): Archive {
            val feeds = listOf(
                ArchiveFeed(1, "https://blog.example/feed.xml", "A Blog", siteUrl = "https://blog.example/", tags = listOf("Tech", "Essays"), sliderStep = "FAVOURITE", addedAt = ms(0)),
                ArchiveFeed(2, "http://www.news.example/rss", "The News", addedAt = ms(1)),
            )
            var n = 0L
            val archived = ArrayList<ArchivePost>()
            for ((feed, name) in listOf(1L to "blog", 2L to "news")) {
                for (i in 1..posts) {
                    val guid = "$name-$i"
                    val text = "Body of $guid with the word marmalade"
                    val content = when (guid) {
                        "news-5" -> null
                        "blog-2" -> ArchiveContent(body("Full text of blog-2 quince"), "FETCHED", ms(3))
                        else -> ArchiveContent(body(text), "FEED", ms(i.toLong()))
                    }
                    val engagement = when (guid) {
                        "blog-1" -> ArchiveEngagement(openedAt = ms(2), readFraction = 0.9f, readAt = ms(2), likedAt = ms(2))
                        "news-1" -> ArchiveEngagement(savedAt = ms(3))
                        "news-2" -> ArchiveEngagement(openedAt = ms(4))
                        else -> null
                    }
                    archived += ArchivePost(
                        n = ++n, feed = feed, guid = guid, url = "https://$name.example/$guid", title = "Headline $guid",
                        summary = "Summary $guid", publishedAt = ms(i.toLong()), firstSeenAt = ms(i.toLong()), wordCount = 8,
                        feedWordCount = 8, content = content, engagement = engagement,
                    )
                }
            }
            fun post(guid: String) = archived.first { it.guid == guid }.n
            val editions = (1L..3L).map { e ->
                val printed = listOf(post("blog-$e"), post("news-$e"))
                val index = if (e == 3L) listOf(post("news-4")) else emptyList()
                ArchiveEdition(
                    date = LocalDate.of(2026, 10, 10).plusDays(e).toString(), kind = "MORNING", cutoffAt = ms(e), releaseAt = ms(e),
                    builtAt = ms(e) - 5_400_000, length = "STANDARD", number = e.toInt(),
                    openedAt = ms(e) + 60_000, finishedAt = if (e == 1L) ms(e) + 600_000 else null,
                    items = printed.mapIndexed { slot, id ->
                        ArchiveItem(id, 1, slot, if (slot == 0) "LEAD" else "FRONT", 1.0 - slot * 0.25, reason = "Reason $slot")
                    } + index.map { ArchiveItem(it, 2, 0, "INDEX", 0.1) },
                    pageViewsTracked = e != 2L,
                    pageViews = if (e == 1L) listOf(ArchivePageView(1, 12_000, ms(e) + 61_000), ArchivePageView(2, 3_000, ms(e) + 75_000)) else null,
                )
            }
            return Archive(feeds, archived, editions, settings)
        }

        fun category(key: String, label: String = key) = ArchiveCategory(key, label)
    }
}
