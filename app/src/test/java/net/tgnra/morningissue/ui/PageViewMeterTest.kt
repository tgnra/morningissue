package net.tgnra.morningissue.ui

import net.tgnra.morningissue.ui.paper.PageViewMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The bookkeeping behind the paper screen's page views. */
class PageViewMeterTest {
    private var now = 0L
    private val meter = PageViewMeter { now }

    @Test
    fun eachPageIsTimedFromWhenItCameUpToWhenItWentAway() {
        assertNull(meter.show(0))
        now = 6_000
        assertEquals(0 to 6_000L, meter.show(1))
        now = 7_500
        // The screen goes off (or the paper closes): the page's time ends there.
        assertEquals(1 to 1_500L, meter.show(null))
        now = 60_000
        // Nothing was on screen in between, so nothing is counted for it.
        assertNull(meter.show(1))
        now = 64_000
        assertEquals(1 to 4_000L, meter.show(null))
    }

    @Test
    fun noTimeIsNoView() {
        now = 100
        meter.show(2)
        assertNull(meter.show(3))
        // A clock that goes backwards never gives negative time.
        now = 50
        assertNull(meter.show(null))
    }
}
