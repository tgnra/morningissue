package net.tgnra.morningissue.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import kotlin.math.abs
import net.tgnra.morningissue.R
import net.tgnra.morningissue.data.images.StoredImageFetcher.Companion.original
import net.tgnra.morningissue.ui.components.ImageTreatment
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperIconButton
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** The arithmetic of the full-screen picture: how far it may be enlarged and moved. Pure. */
object ImageZoom {
    /** The picture fitted to the screen. */
    const val MIN_SCALE = 1f

    /** Past this a picture decoded for the viewer is only blur. */
    const val MAX_SCALE = 5f

    /** Where a double tap goes from the fitted picture. */
    const val DOUBLE_TAP_SCALE = 2.5f

    /** The enlargements E-ink mode steps through: no pinch there, since every frame of one is a full redraw. */
    val EINK_STEPS = listOf(1f, 2f, 4f)

    /** How much of the screen one E-ink swipe moves an enlarged picture; the rest overlaps. */
    const val EINK_PAN_FRACTION = 0.8f

    /**
     * The longest edge, in pixels, the original is asked for at. Coil decodes at the smallest
     * power-of-two reduction that is not below this, so a 40-megapixel original is held at a
     * quarter of its pixels and nothing is ever enlarged.
     */
    const val ORIGINAL_EDGE_PX = 2048

    /** The size a picture of [imageRatio] (width ÷ height) takes when fitted inside [container]. */
    fun fitted(container: Size, imageRatio: Float?): Size {
        if (imageRatio == null || imageRatio <= 0f || container.width <= 0f || container.height <= 0f) return container
        return if (imageRatio > container.width / container.height) {
            Size(container.width, container.width / imageRatio)
        } else {
            Size(container.height * imageRatio, container.height)
        }
    }

    /**
     * [offset] held to where the picture, [fitted] at 1× and enlarged by [scale] about the
     * centre of [container], still covers the screen: an edge of the picture never comes
     * further in than the screen's edge, and a picture smaller than the screen stays centred.
     */
    fun clamp(offset: Offset, scale: Float, container: Size, fitted: Size): Offset {
        val maxX = ((fitted.width * scale - container.width) / 2f).coerceAtLeast(0f)
        val maxY = ((fitted.height * scale - container.height) / 2f).coerceAtLeast(0f)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    /**
     * One step of a pinch or drag: the scale multiplied by [zoom] (within [MIN_SCALE] and
     * [MAX_SCALE]) so that the point under [centroid] stays under the fingers, then moved by [pan].
     */
    fun transform(scale: Float, offset: Offset, centroid: Offset, pan: Offset, zoom: Float, container: Size, fitted: Size): Pair<Float, Offset> {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val fromCentre = centroid - Offset(container.width / 2f, container.height / 2f)
        val moved = (offset - fromCentre) * (newScale / scale) + fromCentre + pan
        return newScale to clamp(moved, newScale, container, fitted)
    }

    /** The next E-ink step up or down from [scale]; the same scale at either end. */
    fun einkStep(scale: Float, up: Boolean): Float =
        if (up) EINK_STEPS.firstOrNull { it > scale + 0.01f } ?: EINK_STEPS.last() else EINK_STEPS.lastOrNull { it < scale - 0.01f } ?: EINK_STEPS.first()

    /**
     * Where an E-ink swipe of [drag] leaves an enlarged picture: one screen, less the overlap,
     * along the swipe's main direction. The content follows the finger, so a swipe to the left
     * shows what was to the right.
     */
    fun einkPan(offset: Offset, drag: Offset, scale: Float, container: Size, fitted: Size): Offset {
        val step = if (abs(drag.x) >= abs(drag.y)) {
            Offset(if (drag.x < 0) -container.width * EINK_PAN_FRACTION else container.width * EINK_PAN_FRACTION, 0f)
        } else {
            Offset(0f, if (drag.y < 0) -container.height * EINK_PAN_FRACTION else container.height * EINK_PAN_FRACTION)
        }
        return clamp(offset + step, scale, container, fitted)
    }
}

private enum class OriginalState { Loading, Shown, Failed }

/**
 * A picture from an article, full screen, loaded from its own address: the original, not the
 * smaller copy the image store keeps.
 *
 * - The copy already on the device is shown at once, underneath, and the original is drawn
 *   over it when it arrives. If it does not arrive (no connection, or the site has dropped
 *   the picture) a line says so and the copy stays.
 * - Pinch to enlarge up to [ImageZoom.MAX_SCALE], drag to move, double tap to enlarge and to
 *   fit again. The picture cannot be dragged off the screen.
 * - The arrow, or the system's back, returns to the article where it was left.
 * - In E-ink mode nothing follows the finger: Enlarge and Reduce step through fixed sizes,
 *   and a swipe moves an enlarged picture by most of a screen once the finger lifts.
 * - The greyscale option and E-ink's contrast apply here too, without the dither: the
 *   picture is drawn scaled, and a dither pattern made for one size smears at another.
 */
@Composable
fun ImageViewer(url: String, contentDescription: String?, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val colors = MorningIssueTheme.colors
    val eink = MorningIssueTheme.isEink
    val tone = ImageTreatment.tone
    val context = LocalPlatformContext.current

    var scale by remember(url) { mutableFloatStateOf(ImageZoom.MIN_SCALE) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(Size.Zero) }
    var ratio by remember(url) { mutableStateOf<Float?>(null) }
    var state by remember(url) { mutableStateOf(OriginalState.Loading) }
    // Read when a gesture happens, not when it was set up: the picture's shape arrives late.
    fun fitted(): Size = ImageZoom.fitted(container, ratio)

    val kept = remember(context, url, tone) { ImageTreatment.request(context, url, tone, dither = false).build() }
    val original = remember(context, url, tone) {
        ImageTreatment.request(context, url, tone, dither = false).original().size(ImageZoom.ORIGINAL_EDGE_PX).build()
    }

    val gestures = if (eink) {
        Modifier.pointerInput(url) {
            var drag = Offset.Zero
            detectDragGestures(
                onDragStart = { drag = Offset.Zero },
                onDrag = { _, amount -> drag += amount },
                onDragEnd = { if (scale > ImageZoom.MIN_SCALE) offset = ImageZoom.einkPan(offset, drag, scale, container, fitted()) },
            )
        }
    } else {
        Modifier
            .pointerInput(url) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > ImageZoom.MIN_SCALE) {
                            scale = ImageZoom.MIN_SCALE
                            offset = Offset.Zero
                        } else {
                            val (newScale, newOffset) = ImageZoom.transform(scale, offset, tap, Offset.Zero, ImageZoom.DOUBLE_TAP_SCALE, container, fitted())
                            scale = newScale
                            offset = newOffset
                        }
                    },
                )
            }
            .pointerInput(url) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val (newScale, newOffset) = ImageZoom.transform(scale, offset, centroid, pan, zoom, container, fitted())
                    scale = newScale
                    offset = newOffset
                }
            }
    }
    val placed = Modifier.fillMaxSize().graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationX = offset.x
        translationY = offset.y
    }

    // The empty pointer input makes this layer take every touch, so none reaches the article beneath.
    Box(Modifier.fillMaxSize().background(colors.paper).pointerInput(Unit) {}) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                PaperIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.viewer_back), onClose)
                SectionLabel(
                    text = stringResource(
                        when (state) {
                            OriginalState.Loading -> R.string.viewer_loading
                            OriginalState.Shown -> R.string.viewer_original
                            OriginalState.Failed -> R.string.viewer_kept_copy
                        },
                    ),
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { container = it.toSize() }.then(gestures),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = kept,
                    contentDescription = contentDescription,
                    modifier = placed,
                    contentScale = ContentScale.Fit,
                    onSuccess = { result -> if (ratio == null) ratio = ratioOf(result.result.image.width, result.result.image.height) },
                )
                AsyncImage(
                    model = original,
                    contentDescription = null,
                    modifier = placed,
                    contentScale = ContentScale.Fit,
                    onSuccess = { result ->
                        ratio = ratioOf(result.result.image.width, result.result.image.height) ?: ratio
                        state = OriginalState.Shown
                    },
                    onError = { state = OriginalState.Failed },
                )
            }
            if (state == OriginalState.Failed) {
                NoticeLine(stringResource(R.string.viewer_failed), Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            if (eink) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PaperButton(
                        stringResource(R.string.viewer_reduce),
                        onClick = {
                            scale = ImageZoom.einkStep(scale, up = false)
                            offset = ImageZoom.clamp(offset, scale, container, fitted())
                        },
                        modifier = Modifier.weight(1f),
                        enabled = scale > ImageZoom.EINK_STEPS.first(),
                    )
                    PaperButton(
                        stringResource(R.string.viewer_enlarge),
                        onClick = { scale = ImageZoom.einkStep(scale, up = true) },
                        modifier = Modifier.weight(1f),
                        enabled = scale < ImageZoom.EINK_STEPS.last(),
                    )
                }
            }
        }
    }
}

private fun ratioOf(width: Int, height: Int): Float? = if (width > 0 && height > 0) width.toFloat() / height else null
