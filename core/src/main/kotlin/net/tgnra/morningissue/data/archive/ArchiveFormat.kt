package net.tgnra.morningissue.data.archive

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.ReleaseChange

/**
 * The archive file: a zip holding four entries, read in this order.
 *
 * - `manifest.json`: one [ArchiveManifest]. [ArchiveManifest.version] is raised when a reader
 *   of the old version could no longer read the file; additions that old readers can ignore
 *   do not raise it.
 * - `feeds.jsonl`: one [ArchiveFeed] per line, with whether the reader subscribes to it.
 *   Version 2 added the status: a version 1 reader would subscribe to every feed in the file,
 *   including those that only hold posts saved from discovery, so the version was raised.
 *   A version 1 file imports with every feed subscribed, as before.
 * - `posts.jsonl`: one [ArchivePost] per line, with its stored body and the reader's
 *   engagement when there is any. A post also carries the two things only its feed could
 *   supply again, its categories and the kind of its enclosure. The rest of what the ranking
 *   reads (traits, embeddings) is left out and rebuilt from the post after an import. Both
 *   fields were added without raising the version: files written before them import as posts
 *   without categories.
 * - `editions.jsonl`: one [ArchiveEdition] per line, with its items.
 * - `settings.json`, optional: one [ArchiveSettings], the schedule and edition settings the
 *   phone had, so that a server account that has none yet can take them on. Older files have
 *   no such entry; older readers ignore it.
 *
 * Feeds' `muteUntil` and editions' `pageViewsTracked` and `pageViews` were added, with
 * `settings.json`, for moving a library to a self-hosted server, again without raising the
 * version. Readers ignore what they do not know.
 *
 * Feeds and posts carry a number that is only meaningful inside the file; posts point at
 * their feed, and edition items at their post, by it. Times are epoch milliseconds, dates ISO
 * (`2026-11-10`), enums their names. One JSON object per line means a file of any size can be
 * written and read a line at a time.
 */
object ArchiveFormat {
    const val FORMAT = "morning-issue-archive"
    const val VERSION = 2
    const val MIME_TYPE = "application/zip"
    const val FILE_EXTENSION = "zip"

    const val MANIFEST = "manifest.json"
    const val FEEDS = "feeds.jsonl"
    const val POSTS = "posts.jsonl"
    const val EDITIONS = "editions.jsonl"
    const val SETTINGS = "settings.json"

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
    /** A `FeedStatus` name; absent in version 1 files, whose feeds were all subscriptions. */
    val status: String = "SUBSCRIBED",
    val discoveredAt: Long? = null,
    /** Until when the feed is muted (left out of editions); absent when it is not. */
    val muteUntil: Long? = null,
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
    /** `article.feedWordCount`; absent in files written before it existed, and then estimated (`FeedWordCount`). */
    val feedWordCount: Int? = null,
    val contentHash: String? = null,
    val content: ArchiveContent? = null,
    val engagement: ArchiveEngagement? = null,
    val categories: List<ArchiveCategory> = emptyList(),
    /** An `EnclosureKind` name; absent when the post has no enclosure or none is known. */
    val enclosure: String? = null,
)

/** A feed's category for a post, as in `article_category`. */
@Serializable
data class ArchiveCategory(
    val key: String,
    val label: String,
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
    /** Whether the edition recorded page views; absent in files written before it was exported, read as untracked. */
    val pageViewsTracked: Boolean? = null,
    /** The pages the reader looked at; absent when there are none or the file predates it. */
    val pageViews: List<ArchivePageView>? = null,
)

/** Time on one page of an edition, as in `edition_page_view`. */
@Serializable
data class ArchivePageView(
    val page: Int,
    /** Total time on screen, in milliseconds. */
    val ms: Long,
    /** When the page was first shown, epoch milliseconds. */
    val firstSeenAt: Long,
)

/**
 * `settings.json`: the phone's schedule and edition settings. Device-only settings (theme,
 * display, greyscale, storage budget) are left out. Times of day are `HH:mm`.
 */
@Serializable
data class ArchiveSettings(
    val resetTime: String,
    /** An `EditionLength` name. */
    val editionLength: String,
    val eveningEnabled: Boolean,
    val eveningTime: String,
    /** The IANA time zone the phone was in, such as `Europe/London`. */
    val timeZone: String,
    val onboardingDone: Boolean,
    /** Releases (epoch milliseconds) for which a run found nothing new to print, newest first. */
    val nothingNewFor: List<Long> = emptyList(),
    /** How the last change of the reset time takes effect; absent if it was never changed. */
    val resetChange: ArchiveReleaseChange? = null,
    /** How the last change of the Evening edition's time, or switching it on, takes effect. */
    val eveningChange: ArchiveReleaseChange? = null,
) {
    companion object {
        /** What the archive keeps of [settings], with the phone's [zone]. */
        fun of(settings: AppSettings, zone: ZoneId): ArchiveSettings = ArchiveSettings(
            resetTime = settings.resetTime.format(TIME_OF_DAY),
            editionLength = settings.editionLength.name,
            eveningEnabled = settings.eveningEditionEnabled,
            eveningTime = settings.eveningEditionTime.format(TIME_OF_DAY),
            timeZone = zone.id,
            onboardingDone = settings.onboardingDone,
            nothingNewFor = settings.nothingNewFor.map { it.toEpochMilli() },
            resetChange = settings.resetChange?.let(ArchiveReleaseChange::of),
            eveningChange = settings.eveningChange?.let(ArchiveReleaseChange::of),
        )

        private val TIME_OF_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/** A `ReleaseChange`, in epoch milliseconds. */
@Serializable
data class ArchiveReleaseChange(
    val settled: Long? = null,
    val kept: Long? = null,
    val from: Long,
) {
    /** The model's form. */
    fun toReleaseChange(): ReleaseChange =
        ReleaseChange(settled?.let(Instant::ofEpochMilli), kept?.let(Instant::ofEpochMilli), Instant.ofEpochMilli(from))

    companion object {
        fun of(change: ReleaseChange): ArchiveReleaseChange =
            ArchiveReleaseChange(change.settled?.toEpochMilli(), change.kept?.toEpochMilli(), change.from.toEpochMilli())
    }
}

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
