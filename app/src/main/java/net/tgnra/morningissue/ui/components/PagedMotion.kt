package net.tgnra.morningissue.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/*
 * Everything that moves differently in E-ink mode lives here, so screens ask for "paged"
 * behaviour once instead of testing the display mode themselves:
 *
 *  - pagedVerticalScroll: free scrolling normally; in E-ink, whole-screen steps on a swipe,
 *    snapped to the anchors that content registers with stepAnchor.
 *  - pagedKeys: volume and page keys step forward and back in E-ink.
 *  - turnToPage: an animated pager scroll normally, an instant jump in E-ink.
 */

/** The maths of step scrolling, kept pure so it can be tested. All values are pixels. */
object StepScroll {
    /**
     * Where a step forward from [current] should land: one [viewport] further less [overlap],
     * pulled back to the nearest anchor (a story row or line top) so nothing is cut in half,
     * as long as that still moves at least half a screen. Never beyond [max].
     *
     * [contentMax] is the furthest position at which the screen is still full of content; it is
     * less than [max] when blank filler follows the content. When the end is within this step,
     * the step lands on the first anchor at or after [contentMax]: everything left is shown and
     * as little of the screen as possible is blank, at the price of repeating a few lines. If
     * that anchor would still leave more than a third of the screen blank (the content ends in
     * something tall, such as a lead picture), the step lands on [contentMax] itself: a full
     * screen, even though its top edge may cut through what is there.
     */
    fun forward(current: Int, viewport: Int, max: Int, anchors: IntArray, overlap: Int, contentMax: Int = max): Int {
        if (viewport <= 0 || current >= max) return current.coerceAtMost(max.coerceAtLeast(0))
        val raw = current + (viewport - overlap).coerceAtLeast(1)
        if (raw >= max) return max
        if (contentMax < max && raw >= contentMax) {
            if (contentMax <= current) return current
            val furthest = minOf(raw, contentMax + viewport / 3)
            var fullest = Int.MAX_VALUE
            for (anchor in anchors) if (anchor in contentMax..furthest && anchor < fullest) fullest = anchor
            return if (fullest != Int.MAX_VALUE) fullest else contentMax
        }
        val least = current + viewport / 2
        var best = -1
        for (anchor in anchors) if (anchor in (least + 1)..raw && anchor > best) best = anchor
        return if (best >= 0) best else raw
    }

    /** The mirror of [forward]: one screen back, pushed on to the nearest anchor. Never below 0. */
    fun backward(current: Int, viewport: Int, anchors: IntArray, overlap: Int): Int {
        if (viewport <= 0 || current <= 0) return current.coerceAtLeast(0)
        val raw = current - (viewport - overlap).coerceAtLeast(1)
        if (raw <= 0) return 0
        val most = current - viewport / 2
        var best = Int.MAX_VALUE
        for (anchor in anchors) if (anchor in raw until most && anchor < best) best = anchor
        return if (best != Int.MAX_VALUE) best else raw
    }
}

/**
 * A vertical scroll position that can also move in whole-screen steps. Content registers the
 * tops of its rows and lines with [stepAnchor]; they are only measured when a step is taken.
 */
@Stable
class PagedScroller(val scrollState: ScrollState, private val overlapPx: Int) {
    private class Anchor(var coordinates: LayoutCoordinates?, var lineTops: IntArray)

    private var content: LayoutCoordinates? = null
    private val anchors = HashMap<Any, Anchor>()

    internal fun setContent(coordinates: LayoutCoordinates) {
        content = coordinates
    }

    internal fun setAnchor(key: Any, coordinates: LayoutCoordinates) {
        anchors.getOrPut(key) { Anchor(null, NO_LINES) }.coordinates = coordinates
    }

    internal fun setLines(key: Any, lineTops: IntArray) {
        anchors.getOrPut(key) { Anchor(null, NO_LINES) }.lineTops = lineTops
    }

    internal fun removeAnchor(key: Any) {
        anchors.remove(key)
    }

    private fun anchorPositions(): IntArray {
        val root = content?.takeIf { it.isAttached } ?: return IntArray(0)
        val positions = ArrayList<Int>(anchors.size)
        for (anchor in anchors.values) {
            val coordinates = anchor.coordinates?.takeIf { it.isAttached } ?: continue
            val top = root.localPositionOf(coordinates, Offset.Zero).y.toInt()
            positions += top
            for (line in anchor.lineTops) positions += top + line
        }
        return positions.toIntArray()
    }

    /**
     * Blank space added after the content in E-ink mode (one viewport's worth), so the last
     * step can stop at an anchor like every other instead of slicing a line at the top edge.
     */
    internal var fillerPx by mutableIntStateOf(0)

    /** The furthest scroll position that still shows content: the scroll range less the filler. */
    val maxScroll: Int get() = (scrollState.maxValue - fillerPx).coerceAtLeast(0)

    val canStepForward: Boolean get() = scrollState.value < maxScroll
    val canStepBackward: Boolean get() = scrollState.value > 0

    /** Jumps one screen on; returns false when already at the end. */
    suspend fun stepForward(): Boolean {
        if (!canStepForward) return false
        val target = StepScroll.forward(
            scrollState.value, scrollState.viewportSize, scrollState.maxValue, anchorPositions(), overlapPx,
            contentMax = maxScroll,
        )
        // With the filler the range runs a screen past the content; never land wholly in the blank.
        val lastUseful = if (fillerPx > 0) maxScroll + scrollState.viewportSize - overlapPx else scrollState.maxValue
        scrollState.scrollTo(target.coerceAtMost(lastUseful.coerceAtLeast(maxScroll)))
        return true
    }

    /** Jumps one screen back; returns false when already at the top. */
    suspend fun stepBackward(): Boolean {
        if (!canStepBackward) return false
        scrollState.scrollTo(StepScroll.backward(scrollState.value, scrollState.viewportSize, anchorPositions(), overlapPx))
        return true
    }

    private companion object {
        val NO_LINES = IntArray(0)
    }
}

/** The scroller whose content is being composed, for [stepAnchor]. */
val LocalPagedScroller = compositionLocalOf<PagedScroller?> { null }

@Composable
fun rememberPagedScroller(
    scrollState: ScrollState = rememberScrollState(),
    overlap: Dp = 28.dp,
): PagedScroller {
    val overlapPx = with(LocalDensity.current) { overlap.roundToPx() }
    return remember(scrollState, overlapPx) { PagedScroller(scrollState, overlapPx) }
}

/**
 * Scrolls freely in standard mode. In E-ink mode dragging does nothing until the finger lifts,
 * then the content jumps one screen in the direction of the swipe without animation.
 * [onHorizontalSwipe] receives sideways swipes in E-ink mode (true = towards the next page).
 */
@Composable
fun Modifier.pagedVerticalScroll(
    scroller: PagedScroller,
    onHorizontalSwipe: ((forward: Boolean) -> Unit)? = null,
): Modifier {
    if (!MorningIssueTheme.isEink) {
        SideEffect { scroller.fillerPx = 0 }
        return verticalScroll(scroller.scrollState)
    }
    val filler = scroller.scrollState.viewportSize
    SideEffect { scroller.fillerPx = filler }
    val scope = rememberCoroutineScope()
    val horizontal = rememberUpdatedState(onHorizontalSwipe)
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    return this
        .pointerInput(scroller) {
            var total = Offset.Zero
            detectDragGestures(
                onDragStart = { total = Offset.Zero },
                onDragEnd = {
                    val swipe = total
                    when {
                        abs(swipe.x) > abs(swipe.y) && abs(swipe.x) > threshold -> horizontal.value?.invoke(swipe.x < 0)
                        abs(swipe.y) > threshold -> scope.launch {
                            if (swipe.y < 0) scroller.stepForward() else scroller.stepBackward()
                        }
                    }
                },
            ) { change, drag ->
                change.consume()
                total += drag
            }
        }
        .verticalScroll(scroller.scrollState, enabled = false)
        .onGloballyPositioned { scroller.setContent(it) }
        .padding(bottom = with(LocalDensity.current) { filler.toDp() })
}

/**
 * Marks the top of this element as a place an E-ink step may stop at. Does nothing in standard
 * mode or outside a [pagedVerticalScroll].
 */
@Composable
fun Modifier.stepAnchor(): Modifier = stepAnchorWithKey().first

/**
 * As [stepAnchor], also returning a callback for reporting the tops of the text lines inside
 * the element (from `TextLayoutResult`), so long paragraphs can be stepped through line by line.
 */
@Composable
fun Modifier.stepAnchorWithLines(): Pair<Modifier, (IntArray) -> Unit> {
    val (modifier, key) = stepAnchorWithKey()
    val scroller = LocalPagedScroller.current
    if (key == null || scroller == null) return modifier to NoLines
    return modifier to remember(scroller, key) { { lines: IntArray -> scroller.setLines(key, lines) } }
}

private val NoLines: (IntArray) -> Unit = {}

@Composable
private fun Modifier.stepAnchorWithKey(): Pair<Modifier, Any?> {
    val scroller = LocalPagedScroller.current
    if (scroller == null || !MorningIssueTheme.isEink) return this to null
    val key = remember { Any() }
    DisposableEffect(scroller, key) { onDispose { scroller.removeAnchor(key) } }
    return onGloballyPositioned { scroller.setAnchor(key, it) } to key
}

/**
 * In E-ink mode, takes key focus and turns the volume and page keys into [onForward] and
 * [onBackward] (e-readers map their page buttons to these). Leaves the keys alone otherwise.
 */
@Composable
fun Modifier.pagedKeys(onForward: () -> Unit, onBackward: () -> Unit): Modifier {
    if (!MorningIssueTheme.isEink) return this
    val focusRequester = remember { FocusRequester() }
    val forward = rememberUpdatedState(onForward)
    val backward = rememberUpdatedState(onBackward)
    LaunchedEffect(focusRequester) { runCatching { focusRequester.requestFocus() } }
    return this
        .focusRequester(focusRequester)
        .onPreviewKeyEvent { event ->
            val action = when (event.key) {
                Key.VolumeDown, Key.PageDown, Key.DirectionRight, Key.Spacebar -> forward
                Key.VolumeUp, Key.PageUp, Key.DirectionLeft -> backward
                else -> return@onPreviewKeyEvent false
            }
            if (event.type == KeyEventType.KeyDown) action.value()
            true
        }
        .focusable()
}

/**
 * Goes to [page]: animated in standard mode (with [animation] when given, else the pager's
 * own), an instant jump when [animate] is false (E-ink).
 */
suspend fun PagerState.turnToPage(page: Int, animate: Boolean, animation: AnimationSpec<Float>? = null) {
    val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    when {
        !animate -> scrollToPage(target)
        animation != null -> animateScrollToPage(target, animationSpec = animation)
        else -> animateScrollToPage(target)
    }
}
