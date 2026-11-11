package net.tgnra.morningissue.ui

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.SearchQuery
import net.tgnra.morningissue.ui.paper.PageTurnMath
import net.tgnra.morningissue.ui.paper.PageTurnTransform
import net.tgnra.morningissue.ui.reader.CodeFit
import net.tgnra.morningissue.ui.search.SearchFilters
import net.tgnra.morningissue.ui.search.SearchPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolishLogicTest {
    // --- Page turn --------------------------------------------------------------------------

    /** A phone page in portrait: its width over the distance of the camera the app would use. */
    private val camera = 1080f / PageTurnMath.cameraDistance(1080f, 2100f)

    @Test
    fun aSettledPageAndPagesAWholeTurnAwayAreLeftAlone() {
        // Flat in view: an identity layer, so text is drawn exactly as without the turn.
        assertEquals(PageTurnTransform.Flat, PageTurnMath.transform(0f, camera))
        // Neighbours at rest stay where the pager put them (off screen), not pinned over the page.
        assertEquals(PageTurnTransform.Flat, PageTurnMath.transform(1f, camera))
        assertEquals(PageTurnTransform.Flat, PageTurnMath.transform(-1f, camera))
        assertEquals(PageTurnTransform.Flat, PageTurnMath.transform(3.4f, camera))
        assertEquals(PageTurnTransform.Flat, PageTurnMath.transform(-2.5f, camera))
        assertEquals(PageTurnTransform.Flat, PageTurnMath.transform(Float.NaN, camera))
    }

    @Test
    fun theTurningSheetStaysPutAndSwingsOnItsLeftEdge() {
        val turn = PageTurnMath.transform(0.5f, camera)
        // The pager slid it half a width to the left; the transform puts it back.
        assertEquals(0.5f, turn.translationX, 0f)
        assertEquals(0f, turn.pivotX, 0f)
        assertTrue("lifts towards the reader", turn.rotationY < 0f && turn.rotationY > -90f)
        assertTrue(turn.shade > 0f && turn.shade <= PageTurnMath.MAX_TURNING_SHADE)
        assertEquals(1f, turn.edge, 0f)
    }

    @Test
    fun theTurnNeverPassesEdgeOnSoTheBackOfASheetIsNeverSeen() {
        var last = 0f
        for (step in 0..1000) {
            val angle = PageTurnMath.turnAngle(step / 1000f, camera)
            assertTrue("angle $angle at $step", angle in 0f..90f)
            assertTrue("monotonic at $step", angle >= last - 1e-3f)
            last = angle
        }
        assertEquals(0f, PageTurnMath.turnAngle(0f, camera), 0.01f)
        assertEquals(90f, PageTurnMath.turnAngle(1f, camera), 0.01f)
        // Out-of-range progress is clamped rather than turned past the spine.
        assertEquals(90f, PageTurnMath.turnAngle(1.7f, camera), 0.01f)
        assertEquals(0f, PageTurnMath.turnAngle(-0.3f, camera), 0.01f)
    }

    @Test
    fun theFreeEdgeIsSeenUnderTheFinger() {
        // Once it has lifted off, a sheet dragged p of the way across has its edge drawn at
        // 1 - p of the width, with perspective taken into account, for near and far cameras.
        for (k in listOf(0f, 0.2f, camera, 0.8f)) {
            for (step in 5..19) {
                val p = step / 20f
                val seen = PageTurnMath.projectedEdge(PageTurnMath.turnAngle(p, k), k)
                assertEquals("progress $p camera $k", 1f - p, seen, 0.005f)
            }
        }
    }

    @Test
    fun whileLiftingOffTheEdgeIsAtOrPastThePageEdgeNeverBehindTheFinger() {
        for (step in 1..24) {
            val p = step / 100f
            val seen = PageTurnMath.projectedEdge(PageTurnMath.turnAngle(p, camera), camera)
            assertTrue("progress $p edge $seen", seen >= 1f - p - 0.005f)
        }
    }

    @Test
    fun theSheetBeneathLiesFlatInPlaceAndClearsAsItIsUncovered() {
        val covered = PageTurnMath.transform(-0.9f, camera)
        val nearlyClear = PageTurnMath.transform(-0.1f, camera)
        assertEquals(0f, covered.rotationY, 0f)
        assertEquals(-0.9f, covered.translationX, 0f)
        assertEquals(0f, covered.edge, 0f)
        assertTrue(covered.shade > nearlyClear.shade && nearlyClear.shade > 0f)
        assertTrue(covered.shade <= PageTurnMath.MAX_UNDER_SHADE)
    }

    @Test
    fun turningBackIsTheSameMotionInReverse() {
        // Going back, the earlier page's progress runs 1 -> 0 and the current page's 0 -> -1:
        // the transform depends on the progress alone, so it is the forward turn met in the
        // opposite order. The shade grows with the turn, whichever way the sheet is moving.
        for (step in 1..9) {
            val p = step / 10f
            assertEquals(PageTurnMath.MAX_TURNING_SHADE * p, PageTurnMath.transform(p, camera).shade, 1e-6f)
            assertEquals(PageTurnMath.shade(p), PageTurnMath.transform(p, camera).shade, 0f)
            assertEquals(PageTurnMath.edge(p), PageTurnMath.transform(p, camera).edge, 0f)
            assertEquals(-PageTurnMath.turnAngle(p, camera), PageTurnMath.transform(p, camera).rotationY, 0f)
        }
        // And it is continuous at both ends: no jump when a turn starts or finishes.
        assertEquals(0f, PageTurnMath.transform(0.001f, camera).rotationY, 0.5f)
        assertEquals(-90f, PageTurnMath.transform(0.9999f, camera).rotationY, 1.5f)
        assertEquals(0f, PageTurnMath.transform(-0.001f, camera).shade, 0.001f)
    }

    @Test
    fun rightToLeftMirrorsTheTurn() {
        val ltr = PageTurnMath.transform(0.4f, camera)
        val rtl = PageTurnMath.transform(0.4f, camera, rtl = true)
        assertEquals(1f, rtl.pivotX, 0f)
        assertEquals(-ltr.rotationY, rtl.rotationY, 0f)
        assertEquals(-ltr.translationX, rtl.translationX, 0f)
        assertEquals(ltr.shade, rtl.shade, 0f)
        assertEquals(0.3f, PageTurnMath.transform(-0.3f, camera, rtl = true).translationX, 0f)
    }

    // --- Search filters ---------------------------------------------------------------------

    private val now: Instant = Instant.parse("2026-11-10T08:00:00Z")

    @Test
    fun noFiltersSearchEverything() {
        assertFalse(SearchFilters.None.isActive)
        assertEquals(0, SearchFilters.None.activeCount)
        assertEquals(SearchQuery(text = "paper"), SearchFilters.None.toQuery("  paper ", now))
    }

    @Test
    fun everyFilterReachesTheQuery() {
        val filters = SearchFilters(tag = "Tech", feedId = 7, period = SearchPeriod.PastWeek, savedOnly = true, likedOnly = true)
        assertEquals(5, filters.activeCount)
        assertEquals(
            SearchQuery(
                text = "kotlin",
                tag = "Tech",
                feedId = 7,
                from = now.minus(Duration.ofDays(7)),
                to = null,
                likedOnly = true,
                savedOnly = true,
            ),
            filters.toQuery("kotlin", now),
        )
    }

    @Test
    fun aPeriodRunsBackFromNowWithNoUpperBound() {
        assertNull(SearchFilters(period = SearchPeriod.AnyTime).toQuery("a", now).from)
        assertEquals(now.minus(Duration.ofDays(30)), SearchFilters(period = SearchPeriod.PastMonth).toQuery("a", now).from)
        assertEquals(now.minus(Duration.ofDays(365)), SearchFilters(period = SearchPeriod.PastYear).toQuery("a", now).from)
        SearchPeriod.entries.forEach { assertNull(SearchFilters(period = it).toQuery("a", now).to) }
        assertEquals(1, SearchFilters(period = SearchPeriod.PastYear).activeCount)
    }

    @Test
    fun filtersOnThingsThatNoLongerExistAreDropped() {
        val filters = SearchFilters(tag = "tech", feedId = 7, savedOnly = true)
        // The tag is matched without regard to case and takes the stored spelling.
        assertEquals(filters.copy(tag = "Tech"), filters.validFor(listOf("Design", "Tech"), listOf(3L, 7L)))
        assertEquals(SearchFilters(savedOnly = true), filters.validFor(listOf("Design"), listOf(3L)))
        assertNull(SearchFilters(tag = " ").toQuery("a", now).tag)
    }

    // --- E-ink code blocks ------------------------------------------------------------------

    @Test
    fun codeIsShrunkOnlyAsFarAsItTakesToFit() {
        assertEquals(1f, CodeFit.scale(longestLinePx = 400, availablePx = 600), 0f)
        assertEquals(1f, CodeFit.scale(600, 600), 0f)
        val fitted = CodeFit.scale(700, 600)
        assertTrue(fitted < 1f && 700 * fitted <= 600f)
        assertEquals(600f / 700f, fitted, 0.01f)
        // Very long lines stop at the floor and wrap.
        assertEquals(CodeFit.MIN_SCALE, CodeFit.scale(3000, 600), 0f)
        assertEquals(1f, CodeFit.scale(0, 600), 0f)
        assertEquals(1f, CodeFit.scale(500, 0), 0f)
    }
}
