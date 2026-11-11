package net.tgnra.morningissue.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import net.tgnra.morningissue.ui.reader.ImageZoom
import org.junit.Assert.assertEquals
import org.junit.Test

/** The full-screen picture's arithmetic. */
class ImageZoomTest {
    private val screen = Size(1000f, 2000f)

    /** A 4:3 picture on a tall screen: full width, 750 high. */
    private val fitted = ImageZoom.fitted(screen, 4f / 3f)

    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.01f)
        assertEquals(expected.y, actual.y, 0.01f)
    }

    @Test
    fun aPictureIsFittedInsideTheScreenByItsShape() {
        assertEquals(Size(1000f, 750f), fitted)
        assertEquals(Size(500f, 2000f), ImageZoom.fitted(screen, 0.25f))
        // Until the shape is known the whole screen stands in for it.
        assertEquals(screen, ImageZoom.fitted(screen, null))
        assertEquals(Size.Zero, ImageZoom.fitted(Size.Zero, 1.5f))
    }

    @Test
    fun thePictureCannotBeDraggedOffTheScreen() {
        // Fitted: nowhere to go.
        assertOffset(Offset.Zero, ImageZoom.clamp(Offset(300f, -400f), 1f, screen, fitted))
        // Twice the size: 2000 wide on a 1000 screen moves 500 either way; 1500 high still fits, so it stays centred.
        assertOffset(Offset(500f, 0f), ImageZoom.clamp(Offset(900f, 900f), 2f, screen, fitted))
        assertOffset(Offset(-500f, 0f), ImageZoom.clamp(Offset(-900f, -900f), 2f, screen, fitted))
        // Four times: 3000 high moves 500 up or down.
        assertOffset(Offset(1500f, -500f), ImageZoom.clamp(Offset(5000f, -5000f), 4f, screen, fitted))
    }

    @Test
    fun aPinchKeepsThePointUnderTheFingersWhereItIs() {
        // Fingers 200 px right of centre, vertically centred. The picture point under them is
        // 200 right of the picture's centre; after doubling it is 400 right, so the picture moves 200 left.
        val centroid = Offset(700f, 1000f)
        val (scale, offset) = ImageZoom.transform(1f, Offset.Zero, centroid, Offset.Zero, 2f, screen, fitted)
        assertEquals(2f, scale, 0.001f)
        assertOffset(Offset(-200f, 0f), offset)
        // The point's place on screen: centre + offset + (point × scale) = 500 − 200 + 400.
        assertEquals(centroid.x, 500f + offset.x + 200f * scale, 0.01f)

        // Dragging with one finger moves it, within bounds.
        val (same, dragged) = ImageZoom.transform(2f, offset, centroid, Offset(150f, 0f), 1f, screen, fitted)
        assertEquals(2f, same, 0.001f)
        assertOffset(Offset(-50f, 0f), dragged)
    }

    @Test
    fun theScaleStaysBetweenFittedAndTheLimit() {
        assertEquals(ImageZoom.MAX_SCALE, ImageZoom.transform(4f, Offset.Zero, Offset(500f, 1000f), Offset.Zero, 3f, screen, fitted).first, 0.001f)
        val (scale, offset) = ImageZoom.transform(1.2f, Offset(80f, 0f), Offset(500f, 1000f), Offset.Zero, 0.5f, screen, fitted)
        assertEquals(1f, scale, 0.001f)
        // Back at fitted size the picture is centred again.
        assertOffset(Offset.Zero, offset)
    }

    @Test
    fun einkStepsThroughFixedSizesAndPansByMostOfAScreen() {
        assertEquals(2f, ImageZoom.einkStep(1f, up = true), 0f)
        assertEquals(4f, ImageZoom.einkStep(2f, up = true), 0f)
        assertEquals(4f, ImageZoom.einkStep(4f, up = true), 0f)
        assertEquals(2f, ImageZoom.einkStep(4f, up = false), 0f)
        assertEquals(1f, ImageZoom.einkStep(1f, up = false), 0f)

        // At 4×: 4000 × 3000 on a 1000 × 2000 screen. A swipe to the left shows what was to the right.
        val left = ImageZoom.einkPan(Offset.Zero, Offset(-120f, 30f), 4f, screen, fitted)
        assertOffset(Offset(-800f, 0f), left)
        // The next swipe stops at the picture's edge.
        assertOffset(Offset(-1500f, 0f), ImageZoom.einkPan(ImageZoom.einkPan(left, Offset(-90f, 0f), 4f, screen, fitted), Offset(-90f, 0f), 4f, screen, fitted))
        // A mostly vertical swipe moves it up or down, by the height's share.
        assertOffset(Offset(0f, 500f), ImageZoom.einkPan(Offset.Zero, Offset(10f, 200f), 4f, screen, fitted))
    }
}
