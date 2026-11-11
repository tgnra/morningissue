package net.tgnra.morningissue.ui.paper

import android.os.SystemClock
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Times how long each page of an edition is on screen, so that a story only counts as passed
 * over once the reader has had its page in front of them (`RankingConfig.pageSeenMinMillis`).
 *
 * **What a page is.** The pager's page: the front page, a Top stories page, a section, In
 * brief and so on, each `edition_item.page`. A page is timed while it is the pager's settled
 * page and the screen is resumed. In scrolling mode a page scrolls vertically; in E-ink mode it
 * is stepped a screen at a time (`PagedScroller`). Either way the whole page counts as seen
 * once it has been up for the threshold, including the stories below the first screen.
 *
 * Per page rather than per visible story, on purpose:
 * - Pages are what the builder composes and what the reader turns; a page is short (a front
 *   page, eight Top stories, six section stories, fifteen briefs), and a reader who stays on
 *   it for five seconds has looked at it, while one who turns on at once has not.
 * - Per-story visibility would need every story block's position on every screen, scroll
 *   offsets in two modes, and a threshold per story that a reader scanning headlines (a
 *   second or two each) would often miss even for stories they did read the headline of. It
 *   would also put many more rows behind every learning pass.
 * - The rule only removes evidence, never invents it: anything opened counts whatever the
 *   page time. Erring towards "seen" for the bottom of a long page is what every run counted
 *   as before page views existed.
 *
 * The time itself is measured by [PageViewMeter] on the monotonic clock, so a clock change in
 * the middle of reading does not create or lose time.
 */
@Composable
internal fun TrackPageViews(pagerState: PagerState, onViewed: (pageIndex: Int, millis: Long) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val record by rememberUpdatedState(onViewed)
    LaunchedEffect(pagerState, lifecycle) {
        val meter = PageViewMeter(SystemClock::elapsedRealtime)
        try {
            combine(snapshotFlow { pagerState.settledPage }, lifecycle.currentStateFlow) { page, state ->
                page.takeIf { state.isAtLeast(Lifecycle.State.RESUMED) }
            }
                .distinctUntilChanged()
                .collect { page -> meter.show(page)?.let { (index, millis) -> record(index, millis) } }
        } finally {
            // Leaving the paper (or the screen going off) ends the current page's time.
            meter.show(null)?.let { (index, millis) -> record(index, millis) }
        }
    }
}

/**
 * The bookkeeping behind [TrackPageViews], apart from Compose so it can be tested: [show] is
 * told which page is on screen now (null for none) and returns the page that was on screen
 * until now with how long it was, or null when nothing was.
 */
internal class PageViewMeter(private val elapsedMillis: () -> Long) {
    private var page: Int? = null
    private var since = 0L

    fun show(next: Int?): Pair<Int, Long>? {
        val now = elapsedMillis()
        val ended = page?.let { it to (now - since).coerceAtLeast(0) }
        page = next
        since = now
        return ended?.takeIf { it.second > 0 }
    }
}
