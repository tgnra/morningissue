package net.tgnra.morningissue.ui.layout

import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The three layouts of the plan's tablet table. */
enum class PaperWidthClass { Compact, Medium, Expanded }

enum class NewsstandArrangement {
    /** Today's cover above one horizontally scrolling shelf. */
    SingleShelf,

    /** Today's cover above two shelves. */
    TwoShelves,

    /** Today's cover on the left, a grid of shelves on the right. */
    CoverWithShelfGrid,
}

/** Every size-dependent layout decision, made once per window and passed down. */
@Immutable
data class PaperLayoutSpec(
    val widthClass: PaperWidthClass,
    /** Columns of the paper's story grid. */
    val columns: Int,
    /** How many of [columns] the lead story spans when secondaries sit beside it; 0 = full width. */
    val leadColumnSpan: Int,
    /** Space between the page edge and its content. */
    val pageMargin: Dp,
    /** Space between columns. */
    val gutter: Dp,
    /** Multiplier applied to the whole type scale. */
    val typeScale: Float,
    /** Widest the reader's text column may be (about 66 characters); [Dp.Unspecified] = full width. */
    val readerMaxMeasure: Dp,
    /** Whether the reader shows the contents list as a side panel. */
    val readerSidePanel: Boolean,
    val newsstand: NewsstandArrangement,
    /** The newsstand's buttons go in a side rail instead of a bottom bar. */
    val useButtonRail: Boolean,
) {
    companion object {
        /** Phones in portrait. */
        val Compact = PaperLayoutSpec(
            widthClass = PaperWidthClass.Compact,
            columns = 2,
            leadColumnSpan = 0,
            pageMargin = 16.dp,
            gutter = 12.dp,
            typeScale = 1f,
            readerMaxMeasure = Dp.Unspecified,
            readerSidePanel = false,
            newsstand = NewsstandArrangement.SingleShelf,
            useButtonRail = false,
        )

        /** Small tablets, and phones in landscape. */
        val Medium = PaperLayoutSpec(
            widthClass = PaperWidthClass.Medium,
            columns = 3,
            leadColumnSpan = 0,
            pageMargin = 24.dp,
            gutter = 16.dp,
            typeScale = 1.05f,
            readerMaxMeasure = 600.dp,
            readerSidePanel = false,
            newsstand = NewsstandArrangement.TwoShelves,
            useButtonRail = false,
        )

        /** Tablets in landscape. */
        val Expanded = PaperLayoutSpec(
            widthClass = PaperWidthClass.Expanded,
            columns = 4,
            leadColumnSpan = 2,
            pageMargin = 32.dp,
            gutter = 20.dp,
            typeScale = 1.1f,
            readerMaxMeasure = 640.dp,
            readerSidePanel = true,
            newsstand = NewsstandArrangement.CoverWithShelfGrid,
            useButtonRail = true,
        )

        fun from(windowSizeClass: WindowSizeClass): PaperLayoutSpec =
            from(windowSizeClass.widthSizeClass, windowSizeClass.heightSizeClass)

        /**
         * A wide but short window is a phone in landscape, which the plan puts in Medium:
         * there is no room for the four-column tablet page.
         */
        fun from(width: WindowWidthSizeClass, height: WindowHeightSizeClass): PaperLayoutSpec = when {
            width == WindowWidthSizeClass.Compact -> Compact
            width == WindowWidthSizeClass.Medium -> Medium
            height == WindowHeightSizeClass.Compact -> Medium
            else -> Expanded
        }
    }
}

val LocalPaperLayoutSpec = staticCompositionLocalOf { PaperLayoutSpec.Compact }
