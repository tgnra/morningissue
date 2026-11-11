package net.tgnra.morningissue.model

import java.time.Instant
import java.time.LocalDate

/** What a newsstand cover or a calendar cell needs to know about an edition. */
data class EditionSummary(
    val id: Long,
    val date: LocalDate,
    val kind: EditionKind,
    /** Running edition number, starting at 1. */
    val number: Int,
    val length: EditionLength,
    val releaseAt: Instant,
    /** Posts first seen after this instant were too late for the edition. */
    val cutoffAt: Instant,
    val leadHeadline: String?,
    val leadImageUrl: String?,
    val leadSource: String?,
    /** Stories printed in the edition, not counting the back-page index. */
    val storyCount: Int,
    /** True once the reader has reached the back page. */
    val isRead: Boolean,
)

/** One story placed in an edition, with the reader's current state for ticks and menus. */
data class EditionStory(
    val articleId: Long,
    val feedId: Long,
    val feedTitle: String,
    val feedIconUrl: String?,
    val title: String,
    val summary: String?,
    val imageUrl: String?,
    val author: String?,
    val url: String?,
    val publishedAt: Instant,
    val readMinutes: Int,
    val kind: ItemKind,
    /** Order within the page, ascending. */
    val slot: Int,
    val score: Double,
    /** One line for "Why is this here?"; null when the builder gave none. */
    val reason: String?,
    val isOpened: Boolean,
    val isRead: Boolean,
    val isLiked: Boolean,
    val isSaved: Boolean,
    /**
     * "Back after 2 years": set when the story is its feed's first post after a long silence
     * (Welcome back); null otherwise.
     */
    val comebackLabel: String? = null,
    /** False for a From your reading list story, whose feed the reader does not subscribe to. */
    val feedSubscribed: Boolean = true,
)

/** Back-page index entries from one source. */
data class IndexGroup(
    val feedId: Long,
    val feedTitle: String,
    val stories: List<EditionStory>,
)

/** A page of an edition. [number] is 1-based and matches the position in [Edition.pages]. */
sealed interface EditionPage {
    val number: Int

    /** Every story on the page in reading order. */
    val stories: List<EditionStory>

    data class Front(
        override val number: Int,
        val lead: EditionStory?,
        val secondaries: List<EditionStory>,
    ) : EditionPage {
        override val stories: List<EditionStory> get() = listOfNotNull(lead) + secondaries
    }

    data class TopStories(
        override val number: Int,
        override val stories: List<EditionStory>,
    ) : EditionPage

    /** A tag's page. [name] is the tag, or "General" for untagged feeds. */
    data class Section(
        override val number: Int,
        val name: String,
        override val stories: List<EditionStory>,
    ) : EditionPage

    /** Headline-only lines. */
    data class InBrief(
        override val number: Int,
        override val stories: List<EditionStory>,
    ) : EditionPage

    data class FromTheArchive(
        override val number: Int,
        override val stories: List<EditionStory>,
    ) : EditionPage

    /** From your reading list: a post saved from a feed the reader does not subscribe to. */
    data class FromYourReadingList(
        override val number: Int,
        override val stories: List<EditionStory>,
    ) : EditionPage

    /** The "While you were away" pull-out. */
    data class WhileYouWereAway(
        override val number: Int,
        override val stories: List<EditionStory>,
    ) : EditionPage

    /** Always the last page, present even when the index is empty. */
    data class Back(
        override val number: Int,
        val alsoInYourFeeds: List<IndexGroup>,
        /** When the next edition is released; null if not known. */
        val nextEditionAt: Instant?,
    ) : EditionPage {
        override val stories: List<EditionStory> get() = alsoInYourFeeds.flatMap { it.stories }
    }
}

/** A full edition, ready to render. */
data class Edition(
    val id: Long,
    val date: LocalDate,
    val kind: EditionKind,
    val number: Int,
    val length: EditionLength,
    val cutoffAt: Instant,
    val releaseAt: Instant,
    val builtAt: Instant,
    /** Ordered pages; the last one is always [EditionPage.Back]. */
    val pages: List<EditionPage>,
    /** Estimated reading time for the printed stories (briefs and index excluded). */
    val readMinutes: Int,
    val isRead: Boolean,
)
