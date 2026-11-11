package net.tgnra.morningissue.data.archive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The archive file: a zip holding four entries, read in this order.
 *
 * - `manifest.json`: one [ArchiveManifest]. [ArchiveManifest.version] is raised when a reader
 *   of the old version could no longer read the file; additions that old readers can ignore
 *   do not raise it.
 * - `feeds.jsonl`: one [ArchiveFeed] per line.
 * - `posts.jsonl`: one [ArchivePost] per line, with its stored body and the reader's
 *   engagement when there is any.
 * - `editions.jsonl`: one [ArchiveEdition] per line, with its items.
 *
 * Feeds and posts carry a number that is only meaningful inside the file; posts point at
 * their feed, and edition items at their post, by it. Times are epoch milliseconds, dates ISO
 * (`2026-11-10`), enums their names. One JSON object per line means a file of any size can be
 * written and read a line at a time.
 */
object ArchiveFormat {
    const val FORMAT = "morning-issue-archive"
    const val VERSION = 1
    const val MIME_TYPE = "application/zip"
    const val FILE_EXTENSION = "zip"

    const val MANIFEST = "manifest.json"
    const val FEEDS = "feeds.jsonl"
    const val POSTS = "posts.jsonl"
    const val EDITIONS = "editions.jsonl"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

@Serializable
data class ArchiveManifest(
    val format: String,
    val version: Int,
    val exportedAt: Long,
    val feeds: Int = 0,
    val posts: Int = 0,
    val editions: Int = 0,
)

@Serializable
data class ArchiveFeed(
    val n: Long,
    val url: String,
    val title: String,
    val siteUrl: String? = null,
    val iconUrl: String? = null,
    val tags: List<String> = emptyList(),
    val sliderStep: String = "AUTO",
    val addedAt: Long,
)

@Serializable
data class ArchivePost(
    val n: Long,
    val feed: Long,
    val guid: String,
    val url: String? = null,
    val title: String,
    val summary: String? = null,
    val imageUrl: String? = null,
    val author: String? = null,
    val publishedAt: Long,
    val firstSeenAt: Long,
    val wordCount: Int = 0,
    val contentHash: String? = null,
    val content: ArchiveContent? = null,
    val engagement: ArchiveEngagement? = null,
)

@Serializable
data class ArchiveContent(
    /** The block-model JSON exactly as stored (`ContentBlocks`). */
    val blocks: String,
    val source: String,
    val storedAt: Long,
)

@Serializable
data class ArchiveEngagement(
    val openedAt: Long? = null,
    val readFraction: Float = 0f,
    val readAt: Long? = null,
    val savedAt: Long? = null,
    val likedAt: Long? = null,
    val editionsRunUnopened: Int = 0,
    val leadCount: Int = 0,
)

@Serializable
data class ArchiveEdition(
    val date: String,
    val kind: String,
    val cutoffAt: Long,
    val releaseAt: Long,
    val builtAt: Long,
    val length: String,
    val number: Int,
    val openedAt: Long? = null,
    val finishedAt: Long? = null,
    val items: List<ArchiveItem> = emptyList(),
)

@Serializable
data class ArchiveItem(
    val post: Long,
    val page: Int,
    val slot: Int,
    val kind: String,
    val score: Double,
    val section: String? = null,
    val reason: String? = null,
)
