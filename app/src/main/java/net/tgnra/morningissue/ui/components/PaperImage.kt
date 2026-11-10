package net.tgnra.morningissue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.util.LruCache
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.tgnra.morningissue.ui.learning.ImageFrame
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
    onSize: ((width: Int, height: Int) -> Unit)? = null,
) {
    val colors = MorningIssueTheme.colors
    val placeholder = if (colors.isEink) Modifier.border(1.dp, colors.ink) else Modifier.background(colors.hairline.copy(alpha = 0.45f))
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier.then(placeholder),
        contentScale = contentScale,
        colorFilter = ImageTreatment.colorFilter,
        onSuccess = onSize?.let { report -> { state -> report(state.result.image.width, state.result.image.height) } },
    )
}

/** Width ÷ height of pictures already seen, so a page turned back to does not jump as they load again. */
private val knownShapes = LruCache<String, Float>(256)

/**
 * A full-width picture set to [preferredRatio] unless that would cut much of it off: a strip or
 * panorama gets a shallower frame, a tall picture a taller one (down to [tallestRatio]) and
 * anything taller still is shown whole between bands. See [ImageFrame] for the rule.
 */
@Composable
fun FramedImage(
    url: String?,
    contentDescription: String?,
    preferredRatio: Float,
    modifier: Modifier = Modifier,
    tallestRatio: Float = 1f,
) {
    var shape by remember(url) { mutableStateOf(url?.let { knownShapes.get(it) }) }
    val frame = shape?.let { ImageFrame.of(it, 1f, preferredRatio, tallestRatio) } ?: ImageFrame(preferredRatio, fit = false)
    PaperImage(
        url = url,
        contentDescription = contentDescription,
        modifier = modifier.fillMaxWidth().aspectRatio(frame.ratio),
        contentScale = if (frame.fit) ContentScale.Fit else ContentScale.Crop,
        onSize = { width, height ->
            if (url != null && width > 0 && height > 0) {
                val ratio = width.toFloat() / height
                knownShapes.put(url, ratio)
                shape = ratio
            }
        },
    )
}
