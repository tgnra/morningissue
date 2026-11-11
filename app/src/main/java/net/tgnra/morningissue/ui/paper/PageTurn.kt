package net.tgnra.morningissue.ui.paper

import android.provider.Settings
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.sqrt
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * How far [page] is from being the settled page, in pages: 0 when it is flat in view, -1 when
 * it is one whole page to the right (not yet turned to), +1 when it has been turned past.
 */
fun PagerState.turnProgress(page: Int): Float = (currentPage - page) + currentPageOffsetFraction

/** How the paper moves from one page to the next. */
enum class PageTurnStyle {
    /** E-ink: pages change at once, with no motion at all. */
    None,

    /** The pager's own sideways slide: when the system's animations are switched off. */
    Slide,

    /** Each page turns over on its spine. */
    Turn,
}

/** What [PageTurnMath.transform] asks of a page's layer. All of it is applied in the draw phase. */
data class PageTurnTransform(
    /** Degrees about the vertical axis through the spine; 0 is flat. */
    val rotationY: Float,
    /** Sideways shift as a fraction of the page's width, cancelling the pager's own slide. */
    val translationX: Float,
    /** Where the spine is: 0 = the left edge, 1 = the right edge. */
    val pivotX: Float,
    /** Opacity of the black scrim over the page, 0..1. */
    val shade: Float,
    /** Opacity of the line drawn down the page's free edge, 0..1. */
    val edge: Float,
) {
    companion object {
        /** Flat, where the pager put it. */
        val Flat = PageTurnTransform(rotationY = 0f, translationX = 0f, pivotX = 0f, shade = 0f, edge = 0f)
    }
}

/**
 * The geometry of the page turn, as pure functions of a page's [PagerState.turnProgress].
 *
 * A page is one sheet hinged at its spine: the left edge (the right edge in right-to-left
 * layouts). The sheet being turned (progress 0..1) stays where it is and swings up towards the
 * reader around the spine until it stands edge-on at progress 1, when it is gone: its back is
 * never seen. The sheet beneath (progress -1..0) lies flat in place and is uncovered, slightly
 * in the shadow of the sheet above it. Going back is the same motion in reverse. Any page a
 * whole turn or more away is left where the pager put it, off screen.
 */
object PageTurnMath {
    /** The turning sheet is darkened by up to this much as it stands up. */
    const val MAX_TURNING_SHADE = 0.22f

    /** The sheet beneath starts this much in shadow and clears as it is uncovered. */
    const val MAX_UNDER_SHADE = 0.16f

    /**
     * Until this far into a turn the sheet is still easing up off the page; from here on its
     * free edge is exactly under the finger.
     */
    const val LIFT_OFF = 0.25f

    /**
     * The angle in degrees (0..90) of a sheet [progress] (0..1) of the way through its turn,
     * chosen so that the sheet's free edge is *seen* at `1 - progress` of the page width: it
     * stays under the finger that drags it. [widthOverCamera] is the page width divided by the
     * camera's distance, both in pixels; 0 is a camera infinitely far away (no perspective).
     *
     * A sheet of width w at angle θ, lifted towards a camera at distance D, shows its free edge
     * at `w·cos θ · D / (D − w·sin θ)`. Setting that to `(1 − p)·w` gives
     * `cos θ + k·sin θ = 1 − p` with `k = (1 − p)·w/D`, whose solution that ends edge-on is
     * `θ = atan k + acos((1 − p) / √(1 + k²))`.
     *
     * With perspective that solution does not start flat: a sheet lifting towards the camera
     * first looms *outwards*, past the page's own edge, and only comes back inside it at
     * `2·atan(w/D)`. So the first [LIFT_OFF] of the turn eases the sheet up to the exact angle
     * (its edge is off the side of the page meanwhile, where it cannot be seen to lag), and the
     * rest of the turn follows the finger exactly.
     */
    fun turnAngle(progress: Float, widthOverCamera: Float): Float {
        val p = progress.coerceIn(0f, 1f).toDouble()
        val k = (1.0 - p) * widthOverCamera.coerceAtLeast(0f)
        val exact = atan(k) + acos(((1.0 - p) / sqrt(1.0 + k * k)).coerceIn(-1.0, 1.0))
        val lift = (p / LIFT_OFF).coerceIn(0.0, 1.0)
        val eased = lift * lift * (3.0 - 2.0 * lift)
        return Math.toDegrees(exact * eased).toFloat().coerceIn(0f, 90f)
    }

    /** Where the free edge of a sheet at [angleDegrees] is seen, as a fraction of the page width. */
    fun projectedEdge(angleDegrees: Float, widthOverCamera: Float): Float {
        val angle = Math.toRadians(angleDegrees.toDouble())
        return (Math.cos(angle) / (1.0 - widthOverCamera * Math.sin(angle))).toFloat()
    }

    /**
     * The layer transform of a page at [progress]. [rtl] mirrors it: the spine moves to the
     * right edge and the sheet swings the other way.
     */
    fun transform(progress: Float, widthOverCamera: Float, rtl: Boolean = false): PageTurnTransform {
        if (progress == 0f || abs(progress) >= 1f || progress.isNaN()) return PageTurnTransform.Flat
        val side = if (rtl) -1f else 1f
        // The pager has slid the page `progress` widths towards the start; put it back.
        val pinned = progress * side
        val pivot = if (rtl) 1f else 0f
        return PageTurnTransform(
            // Negative lifts the right-hand (free) edge towards the reader; mirrored in RTL.
            rotationY = if (progress > 0f) -turnAngle(progress, widthOverCamera) * side else 0f,
            translationX = pinned,
            pivotX = pivot,
            shade = shade(progress),
            edge = edge(progress),
        )
    }

    /**
     * How dark the scrim over a page at [progress] is, 0..1: the turning sheet darkens steadily
     * as it stands up; the sheet beneath starts in shadow and clears as it is uncovered.
     */
    fun shade(progress: Float): Float = when {
        progress == 0f || abs(progress) >= 1f || progress.isNaN() -> 0f
        progress > 0f -> MAX_TURNING_SHADE * progress
        else -> MAX_UNDER_SHADE * -progress
    }

    /** How strongly the line down a turning sheet's free edge is drawn, 0..1; it fades in as the turn starts. */
    fun edge(progress: Float): Float =
        if (progress > 0f && progress < 1f) (progress * 8f).coerceAtMost(1f) else 0f

    /**
     * How far the camera sits from a page of this size, in pixels. Measured against both sides
     * of the page: against the width alone, a tall phone page would loom far over the top and
     * bottom of the screen as it lifts, and a wide tablet page would barely seem to lift at all.
     */
    fun cameraDistance(width: Float, height: Float): Float = CAMERA_DISTANCE_IN_HALF_PERIMETERS * (width + height)

    private const val CAMERA_DISTANCE_IN_HALF_PERIMETERS = 2.5f
}

/** One unit of a layer's `cameraDistance`, in pixels. */
private const val CAMERA_UNIT_PX = 72f

/** The page-turn animation when a page is turned by a button, a key or the contents list. */
val PageTurnAnimation = tween<Float>(durationMillis = 420)

/**
 * The style to turn pages with: none in E-ink, the plain slide when the system's animations are
 * off (the animator duration scale is 0, as set by "Remove animations"), the page turn
 * otherwise. The setting is read again whenever the app comes back to the front.
 */
@Composable
fun rememberPageTurnStyle(): PageTurnStyle {
    if (MorningIssueTheme.isEink) return PageTurnStyle.None
    val resolver = LocalContext.current.contentResolver
    fun animationsOn() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var on by remember { mutableStateOf(animationsOn()) }
    LifecycleResumeEffect(resolver) {
        on = animationsOn()
        onPauseOrDispose { }
    }
    return if (on) PageTurnStyle.Turn else PageTurnStyle.Slide
}

/**
 * The one place a page is transformed as the pager moves, applied to every page's root. With
 * [PageTurnStyle.Turn] the page becomes an opaque sheet in a layer of its own that follows
 * [PageTurnMath]; the pager state is only read inside the layer block, so a turn re-runs
 * neither composition nor layout nor the page's drawing. Earlier pages are stacked above later
 * ones, because the sheet being turned always lies over the one it uncovers.
 */
@Composable
fun Modifier.pageTurn(pagerState: PagerState, page: Int, style: PageTurnStyle): Modifier {
    if (style != PageTurnStyle.Turn) return this
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return this
        .zIndex(-page.toFloat())
        .graphicsLayer {
            val camera = PageTurnMath.cameraDistance(size.width, size.height)
            // The layer counts camera distance in units of 72 pixels (checked on a device).
            cameraDistance = camera / CAMERA_UNIT_PX
            val turn = PageTurnMath.transform(pagerState.turnProgress(page), size.width / camera, rtl)
            transformOrigin = TransformOrigin(turn.pivotX, 0.5f)
            rotationY = turn.rotationY
            translationX = turn.translationX * size.width
        }
        .background(MorningIssueTheme.colors.paper)
}

/**
 * The shading of a turning page, laid over its content as the last child of the page's root:
 * a scrim that darkens the sheet as it stands up (and the sheet beneath while it is covered),
 * and a line down the free edge so the sheet reads against the same paper beneath it, which
 * matters most on the Night edition's black. Both are layers whose opacity alone changes.
 */
@Composable
fun BoxScope.PageTurnShading(pagerState: PagerState, page: Int, style: PageTurnStyle) {
    if (style != PageTurnStyle.Turn) return
    val colors = MorningIssueTheme.colors
    Box(
        Modifier
            .matchParentSize()
            .graphicsLayer { alpha = PageTurnMath.shade(pagerState.turnProgress(page)) }
            .background(Color.Black),
    )
    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(if (colors.isDark) 2.dp else 1.dp)
            .graphicsLayer { alpha = PageTurnMath.edge(pagerState.turnProgress(page)) }
            .background(colors.shelfEdge),
    )
}
