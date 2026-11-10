package net.tgnra.morningissue.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import net.tgnra.morningissue.R

private val WEIGHTS = listOf(
    FontWeight.Normal,
    FontWeight.Medium,
    FontWeight.SemiBold,
    FontWeight.Bold,
    FontWeight.ExtraBold,
)

/**
 * One family from a variable font file (and optionally its italic companion), exposing the
 * weights in [WEIGHTS]. [opticalSize] pins the `opsz` axis, which Android does not set itself.
 */
private fun variableFamily(upright: Int, italic: Int?, opticalSize: Float): FontFamily {
    fun font(resId: Int, weight: FontWeight, style: FontStyle) = Font(
        resId = resId,
        weight = weight,
        style = style,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight.weight),
            FontVariation.Setting("opsz", opticalSize),
        ),
    )
    return FontFamily(
        WEIGHTS.map { font(upright, it, FontStyle.Normal) } +
            if (italic != null) WEIGHTS.map { font(italic, it, FontStyle.Italic) } else emptyList(),
    )
}

/** The three bundled OFL variable fonts. Change a face here and every screen follows. */
object PaperFonts {
    /** Fraunces: headlines and the masthead. */
    val Headline: FontFamily = variableFamily(R.font.fraunces, R.font.fraunces_italic, opticalSize = 72f)

    /** Newsreader: standfirsts and article text. */
    val Body: FontFamily = variableFamily(R.font.newsreader, R.font.newsreader_italic, opticalSize = 16f)

    /** Inter: bylines, labels, buttons. */
    val Label: FontFamily = variableFamily(R.font.inter, italic = null, opticalSize = 14f)
}

/** The paper's type scale. Read it through `MorningIssueTheme.typography`. */
@Immutable
data class PaperTypography(
    /** The app's name on the front page and covers. */
    val masthead: TextStyle,
    /** Lead story headline. */
    val leadHeadline: TextStyle,
    /** Secondary story headline, and the article title in the reader. */
    val headline: TextStyle,
    /** Headlines in dense places: section stories, covers, search results. */
    val headlineSmall: TextStyle,
    /** The lead's standfirst and story excerpts. */
    val standfirst: TextStyle,
    /** Running text on pages. */
    val body: TextStyle,
    /** Article text in the reader. */
    val reader: TextStyle,
    /** Headings inside an article. */
    val readerHeading: TextStyle,
    /** Block quotes inside an article. */
    val quote: TextStyle,
    /** Code and preformatted blocks. */
    val code: TextStyle,
    /** One line of In brief or of the back-page index. */
    val brief: TextStyle,
    /** "Daring Fireball · 12 min read". */
    val byline: TextStyle,
    /** Buttons, chips, menu items. */
    val label: TextStyle,
    /** Section names and page furniture: upper-case, letter-spaced. */
    val sectionLabel: TextStyle,
    /** Image captions and fine print. */
    val caption: TextStyle,
)

/**
 * Builds the type scale. [scale] comes from `PaperLayoutSpec.typeScale`; [eink] raises body
 * weights one step for e-ink screens.
 */
fun paperTypography(scale: Float = 1f, eink: Boolean = false): PaperTypography {
    val bodyWeight = if (eink) FontWeight.Medium else FontWeight.Normal
    val labelWeight = if (eink) FontWeight.SemiBold else FontWeight.Medium
    fun style(family: FontFamily, weight: FontWeight, size: Float, lineHeight: Float, tracking: Float = 0f) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = (size * scale).sp,
        lineHeight = (lineHeight * scale).sp,
        letterSpacing = tracking.em,
    )
    return PaperTypography(
        masthead = style(PaperFonts.Headline, FontWeight.ExtraBold, 26f, 30f, -0.01f),
        leadHeadline = style(PaperFonts.Headline, FontWeight.Bold, 32f, 36f, -0.015f),
        headline = style(PaperFonts.Headline, FontWeight.SemiBold, 22f, 26f, -0.01f),
        headlineSmall = style(PaperFonts.Headline, FontWeight.SemiBold, 17f, 21f),
        standfirst = style(PaperFonts.Body, bodyWeight, 17f, 24f),
        body = style(PaperFonts.Body, bodyWeight, 15f, 21f),
        reader = style(PaperFonts.Body, bodyWeight, 19f, 29f),
        readerHeading = style(PaperFonts.Headline, FontWeight.SemiBold, 22f, 28f),
        quote = style(PaperFonts.Body, bodyWeight, 19f, 29f).copy(fontStyle = FontStyle.Italic),
        code = style(FontFamily.Monospace, bodyWeight, 14f, 20f),
        brief = style(PaperFonts.Body, labelWeight, 16f, 21f),
        byline = style(PaperFonts.Label, labelWeight, 12f, 16f, 0.01f),
        label = style(PaperFonts.Label, labelWeight, 14f, 20f, 0.01f),
        sectionLabel = style(PaperFonts.Label, FontWeight.Bold, 12f, 16f, 0.12f),
        caption = style(PaperFonts.Label, bodyWeight, 12f, 16f),
    )
}

val LocalPaperTypography = staticCompositionLocalOf { paperTypography() }

/** Material 3 typography mapped from the paper scale, so stock components use the same faces. */
internal fun PaperTypography.toMaterialTypography(): Typography = Typography(
    displayLarge = leadHeadline,
    displayMedium = leadHeadline,
    displaySmall = leadHeadline,
    headlineLarge = leadHeadline,
    headlineMedium = headline,
    headlineSmall = headline,
    titleLarge = headline,
    titleMedium = headlineSmall,
    titleSmall = label,
    bodyLarge = standfirst,
    bodyMedium = body,
    bodySmall = caption,
    labelLarge = label,
    labelMedium = byline,
    labelSmall = byline,
)
