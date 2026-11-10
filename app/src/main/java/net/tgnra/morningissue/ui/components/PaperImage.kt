package net.tgnra.morningissue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** How images are treated per display mode. */
object ImageTreatment {
    /** Contrast multiplier applied to greyscale images in E-ink mode. */
    const val EINK_CONTRAST = 1.3f

    /**
     * Greyscale (Rec. 709 luma) with contrast raised around mid-grey, as a 4×5 colour matrix:
     * e-ink panels render few grey levels, so flat photos turn to mud without it.
     */
    fun einkMatrix(contrast: Float = EINK_CONTRAST): FloatArray {
        val r = 0.2126f * contrast
        val g = 0.7152f * contrast
        val b = 0.0722f * contrast
        val shift = (1f - contrast) * 127.5f
        return floatArrayOf(
            r, g, b, 0f, shift,
            r, g, b, 0f, shift,
            r, g, b, 0f, shift,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    private val einkFilter: ColorFilter by lazy { ColorFilter.colorMatrix(ColorMatrix(einkMatrix())) }

    /** The filter every image is drawn through; null in standard mode. */
    val colorFilter: ColorFilter?
        @Composable @ReadOnlyComposable get() = if (MorningIssueTheme.isEink) einkFilter else null

    /**
     * Whether small pictures beside a story are worth showing. E-ink drops them in favour of
     * text: at thumbnail size a dithered photo is noise.
     */
    val showThumbnails: Boolean
        @Composable @ReadOnlyComposable get() = !MorningIssueTheme.isEink
}

/**
 * A remote image in the paper's manner: square-cornered, flat placeholder while it loads (or
 * if it never does), no crossfade, and greyscale with raised contrast in E-ink mode.
 */
@Composable
fun PaperImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val colors = MorningIssueTheme.colors
    val placeholder = if (colors.isEink) Modifier.border(1.dp, colors.ink) else Modifier.background(colors.hairline.copy(alpha = 0.45f))
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier.then(placeholder),
        contentScale = contentScale,
        colorFilter = ImageTreatment.colorFilter,
    )
}
