package net.tgnra.morningissue.engine

import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep

/** What the engine needs to know about a feed; mapped from the feed row by the caller. */
data class FeedProfile(
    val id: Long,
    val title: String,
    val tier: CadenceTier = CadenceTier.WEEKLY,
    /** Median gap between posts in hours; null until cadence has been computed. */
    val medianIntervalHours: Double? = null,
    /** Learned affinity, 0..1, before the slider is applied. */
    val affinity: Double = 0.5,
    val sliderStep: SliderStep = SliderStep.AUTO,
    val muteUntil: Instant? = null,
    /** Tag names; unused until section pages arrive. */
    val tags: List<String> = emptyList(),
)

/** A post that may go into an edition. */
data class Candidate(
    val articleId: Long,
    val feedId: Long,
    val publishedAt: Instant,
    val firstSeenAt: Instant,
    val hasImage: Boolean = false,
    /** Editions this post was printed in without being opened. */
    val editionsRunUnopened: Int = 0,
    /** Times this post has led a front page. */
    val leadCount: Int = 0,
    val isOpened: Boolean = false,
    val isRead: Boolean = false,
)

/** A story's place in an edition draft; mirrors an `edition_item` row (see `ItemKind`). */
data class DraftItem(
    val articleId: Long,
    val feedId: Long,
    /** 1-based page number. */
    val page: Int,
    /** Tag name for section pages; null otherwise. */
    val section: String?,
    /** Order within the page, from 0. */
    val slot: Int,
    val kind: ItemKind,
    val score: Double,
    /** One line for "Why is this here?". */
    val reason: String,
)

/** The builder's output: every placed post exactly once, ordered by page and slot. */
data class EditionDraft(
    val length: EditionLength,
    val releaseAt: Instant,
    val items: List<DraftItem>,
    /** Candidates left out because their feed is muted at release time. */
    val mutedSkipped: Int = 0,
    /** Candidates left out because they were published more than `maxAgeDays` before release. */
    val staleSkipped: Int = 0,
) {
    /** Stories printed on a page of their own kind, i.e. everything but the back-page index. */
    val printed: List<DraftItem> get() = items.filter { it.kind != ItemKind.INDEX }
    val index: List<DraftItem> get() = items.filter { it.kind == ItemKind.INDEX }
    val lead: DraftItem? get() = items.firstOrNull { it.kind == ItemKind.LEAD }
}
