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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Precision
import net.tgnra.morningissue.ui.theme.LocalGreyscaleImages
import net.tgnra.morningissue.ui.learning.ImageFrame
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** How images are treated per display mode. */
object ImageTreatment {
    /** Contrast multiplier applied to greyscale images in E-ink mode. */
    const val EINK_CONTRAST = 1.3f

    /**
     * Greyscale (Rec. 709 luma) with contrast raised around mid-grey, as a 4×5 colour matrix:
     * e-ink panels render few grey levels, so flat photos turn to mud without it. Images are
     * no longer drawn through this as a filter (dithering needs the pixels, see
     * [ToneTransformation]); it remains the statement of the arithmetic `Dither.greyscale`
     * does, and a test holds the two together.
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

    /**
     * How images are drawn here: E-ink mode's treatment in E-ink mode, greyscale when the
     * Settings option is on, colour otherwise.
     */
    val tone: ImageTone
        @Composable @ReadOnlyComposable get() = when {
            MorningIssueTheme.isEink -> ImageTone.EINK
            LocalGreyscaleImages.current -> ImageTone.GREY
            else -> ImageTone.COLOUR
        }

    /**
     * The request for [url] drawn in [tone]. In colour it is the plain request Coil would make
     * for the address. In a grey tone it carries the [ToneTransformation] and asks for the
     * bitmap at exactly the size it is shown, so that the dither lands on screen pixels; the
     * tone is then part of the memory-cache key.
     *
     * @param dither off for a picture that will be drawn scaled (the full-screen viewer), where
     *   a pattern made for one size would smear
     */
    fun request(context: PlatformContext, url: String?, tone: ImageTone, dither: Boolean = true): ImageRequest.Builder {
        val builder = ImageRequest.Builder(context).data(url)
        if (tone != ImageTone.COLOUR) {
            builder.transformations(ToneTransformation(tone, dither))
            if (dither) builder.precision(Precision.EXACT)
        }
        return builder
    }

    /**
     * Whether small pictures beside a story are worth showing. E-ink drops them in favour of
     * text: at thumbnail size a dithered photo is noise.
     */
    val showThumbnails: Boolean
        @Composable @ReadOnlyComposable get() = !MorningIssueTheme.isEink
}

/**
 * A remote image in the paper's manner: square-cornered, flat placeholder while it loads (or
 * if it never does), no crossfade, and drawn in the current [ImageTreatment.tone]: greyscale
 * and dithered when the Settings option is on, with raised contrast as well in E-ink mode.
 *
 * The picture comes from the image store when it is there and from the network when it is not
 * (`StoredImageFetcher`); nothing here knows which.
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
    val tone = ImageTreatment.tone
    val context = LocalPlatformContext.current
    val request = remember(context, url, tone) { ImageTreatment.request(context, url, tone).build() }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier.then(placeholder),
        contentScale = contentScale,
        // A dithered bitmap is made for these very pixels; smoothing would blur the pattern.
        filterQuality = if (tone == ImageTone.COLOUR) DrawScope.DefaultFilterQuality else FilterQuality.None,
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
