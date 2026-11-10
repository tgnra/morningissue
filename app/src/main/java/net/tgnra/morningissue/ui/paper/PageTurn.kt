package net.tgnra.morningissue.ui.paper

import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier

/**
 * How far [page] is from being the settled page, in pages: 0 when it is flat in view, -1 when
 * it is one whole page to the right (not yet turned to), +1 when it has been turned past.
 */
fun PagerState.turnProgress(page: Int): Float = (currentPage - page) + currentPageOffsetFraction

/**
 * The one place a page is transformed as the pager moves, applied to every page's root.
 *
 * Milestone 1 is the pager's own plain slide, so this adds nothing. The phase-3 page turn goes
 * here: a `graphicsLayer` driven by [turnProgress] (`rotationY` around the spine with a raised
 * camera distance, plus a darkening scrim), falling back to this plain slide in E-ink mode and
 * when the system animator scale is 0.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.pageTurn(pagerState: PagerState, page: Int): Modifier = this
