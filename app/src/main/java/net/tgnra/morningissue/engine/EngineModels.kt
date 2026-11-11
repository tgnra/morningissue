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
    /** Tag names; each may get a section page. A feed without tags goes in General. */
    val tags: List<String> = emptyList(),
    /**
     * Set when [affinity] is high mostly because of this tag rather than the feed's own
     * record (see `Affinity`, tag borrowing); only used to word the reason line honestly.
     */
    val borrowedFromTag: String? = null,
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
    /**
     * An untouched post of a loved Weekly or Rare feed from before the previous cutoff, kept
     * eligible until it is read. False for the posts first seen since that cutoff.
     */
    val isHeldOver: Boolean = false,
    /** Printed in one of the last `archiveCooldownEditions` editions; bars From the archive. */
    val ranRecently: Boolean = false,
    /** The headline and link, for cross-feed deduplication; a post without either is never a duplicate. */
    val title: String? = null,
    val url: String? = null,
    /**
     * When the feed last posted before this post, as far as the app knew before this edition's
     * window opened. Set only on the feed's first new post after that; a gap of
     * `comebackQuietDays` or more makes this post a comeback ("Back after 2 years").
     */
    val previousPostAt: Instant? = null,
    /**
     * Exempt from the age limit and the freshness floor without being held over: the newest
     * posts of each feed in an Extra edition.
     */
    val exemptFromAgeLimits: Boolean = false,
)

/**
 * What the builder needs for the While you were away pull-out.
 *
 * @param candidates the untouched stories printed in the missed editions, minus any that have
 *   been in a pull-out before
 * @param lastVisitAt when the reader last opened an edition; freshness is measured from here
 * @param engagedFeedIds feeds with a post the reader Liked or saved recently; the only
 *   Firehose feeds allowed in
 */
data class AwayPool(
    val candidates: List<Candidate>,
    val lastVisitAt: Instant,
    val engagedFeedIds: Set<Long> = emptySet(),
)

/** A story's place in an edition draft; mirrors an `edition_item` row (see `ItemKind`). */
data class DraftItem(
    val articleId: Long,
    val feedId: Long,
    /** 1-based page number. */
    val page: Int,
    /** Tag name for section pages (`EditionBuilder.GENERAL_SECTION` for untagged feeds); null otherwise. */
    val section: String?,
    /** Order within the page, from 0. */
    val slot: Int,
    val kind: ItemKind,
    val score: Double,
    /** One line for "Why is this here?". */
    val reason: String,
    /** Other feeds that carried the same story and were collapsed into this one. */
    val alsoInFeeds: Int = 0,
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
    /** Copies of a syndicated story that were collapsed into the copy that was kept. */
    val duplicatesDropped: Int = 0,
    /** Weekly and Rare posts an Evening edition left for the morning paper. */
    val waitingForMorning: Int = 0,
) {
    /** Stories printed on a page of their own kind, i.e. everything but the back-page index. */
    val printed: List<DraftItem> get() = items.filter { it.kind != ItemKind.INDEX }
    val index: List<DraftItem> get() = items.filter { it.kind == ItemKind.INDEX }
    val lead: DraftItem? get() = items.firstOrNull { it.kind == ItemKind.LEAD }
}
