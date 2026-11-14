package net.tgnra.morningissue.ui

import androidx.compose.ui.graphics.Color
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import net.tgnra.morningissue.model.InlineSpan
import net.tgnra.morningissue.model.InlineStyle
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.edition.BuildStage
import net.tgnra.morningissue.sync.SyncStatus
import net.tgnra.morningissue.ui.components.ImageTreatment
import net.tgnra.morningissue.ui.components.StepScroll
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.format.PostRate
import net.tgnra.morningissue.ui.layout.PaperLayoutSpec
import net.tgnra.morningissue.ui.newsstand.printProgress
import net.tgnra.morningissue.ui.paper.frontGridColumns
import net.tgnra.morningissue.ui.reader.ReadRule
import net.tgnra.morningissue.ui.reader.ReaderTextSize
import net.tgnra.morningissue.ui.reader.toAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest {
    // --- E-ink step scrolling ---------------------------------------------------------------

    @Test
    fun stepForwardSnapsBackToTheLastAnchorThatFits() {
        // Viewport 1000, overlap 40: the raw step lands at 960; the row starting at 900 would be cut.
        val anchors = intArrayOf(0, 300, 600, 900, 1200, 1500)
        assertEquals(900, StepScroll.forward(current = 0, viewport = 1000, max = 5000, anchors = anchors, overlap = 40))
        assertEquals(1500, StepScroll.forward(current = 900, viewport = 1000, max = 5000, anchors = anchors, overlap = 40))
    }

    @Test
    fun stepForwardIgnoresAnchorsThatWouldMoveLessThanHalfAScreen() {
        // A tall picture: the only anchor in reach is 100 px on, which would barely move.
        assertEquals(960, StepScroll.forward(0, 1000, 5000, intArrayOf(0, 100, 2400), 40))
        // No anchors at all: a plain screen less the overlap.
        assertEquals(960, StepScroll.forward(0, 1000, 5000, IntArray(0), 40))
    }

    @Test
    fun stepForwardStopsAtTheEnd() {
        assertEquals(1200, StepScroll.forward(600, 1000, 1200, intArrayOf(700, 1100), 40))
        assertEquals(1200, StepScroll.forward(1200, 1000, 1200, IntArray(0), 40))
        assertEquals(0, StepScroll.forward(0, 1000, 0, IntArray(0), 40))
        assertEquals(50, StepScroll.forward(50, 0, 500, IntArray(0), 40))
    }

    @Test
    fun stepBackwardMirrorsForward() {
        val anchors = intArrayOf(0, 300, 600, 900, 1200, 1500)
        // Raw step from 1500 is 540; the first anchor at or after it is 600.
        assertEquals(600, StepScroll.backward(1500, 1000, anchors, 40))
        assertEquals(0, StepScroll.backward(600, 1000, anchors, 40))
        assertEquals(0, StepScroll.backward(0, 1000, anchors, 40))
        assertEquals(1040, StepScroll.backward(2000, 1000, IntArray(0), 40))
    }

    @Test
    fun steppingForwardThenBackNeverSkipsContent() {
        val anchors = IntArray(60) { it * 137 }
        var position = 0
        val max = 8000
        while (position < max) {
            val next = StepScroll.forward(position, 1000, max, anchors, 40)
            assertTrue("a step must move", next > position)
            assertTrue("a step may not skip past what was on screen", next <= position + 1000)
            position = next
        }
        assertEquals(max, position)
    }

    // --- Read rule --------------------------------------------------------------------------

    @Test
    fun readByScrollDepthOrDwell() {
        assertFalse(ReadRule.isRead(scrollFraction = 0.69f, dwellMillis = 0, readMinutes = 4))
        assertTrue(ReadRule.isRead(scrollFraction = 0.7f, dwellMillis = 0, readMinutes = 4))
        // Half of four minutes.
        assertEquals(120_000L, ReadRule.dwellThresholdMillis(4))
        assertFalse(ReadRule.isRead(0f, 119_000, 4))
        assertTrue(ReadRule.isRead(0f, 120_000, 4))
        // A zero estimate still needs half a minute.
        assertEquals(30_000L, ReadRule.dwellThresholdMillis(0))
    }

    @Test
    fun scrollFractionAndReporting() {
        assertEquals(0f, ReadRule.scrollFraction(0, 0))
        assertEquals(0.5f, ReadRule.scrollFraction(500, 1000))
        assertEquals(1f, ReadRule.scrollFraction(1500, 1000))
        assertFalse(ReadRule.shouldReport(lastReported = 0.5f, fraction = 0.52f))
        assertTrue(ReadRule.shouldReport(0.5f, 0.56f))
        assertTrue("the end is always stored", ReadRule.shouldReport(0.98f, 1f))
        assertFalse("never goes backwards", ReadRule.shouldReport(0.5f, 0.2f))
        assertFalse(ReadRule.shouldReport(1f, 1f))
    }

    @Test
    fun textSizeStepsAreClamped() {
        assertEquals(0, ReaderTextSize.clamp(-3))
        assertEquals(ReaderTextSize.steps.lastIndex, ReaderTextSize.clamp(99))
        assertEquals(1f, ReaderTextSize.steps[ReaderTextSize.DEFAULT_STEP])
    }

    // --- Formatting -------------------------------------------------------------------------

    @Test
    fun coverAndListDates() {
        val date = LocalDate.of(2026, 11, 10)
        assertEquals("Tuesday 10 November 2026", PaperFormat.longDate(date, Locale.UK))
        assertEquals("Tue 10 Nov", PaperFormat.shortDate(date, Locale.UK))
        assertEquals("November 2026", PaperFormat.monthTitle(YearMonth.of(2026, 11), Locale.UK))
        assertEquals(
            "3 Mar 2024",
            PaperFormat.articleDate(Instant.parse("2024-03-03T12:00:00Z"), ZoneId.of("UTC"), Locale.UK),
        )
    }

    @Test
    fun timesFollowTheClockSetting() {
        assertEquals("6:30 am", PaperFormat.time(LocalTime.of(6, 30), use24Hour = false, locale = Locale.UK))
        assertEquals("06:30", PaperFormat.time(LocalTime.of(6, 30), use24Hour = true, locale = Locale.UK))
        assertEquals("15:05", PaperFormat.time(LocalTime.of(15, 5), use24Hour = true, locale = Locale.UK))
    }

    @Test
    fun nextResetIsTodayOrTomorrow() {
        val zone = ZoneId.of("UTC")
        val reset = LocalTime.of(6, 30)
        assertEquals(
            Instant.parse("2026-11-10T06:30:00Z"),
            PaperFormat.nextReset(Instant.parse("2026-11-10T05:00:00Z"), reset, zone),
        )
        assertEquals(
            Instant.parse("2026-11-11T06:30:00Z"),
            PaperFormat.nextReset(Instant.parse("2026-11-10T06:30:00Z"), reset, zone),
        )
    }

    @Test
    fun monthGridPadsToWholeWeeks() {
        // November 2026 starts on a Sunday.
        val mondayFirst = PaperFormat.monthGrid(YearMonth.of(2026, 11), DayOfWeek.MONDAY)
        assertTrue(mondayFirst.all { it.size == 7 })
        assertEquals(6, mondayFirst.first().count { it == null })
        assertEquals(LocalDate.of(2026, 11, 1), mondayFirst.first().last())
        assertEquals(30, mondayFirst.flatten().count { it != null })

        val sundayFirst = PaperFormat.monthGrid(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 1), sundayFirst.first().first())
        assertEquals(5, sundayFirst.size)
        assertEquals(DayOfWeek.SATURDAY, PaperFormat.weekDays(DayOfWeek.SUNDAY).last())
    }

    @Test
    fun postRatePicksTheUnitThatReadsBest() {
        assertNull(PostRate.of(null))
        assertNull(PostRate.of(0.0))
        assertEquals(PostRate.PerWeek(24), PostRate.of(168.0 / 7.0))
        assertEquals(PostRate.PerWeek(1), PostRate.of(1.0))
        assertEquals(PostRate.PerMonth(2), PostRate.of(0.5))
        assertEquals(PostRate.LessThanMonthly, PostRate.of(168.0 / 900.0))
    }

    @Test
    fun printNowProgressIsOneBarThatOnlyMovesForward() {
        val idle = SyncStatus.Idle
        val steps = listOf(
            printProgress(BuildStage.Syncing, idle),
            printProgress(BuildStage.Syncing, SyncStatus.Running(0, 40)),
            printProgress(BuildStage.Syncing, SyncStatus.Running(10, 40)),
            printProgress(BuildStage.Syncing, SyncStatus.Running(40, 40)),
            printProgress(BuildStage.Building, idle),
            printProgress(BuildStage.FetchingText(1, 0, 20), idle),
            printProgress(BuildStage.FetchingText(1, 10, 20), idle),
            printProgress(BuildStage.FetchingText(1, 20, 20), idle),
            printProgress(BuildStage.FetchingImages(1, 12), idle),
        )
        assertEquals(steps.sorted(), steps)
        assertEquals(0f, steps.first())
        assertEquals(0.125f, steps[2])
        assertEquals("the sync is the first half", 0.5f, steps[3])
        assertTrue(steps.last() < 1f)
        // No feeds and no stories do not divide by zero.
        assertEquals(0f, printProgress(BuildStage.Syncing, SyncStatus.Running(0, 0)))
        assertEquals(0.9f, printProgress(BuildStage.FetchingText(1, 0, 0), idle), 0.0001f)
    }

    // --- Layout and rendering helpers --------------------------------------------------------

    @Test
    fun frontPageSecondariesFillWholeRows() {
        assertEquals(2, frontGridColumns(4, PaperLayoutSpec.Compact))
        // Four across a small tablet rather than three and an orphan.
        assertEquals(4, frontGridColumns(4, PaperLayoutSpec.Medium))
        assertEquals(3, frontGridColumns(6, PaperLayoutSpec.Medium))
        // Beside a lead that spans two of four columns.
        assertEquals(2, frontGridColumns(4, PaperLayoutSpec.Expanded))
    }

    @Test
    fun einkMatrixIsGreyscaleWithContrastAroundMidGrey() {
        val matrix = ImageTreatment.einkMatrix(contrast = 1.5f)
        // Every colour row is the same: the result is grey.
        assertEquals(matrix.slice(0..4), matrix.slice(5..9))
        assertEquals(matrix.slice(0..4), matrix.slice(10..14))
        fun out(grey: Float) = (matrix[0] + matrix[1] + matrix[2]) * grey + matrix[4]
        assertEquals(127.5f, out(127.5f), 0.01f)
        assertTrue("light gets lighter", out(200f) > 200f)
        assertTrue("dark gets darker", out(50f) < 50f)
        // Alpha is untouched.
        assertEquals(listOf(0f, 0f, 0f, 1f, 0f), matrix.slice(15..19))
    }

    @Test
    fun richTextSpansAreClampedNotFatal() {
        val text = RichText(
            text = "Short text",
            spans = listOf(
                InlineSpan(0, 5, InlineStyle.BOLD),
                InlineSpan(6, 400, InlineStyle.LINK, href = "https://example.org"),
                InlineSpan(50, 60, InlineStyle.ITALIC),
                InlineSpan(4, 2, InlineStyle.CODE),
            ),
        )
        val styled = text.toAnnotatedString(linkColor = Color.Red)
        assertEquals("Short text", styled.text)
        assertEquals(1, styled.spanStyles.size)
        val link = styled.getLinkAnnotations(0, styled.length).single()
        assertEquals(6, link.start)
        assertEquals(10, link.end)
    }
}
