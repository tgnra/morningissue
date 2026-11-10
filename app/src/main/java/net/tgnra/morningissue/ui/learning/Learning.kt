package net.tgnra.morningissue.ui.learning

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep

/*
 * The rules behind the learning controls (slider, mutes, tags, "Why is this here?"), kept free
 * of Compose so they can be tested on the JVM.
 */

/** The 5-step slider as positions on a track. */
object SliderSteps {
    val count: Int = SliderStep.entries.size

    /** The step at [index], clamped to the ends of the track. */
    fun fromIndex(index: Int): SliderStep = SliderStep.entries[index.coerceIn(0, SliderStep.entries.lastIndex)]

    /**
     * Which stop a touch at [x] on a track [width] wide lands on: the nearest one. The stops
     * are evenly spaced with the first and last [inset] in from the ends; by default each sits
     * at the centre of one of [count] equal cells. Touches beyond either end take the end stop.
     * [rtl] mirrors the track.
     */
    fun indexAt(x: Float, width: Float, count: Int = this.count, rtl: Boolean = false, inset: Float = width / (2 * count)): Int {
        if (width <= 0f || count <= 1) return 0
        val spacing = (width - 2 * inset) / (count - 1)
        if (spacing <= 0f) return 0
        val index = Math.floor(((x - inset) / spacing + 0.5f).toDouble()).toInt().coerceIn(0, count - 1)
        return if (rtl) count - 1 - index else index
    }

    /** Where stop [index] sits on the track, the inverse of [indexAt]. */
    fun positionOf(index: Int, width: Float, count: Int = this.count, rtl: Boolean = false, inset: Float = width / (2 * count)): Float {
        val place = if (rtl) count - 1 - index else index
        return if (count <= 1) width / 2f else inset + place * (width - 2 * inset) / (count - 1)
    }

    /** The step a touch at [x] selects. */
    fun stepAt(x: Float, width: Float, rtl: Boolean = false, inset: Float = width / (2 * count)): SliderStep =
        fromIndex(indexAt(x, width, count, rtl, inset))

    fun canShowMore(step: SliderStep): Boolean = step != SliderStep.FAVOURITE

    fun canShowLess(step: SliderStep): Boolean = step != SliderStep.RARELY
}

/** Muting a feed for a while. */
object Mutes {
    /** The durations on offer, in days. */
    val DAYS: List<Int> = listOf(1, 3, 7, 30)

    /** When a mute of [days] days started at [now] ends. */
    fun until(now: Instant, days: Int): Instant = now.plus(Duration.ofDays(days.coerceAtLeast(1).toLong()))

    /** The end of the mute if it is still running at [now]; null when unmuted or lapsed. */
    fun activeUntil(muteUntil: Instant?, now: Instant): Instant? = muteUntil?.takeIf { it.isAfter(now) }

    /** Whole days of mute left at [now], rounded up; 0 when not muted. */
    fun daysLeft(muteUntil: Instant?, now: Instant): Int {
        val until = activeUntil(muteUntil, now) ?: return 0
        val seconds = Duration.between(now, until).seconds
        return ((seconds + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY).toInt().coerceAtLeast(1)
    }

    private const val SECONDS_PER_DAY = 24L * 60 * 60
}

/** Turning what the user types into tag names. */
object TagInput {
    /** Long enough for "Science fiction", short enough to fit a section banner on a phone. */
    const val MAX_LENGTH = 24

    /**
     * One tag name as it will be stored: surrounding space and a leading `#` dropped, inner
     * runs of white space collapsed, cut to [MAX_LENGTH]. Null when nothing is left.
     */
    fun normalize(raw: String): String? {
        val clean = raw.trim().trimStart('#').trim().replace(WHITESPACE, " ")
        if (clean.isEmpty()) return null
        return clean.take(MAX_LENGTH).trim()
    }

    /** Several tags typed at once, separated by commas; blanks and repeats (ignoring case) are dropped. */
    fun parse(raw: String): List<String> =
        raw.split(',', ';', '\n').mapNotNull { normalize(it) }.distinctBy { it.lowercase() }

    /**
     * [current] with the tags typed in [raw] added. A typed tag that matches one in [known]
     * (the tags already in use on any feed) without regard to case takes the known spelling, so
     * "tech" joins "Tech" rather than competing with it. Sorted as `Feed.tags` is.
     */
    fun add(current: List<String>, raw: String, known: List<String> = emptyList()): List<String> {
        val result = current.toMutableList()
        for (typed in parse(raw)) {
            if (result.any { it.equals(typed, ignoreCase = true) }) continue
            result += known.firstOrNull { it.equals(typed, ignoreCase = true) } ?: typed
        }
        return result.sortedBy { it.lowercase() }
    }

    fun remove(current: List<String>, tag: String): List<String> = current.filterNot { it.equals(tag, ignoreCase = true) }

    /**
     * Tags in use elsewhere that could be added to a feed carrying [current]: those containing
     * what has been [typed] so far, all of them when nothing has been typed.
     */
    fun suggestions(known: List<String>, current: List<String>, typed: String = ""): List<String> {
        val needle = normalize(typed)?.lowercase().orEmpty()
        return known
            .filter { candidate -> current.none { it.equals(candidate, ignoreCase = true) } }
            .filter { needle.isEmpty() || it.lowercase().contains(needle) }
    }

    private val WHITESPACE = Regex("\\s+")
}

/** Which feeds the Sources list shows. */
sealed interface SourceFilter {
    data object All : SourceFilter

    data class Tagged(val tag: String) : SourceFilter

    /** Feeds with no tag: the ones whose stories go in General. */
    data object Untagged : SourceFilter

    data object Muted : SourceFilter

    fun matches(feed: Feed, now: Instant): Boolean = when (this) {
        All -> true
        is Tagged -> feed.tags.any { it.equals(tag, ignoreCase = true) }
        Untagged -> feed.tags.isEmpty()
        Muted -> feed.isMuted(now)
    }

    /** A string for saved instance state; [decode] reads it back. */
    fun encode(): String = when (this) {
        All -> "all"
        is Tagged -> "tag:$tag"
        Untagged -> "untagged"
        Muted -> "muted"
    }

    companion object {
        fun decode(value: String?): SourceFilter = when {
            value == null || value == "all" -> All
            value == "untagged" -> Untagged
            value == "muted" -> Muted
            value.startsWith("tag:") -> Tagged(value.removePrefix("tag:"))
            else -> All
        }

        /**
         * The filter to show when the feeds change under [filter]: itself while it still matches
         * something, otherwise [All] (the last feed of a tag was retagged, the last mute lapsed).
         */
        fun valid(filter: SourceFilter, feeds: List<Feed>, now: Instant): SourceFilter =
            if (filter == All || feeds.any { filter.matches(it, now) }) filter else All
    }
}

/** What "Why is this here?" says when the builder stored no reason of its own. */
object StoryReason {
    /** A string resource; the section one takes the section name as its argument. */
    fun fallback(kind: ItemKind): Int = when (kind) {
        ItemKind.LEAD -> R.string.reason_lead
        ItemKind.FRONT -> R.string.reason_front
        ItemKind.TOP -> R.string.reason_top
        ItemKind.SECTION -> R.string.reason_section
        ItemKind.BRIEF -> R.string.reason_brief
        ItemKind.ARCHIVE -> R.string.reason_archive
        ItemKind.AWAY -> R.string.reason_away
        ItemKind.INDEX -> R.string.reason_index
    }
}

/**
 * How a picture of [imageWidth] × [imageHeight] is framed where the layout would like
 * [preferredRatio] (width ÷ height).
 *
 * A picture close to the preferred shape is cropped to it, which loses little and keeps pages
 * regular. One that is much wider (a panorama, a comic strip) gets a shallower frame instead of
 * losing its ends. One that is much taller is never cropped either: the frame grows to
 * [tallestRatio] and anything taller still is fitted inside it whole, with bands at the sides.
 */
data class ImageFrame(val ratio: Float, val fit: Boolean) {
    companion object {
        /** Crops of up to about a quarter of the picture are accepted. */
        const val TOLERANCE = 0.75f
        const val WIDEST_RATIO = 3.2f

        fun of(imageWidth: Float, imageHeight: Float, preferredRatio: Float, tallestRatio: Float = 1f): ImageFrame {
            val unknown = !(imageWidth > 0f) || !(imageHeight > 0f) || imageWidth.isInfinite() || imageHeight.isInfinite()
            if (unknown) return ImageFrame(preferredRatio, fit = false)
            val ratio = imageWidth / imageHeight
            val tallest = tallestRatio.coerceAtMost(preferredRatio)
            return when {
                ratio >= preferredRatio * TOLERANCE && ratio * TOLERANCE <= preferredRatio -> ImageFrame(preferredRatio, fit = false)
                ratio > preferredRatio -> ImageFrame(ratio.coerceAtMost(WIDEST_RATIO), fit = ratio > WIDEST_RATIO / TOLERANCE)
                ratio >= tallest -> ImageFrame(ratio, fit = false)
                else -> ImageFrame(tallest, fit = true)
            }
        }
    }
}
