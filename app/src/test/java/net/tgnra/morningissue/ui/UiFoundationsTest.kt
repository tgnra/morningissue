package net.tgnra.morningissue.ui

import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import net.tgnra.morningissue.model.DisplayMode
import net.tgnra.morningissue.ui.layout.NewsstandArrangement
import net.tgnra.morningissue.ui.layout.PaperLayoutSpec
import net.tgnra.morningissue.ui.theme.EinkDevices
import net.tgnra.morningissue.ui.theme.EinkPaperColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.Color

class UiFoundationsTest {
    @Test
    fun layoutFollowsThePlansTabletTable() {
        val phone = PaperLayoutSpec.from(WindowWidthSizeClass.Compact, WindowHeightSizeClass.Medium)
        val smallTablet = PaperLayoutSpec.from(WindowWidthSizeClass.Medium, WindowHeightSizeClass.Expanded)
        val phoneLandscape = PaperLayoutSpec.from(WindowWidthSizeClass.Expanded, WindowHeightSizeClass.Compact)
        val tabletLandscape = PaperLayoutSpec.from(WindowWidthSizeClass.Expanded, WindowHeightSizeClass.Medium)

        assertEquals(listOf(2, 3, 3, 4), listOf(phone, smallTablet, phoneLandscape, tabletLandscape).map { it.columns })
        assertEquals(NewsstandArrangement.SingleShelf, phone.newsstand)
        assertEquals(NewsstandArrangement.TwoShelves, smallTablet.newsstand)
        assertEquals(NewsstandArrangement.CoverWithShelfGrid, tabletLandscape.newsstand)
        assertTrue(tabletLandscape.useButtonRail && tabletLandscape.readerSidePanel)
        assertFalse(phoneLandscape.useButtonRail)
    }

    @Test
    fun einkDetection() {
        assertTrue(EinkDevices.isEinkMaker("ONYX", "Onyx"))
        assertTrue(EinkDevices.isEinkMaker("unknown", "BOOX"))
        assertFalse(EinkDevices.isEinkMaker("Google", "google"))
        assertFalse(EinkDevices.isEinkMaker(null, null))

        assertEquals(DisplayMode.Eink, EinkDevices.resolve(DisplayMode.Auto, deviceIsEink = true))
        assertEquals(DisplayMode.Standard, EinkDevices.resolve(DisplayMode.Auto, deviceIsEink = false))
        // The manual setting always wins.
        assertEquals(DisplayMode.Standard, EinkDevices.resolve(DisplayMode.Standard, deviceIsEink = true))
        assertEquals(DisplayMode.Eink, EinkDevices.resolve(DisplayMode.Eink, deviceIsEink = false))
    }

    @Test
    fun einkPaletteIsOnlyBlackAndWhite() {
        val c = EinkPaperColors
        val used = listOf(c.paper, c.paperRaised, c.ink, c.inkSecondary, c.rule, c.hairline, c.accent, c.onAccent, c.stand, c.shelf, c.shelfEdge)
        assertTrue(used.all { it == Color.Black || it == Color.White })
        assertEquals(Color.Transparent, c.shadow)
    }
}
