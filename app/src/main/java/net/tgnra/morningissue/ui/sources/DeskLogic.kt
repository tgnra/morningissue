package net.tgnra.morningissue.ui.sources

import java.time.Instant
import net.tgnra.morningissue.engine.FeedHealthRules
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.model.FlaggedFeed
import net.tgnra.morningissue.model.HealthFlag

/** A one-tap fix on the Subscriptions desk. */
enum class DeskAction {
    /** Try a broken feed again, alternatives included. */
    RETRY,

    /** "OK": the new address has been seen. */
    ACCEPT,

    /** Set the slider to Less. */
    SHOW_LESS,

    /** Set the slider to Favourite. */
    FAVOURITE,

    /** Asks first. */
    UNSUBSCRIBE,

    /** Hide the flag for six months. */
    DISMISS,
}

object Desk {
    /**
     * The fixes offered for [flag], the suggested one first. There is no "In brief only" for a
     * feed, so a noisy feed is offered Less; and no notifications, so a loved one is offered
     * Favourite alone.
     */
    fun actions(flag: HealthFlag): List<DeskAction> = when (flag) {
        HealthFlag.BROKEN -> listOf(DeskAction.RETRY, DeskAction.UNSUBSCRIBE, DeskAction.DISMISS)
        HealthFlag.MOVED -> listOf(DeskAction.ACCEPT, DeskAction.UNSUBSCRIBE)
        HealthFlag.NOISY -> listOf(DeskAction.SHOW_LESS, DeskAction.UNSUBSCRIBE, DeskAction.DISMISS)
        HealthFlag.IGNORED -> listOf(DeskAction.UNSUBSCRIBE, DeskAction.SHOW_LESS, DeskAction.DISMISS)
        HealthFlag.LOVED -> listOf(DeskAction.FAVOURITE, DeskAction.DISMISS)
        HealthFlag.QUIET -> listOf(DeskAction.DISMISS)
    }

    /** Flagged feeds by flag, most urgent group first; groups with no feed are left out. */
    fun groups(flagged: List<FlaggedFeed>): List<Pair<HealthFlag, List<FlaggedFeed>>> =
        FeedHealthRules.PRIORITY.mapNotNull { flag ->
            flagged.filter { it.flag == flag }.takeIf { it.isNotEmpty() }?.let { flag to it }
        }

    /** Feeds whose undismissed flag asks for something (everything but Gone quiet). */
    fun needingAttention(feeds: List<Feed>, now: Instant): Int =
        feeds.count { feed -> feed.visibleHealthFlag(now)?.let(FeedHealthRules::needsAttention) == true }
}

/** A line of the Sources list: a feed, or the heading above the Quiet group. */
sealed interface SourceRow {
    val key: Any

    data class Source(val feed: Feed) : SourceRow {
        override val key: Any get() = feed.id
    }

    data class QuietHeading(val count: Int) : SourceRow {
        override val key: Any get() = "quiet-heading"
    }

    companion object {
        /**
         * [feeds] in their order, with those that have not posted in 90 days moved to a Quiet
         * group at the bottom under its heading.
         */
        fun of(feeds: List<Feed>, now: Instant, config: RankingConfig = RankingConfig.Default): List<SourceRow> {
            val (quiet, active) = feeds.partition { FeedHealthRules.isQuiet(it.lastPostAt, it.addedAt, it.lastSyncAt, now, config) }
            return buildList {
                active.forEach { add(Source(it)) }
                if (quiet.isNotEmpty()) {
                    add(QuietHeading(quiet.size))
                    quiet.forEach { add(Source(it)) }
                }
            }
        }
    }
}
