package net.tgnra.morningissue.ui

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.ui.components.StepScroll
import net.tgnra.morningissue.ui.learning.ImageFrame
import net.tgnra.morningissue.ui.learning.Mutes
import net.tgnra.morningissue.ui.learning.SliderSteps
import net.tgnra.morningissue.ui.learning.SourceFilter
import net.tgnra.morningissue.ui.learning.StoryReason
import net.tgnra.morningissue.ui.learning.TagInput
import net.tgnra.morningissue.ui.newsstand.StandEdition
import net.tgnra.morningissue.ui.newsstand.StandEditions
import net.tgnra.morningissue.ui.paper.gridRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningLogicTest {
    private val now: Instant = Instant.parse("2026-11-10T08:00:00Z")

    private fun feed(id: Long, tags: List<String> = emptyList(), muteUntil: Instant? = null) = Feed(
        id = id,
        url = "https://example.org/$id/feed.xml",
        siteUrl = null,
        title = "Feed $id",
        iconUrl = null,
        tier = CadenceTier.WEEKLY,
        medianIntervalHours = null,
        burstiness = null,
        affinity = 0.5,
        sliderStep = SliderStep.AUTO,
        tags = tags,
        notify = false,
        muteUntil = muteUntil,
        lastSyncAt = null,
        addedAt = now,
        healthFlag = null,
    )

    // --- The slider ---------------------------------------------------------------------------

    @Test
    fun showMoreAndShowLessMoveOneStepAndStopAtTheEnds() {
        assertEquals(SliderStep.MORE, SliderStep.AUTO.stepUp())
        assertEquals(SliderStep.LESS, SliderStep.AUTO.stepDown())
        assertEquals(SliderStep.FAVOURITE, SliderStep.FAVOURITE.stepUp())
        assertEquals(SliderStep.RARELY, SliderStep.RARELY.stepDown())
        assertFalse(SliderSteps.canShowMore(SliderStep.FAVOURITE))
        assertFalse(SliderSteps.canShowLess(SliderStep.RARELY))
        assertTrue(SliderSteps.canShowMore(SliderStep.MORE))
        assertTrue(SliderSteps.canShowLess(SliderStep.LESS))
    }

    @Test
    fun fiveShowMoresFromRarelyReachFavouriteAndStay() {
        var step = SliderStep.RARELY
        repeat(7) { step = step.stepUp() }
        assertEquals(SliderStep.FAVOURITE, step)
    }

    @Test
    fun sliderIndexIsClampedToTheTrack() {
        assertEquals(SliderStep.RARELY, SliderSteps.fromIndex(-3))
        assertEquals(SliderStep.AUTO, SliderSteps.fromIndex(2))
        assertEquals(SliderStep.FAVOURITE, SliderSteps.fromIndex(99))
    }

    @Test
    fun aTouchSelectsTheStopWhoseCellItFallsIn() {
        // Five cells of 100 px each.
        assertEquals(SliderStep.RARELY, SliderSteps.stepAt(0f, 500f))
        assertEquals(SliderStep.RARELY, SliderSteps.stepAt(99f, 500f))
        assertEquals(SliderStep.LESS, SliderSteps.stepAt(100f, 500f))
        assertEquals(SliderStep.AUTO, SliderSteps.stepAt(250f, 500f))
        assertEquals(SliderStep.FAVOURITE, SliderSteps.stepAt(499f, 500f))
    }

    @Test
    fun aDragBeyondEitherEndTakesTheEndStop() {
        assertEquals(SliderStep.RARELY, SliderSteps.stepAt(-40f, 500f))
        assertEquals(SliderStep.FAVOURITE, SliderSteps.stepAt(900f, 500f))
        assertEquals(0, SliderSteps.indexAt(10f, 0f))
    }

    @Test
    fun theTrackIsMirroredRightToLeft() {
        assertEquals(SliderStep.FAVOURITE, SliderSteps.stepAt(10f, 500f, rtl = true))
        assertEquals(SliderStep.AUTO, SliderSteps.stepAt(250f, 500f, rtl = true))
        assertEquals(SliderStep.RARELY, SliderSteps.stepAt(490f, 500f, rtl = true))
    }

    // --- Mutes --------------------------------------------------------------------------------

    @Test
    fun muteRunsForWholeDaysFromNow() {
        assertEquals(listOf(1, 3, 7, 30), Mutes.DAYS)
        assertEquals(Instant.parse("2026-11-11T08:00:00Z"), Mutes.until(now, 1))
        assertEquals(Instant.parse("2026-11-17T08:00:00Z"), Mutes.until(now, 7))
        assertEquals(Instant.parse("2026-12-10T08:00:00Z"), Mutes.until(now, 30))
        // Nonsense never produces a mute that has already ended.
        assertEquals(Instant.parse("2026-11-11T08:00:00Z"), Mutes.until(now, 0))
    }

    @Test
    fun aLapsedMuteIsNoMute() {
        assertNull(Mutes.activeUntil(null, now))
        assertNull(Mutes.activeUntil(now, now))
        assertNull(Mutes.activeUntil(now.minusSeconds(1), now))
        assertEquals(now.plusSeconds(1), Mutes.activeUntil(now.plusSeconds(1), now))
    }

    @Test
    fun daysLeftRoundsUp() {
        assertEquals(0, Mutes.daysLeft(null, now))
        assertEquals(0, Mutes.daysLeft(now.minusSeconds(5), now))
        assertEquals(1, Mutes.daysLeft(now.plusSeconds(60), now))
        assertEquals(3, Mutes.daysLeft(Mutes.until(now, 3), now))
        assertEquals(3, Mutes.daysLeft(now.plus(Duration.ofHours(49)), now))
    }

    // --- Tags ---------------------------------------------------------------------------------

    @Test
    fun tagNamesAreTidied() {
        assertEquals("Tech", TagInput.normalize("  Tech "))
        assertEquals("Science fiction", TagInput.normalize("Science \t  fiction"))
        assertEquals("personal", TagInput.normalize("#personal"))
        assertNull(TagInput.normalize("   "))
        assertNull(TagInput.normalize("#"))
        assertEquals(TagInput.MAX_LENGTH, TagInput.normalize("x".repeat(80))!!.length)
    }

    @Test
    fun severalTagsCanBeTypedAtOnce() {
        assertEquals(listOf("Tech", "Long reads"), TagInput.parse("Tech, Long  reads,, tech ,"))
        assertEquals(emptyList<String>(), TagInput.parse(" , ,"))
    }

    @Test
    fun addingATagKeepsTheKnownSpellingAndSkipsRepeats() {
        val known = listOf("Culture", "Tech")
        assertEquals(listOf("Personal", "Tech"), TagInput.add(listOf("Personal"), "tech", known))
        assertEquals(listOf("Personal"), TagInput.add(listOf("Personal"), "PERSONAL", known))
        assertEquals(listOf("gardening", "Personal"), TagInput.add(listOf("Personal"), "gardening", known))
        assertEquals(listOf("Personal"), TagInput.add(listOf("Personal"), "  ", known))
    }

    @Test
    fun removingATagIgnoresCase() {
        assertEquals(listOf("Tech"), TagInput.remove(listOf("Personal", "Tech"), "personal"))
        assertEquals(listOf("Tech"), TagInput.remove(listOf("Tech"), "Science"))
    }

    @Test
    fun suggestionsAreTheOtherTagsNarrowedByWhatIsTyped() {
        val known = listOf("Culture", "Personal", "Science", "Tech")
        assertEquals(listOf("Culture", "Science"), TagInput.suggestions(known, listOf("personal", "Tech")))
        assertEquals(listOf("Science"), TagInput.suggestions(known, listOf("Tech"), "sci"))
        assertEquals(listOf("Culture", "Science"), TagInput.suggestions(known, emptyList(), "c").filter { it != "Tech" })
        assertEquals(emptyList<String>(), TagInput.suggestions(known, emptyList(), "zzz"))
    }

    // --- Filtering the Sources list -----------------------------------------------------------

    @Test
    fun sourcesAreFilteredByTagUntaggedAndMuted() {
        val tech = feed(1, listOf("Tech"))
        val both = feed(2, listOf("Personal", "tech"))
        val none = feed(3)
        val muted = feed(4, listOf("Culture"), muteUntil = now.plusSeconds(3600))
        val lapsed = feed(5, muteUntil = now.minusSeconds(3600))
        val all = listOf(tech, both, none, muted, lapsed)

        assertEquals(all, all.filter { SourceFilter.All.matches(it, now) })
        assertEquals(listOf(tech, both), all.filter { SourceFilter.Tagged("Tech").matches(it, now) })
        assertEquals(listOf(none, lapsed), all.filter { SourceFilter.Untagged.matches(it, now) })
        assertEquals(listOf(muted), all.filter { SourceFilter.Muted.matches(it, now) })
    }

    @Test
    fun aFilterSurvivesSavedStateAndFallsBackWhenItMatchesNothing() {
        listOf(SourceFilter.All, SourceFilter.Tagged("Long reads"), SourceFilter.Untagged, SourceFilter.Muted).forEach {
            assertEquals(it, SourceFilter.decode(it.encode()))
        }
        assertEquals(SourceFilter.All, SourceFilter.decode(null))
        assertEquals(SourceFilter.All, SourceFilter.decode("nonsense"))

        val feeds = listOf(feed(1, listOf("Tech")))
        assertEquals(SourceFilter.Tagged("Tech"), SourceFilter.valid(SourceFilter.Tagged("Tech"), feeds, now))
        assertEquals(SourceFilter.All, SourceFilter.valid(SourceFilter.Tagged("Culture"), feeds, now))
        assertEquals(SourceFilter.All, SourceFilter.valid(SourceFilter.Muted, feeds, now))
    }

    // --- Why is this here? --------------------------------------------------------------------

    @Test
    fun everyKindOfStoryHasItsOwnFallbackReason() {
        val reasons = ItemKind.entries.map { StoryReason.fallback(it) }
        assertEquals(ItemKind.entries.size, reasons.toSet().size)
    }

    // --- Page furniture -----------------------------------------------------------------------

    @Test
    fun gridRowsAreFullRowsThenTheRemainder() {
        assertEquals(emptyList<Int>(), gridRows(0, 2))
        assertEquals(listOf(2, 2), gridRows(4, 2))
        assertEquals(listOf(2, 2, 1), gridRows(5, 2))
        assertEquals(listOf(3, 2), gridRows(5, 3))
        assertEquals(listOf(1), gridRows(1, 4))
        assertEquals(listOf(1, 1), gridRows(2, 0))
    }

    @Test
    fun aPictureNearThePreferredShapeIsCroppedToIt() {
        assertEquals(ImageFrame(1.5f, fit = false), ImageFrame.of(1200f, 800f, 1.5f))
        assertEquals(ImageFrame(1.5f, fit = false), ImageFrame.of(1600f, 900f, 1.5f))
        assertEquals(ImageFrame(1.5f, fit = false), ImageFrame.of(1000f, 800f, 1.5f))
        // Size not known yet.
        assertEquals(ImageFrame(1.5f, fit = false), ImageFrame.of(0f, 0f, 1.5f))
        assertEquals(ImageFrame(1.5f, fit = false), ImageFrame.of(Float.NaN, 10f, 1.5f))
    }

    @Test
    fun aWideStripGetsAShallowerFrameInsteadOfLosingItsEnds() {
        val strip = ImageFrame.of(1600f, 500f, 1.5f)
        assertEquals(3.2f, strip.ratio, 0.001f)
        assertFalse(strip.fit)
        // Beyond the shallowest frame it is fitted whole.
        val ribbon = ImageFrame.of(3000f, 300f, 1.5f)
        assertEquals(ImageFrame.WIDEST_RATIO, ribbon.ratio, 0.001f)
        assertTrue(ribbon.fit)
    }

    @Test
    fun aTallPictureIsNeverCutOff() {
        // A square-ish picture takes a frame of its own shape.
        assertEquals(ImageFrame(1f, fit = false), ImageFrame.of(900f, 900f, 1.5f))
        // A tall comic strip is fitted whole inside the tallest frame allowed.
        assertEquals(ImageFrame(1f, fit = true), ImageFrame.of(600f, 1700f, 1.5f))
        // The reader allows taller frames, so a portrait keeps its own shape there.
        assertEquals(ImageFrame(0.75f, fit = false), ImageFrame.of(600f, 800f, 1.5f, tallestRatio = 0.6f))
        assertEquals(ImageFrame(0.6f, fit = true), ImageFrame.of(600f, 1700f, 1.5f, tallestRatio = 0.6f))
    }

    // --- "No new edition today" ---------------------------------------------------------------

    private val zone = java.time.ZoneId.of("Europe/Amsterdam")
    private val reset = java.time.LocalTime.of(6, 30)
    private fun at(date: String, time: String): Instant =
        java.time.LocalDateTime.parse("${date}T$time").atZone(zone).toInstant()
    private fun day(date: String) = java.time.LocalDate.parse(date)

    @Test
    fun todaysEditionOnTheStandIsCurrent() {
        assertEquals(StandEdition.Current, StandEditions.of(day("2026-11-10"), at("2026-11-10", "09:00"), reset, zone))
        // Exactly at the reset the new edition is the expected one.
        assertEquals(StandEdition.Current, StandEditions.of(day("2026-11-10"), at("2026-11-10", "06:30"), reset, zone))
    }

    @Test
    fun aResetThatPrintedNothingLeavesNoNewEdition() {
        assertEquals(StandEdition.NoNewEdition, StandEditions.of(day("2026-11-09"), at("2026-11-10", "09:00"), reset, zone))
        assertEquals(StandEdition.NoNewEdition, StandEditions.of(day("2026-11-03"), at("2026-11-10", "06:30"), reset, zone))
    }

    @Test
    fun beforeTheResetYesterdaysPaperIsStillTheCurrentOne() {
        assertEquals(day("2026-11-09"), StandEditions.expectedDate(at("2026-11-10", "06:29"), reset, zone))
        assertEquals(StandEdition.Current, StandEditions.of(day("2026-11-09"), at("2026-11-10", "05:00"), reset, zone))
        // But the day before yesterday's is not, even before today's reset.
        assertEquals(StandEdition.NoNewEdition, StandEditions.of(day("2026-11-08"), at("2026-11-10", "05:00"), reset, zone))
    }

    @Test
    fun anEmptyStandIsItsOwnState() {
        assertEquals(StandEdition.None, StandEditions.of(null, at("2026-11-10", "09:00"), reset, zone))
    }

    @Test
    fun anEditionDatedAheadIsNeverCalledMissing() {
        // The clock was set back, or the zone changed: the stand does not complain.
        assertEquals(StandEdition.Current, StandEditions.of(day("2026-11-11"), at("2026-11-10", "09:00"), reset, zone))
    }

    @Test
    fun theLastPaperIsNamedByWeekdayOnlyWithinTheWeek() {
        assertEquals(java.time.DayOfWeek.MONDAY, StandEditions.sinceWeekday(day("2026-11-09"), day("2026-11-10")))
        assertEquals(java.time.DayOfWeek.WEDNESDAY, StandEditions.sinceWeekday(day("2026-11-04"), day("2026-11-10")))
        // A week back the weekday is today's own name, which would mislead.
        assertNull(StandEditions.sinceWeekday(day("2026-11-03"), day("2026-11-10")))
        assertNull(StandEditions.sinceWeekday(day("2026-11-10"), day("2026-11-10")))
    }

    // --- The last E-ink screen ----------------------------------------------------------------

    @Test
    fun theLastStepFillsTheScreenInsteadOfShowingMostlyBlank() {
        // Content 2500 px, viewport 1000, a viewport of filler: full of content up to 1500.
        val anchors = IntArray(26) { it * 100 }
        val first = StepScroll.forward(0, 1000, max = 2500, anchors = anchors, overlap = 40, contentMax = 1500)
        assertEquals(900, first)
        // From 900 the raw step is 1860: without the rule it would stop at 1800 and show 700 px
        // of content over 300 px of blank. It stops at 1500, the first row that fills the screen.
        assertEquals(1500, StepScroll.forward(first, 1000, max = 2500, anchors = anchors, overlap = 40, contentMax = 1500))
    }

    @Test
    fun theLastStepStillLandsOnARowWhenNoneSitsExactlyAtTheEnd() {
        // Rows every 270 px: the first row at or after 1500 is 1620.
        val anchors = IntArray(12) { it * 270 }
        val from = 810
        val target = StepScroll.forward(from, 1000, max = 2500, anchors = anchors, overlap = 40, contentMax = 1500)
        assertEquals(1620, target)
        assertTrue("nothing is skipped", target <= from + 1000)
    }

    @Test
    fun aRowThatWouldLeaveMoreThanAThirdBlankIsPassedOverForAFullScreen() {
        // A tall lead from 200 to 1750, then rows: the first row after 820 is 930 px on.
        val anchors = intArrayOf(0, 200, 1750, 2350)
        assertEquals(820, StepScroll.forward(0, 2280, max = 5380, anchors = anchors, overlap = 80, contentMax = 820))
        // With a stop inside the lead (its headline) the step takes that instead.
        assertEquals(1250, StepScroll.forward(0, 2280, max = 5380, anchors = anchors + 1250, overlap = 80, contentMax = 820))
    }

    @Test
    fun aSliderWithEdgeStopsPicksTheNearestStop() {
        // Stops at 20, 135, 250, 365, 480 on a 500 px track with a 20 px inset.
        assertEquals(20f, SliderSteps.positionOf(0, 500f, inset = 20f), 0.01f)
        assertEquals(250f, SliderSteps.positionOf(2, 500f, inset = 20f), 0.01f)
        assertEquals(480f, SliderSteps.positionOf(4, 500f, inset = 20f), 0.01f)
        assertEquals(20f, SliderSteps.positionOf(4, 500f, rtl = true, inset = 20f), 0.01f)
        assertEquals(SliderStep.RARELY, SliderSteps.stepAt(70f, 500f, inset = 20f))
        assertEquals(SliderStep.LESS, SliderSteps.stepAt(80f, 500f, inset = 20f))
        assertEquals(SliderStep.MORE, SliderSteps.stepAt(365f, 500f, inset = 20f))
        assertEquals(SliderStep.FAVOURITE, SliderSteps.stepAt(430f, 500f, inset = 20f))
        // Every stop maps back to itself.
        for (index in 0 until SliderSteps.count) {
            assertEquals(index, SliderSteps.indexAt(SliderSteps.positionOf(index, 500f, inset = 20f), 500f, inset = 20f))
        }
    }

    @Test
    fun withoutAnchorsTheLastStepShowsAFullScreen() {
        assertEquals(1500, StepScroll.forward(900, 1000, max = 2500, anchors = IntArray(0), overlap = 40, contentMax = 1500))
        // Not yet within reach of the end: an ordinary step.
        assertEquals(960, StepScroll.forward(0, 1000, max = 4000, anchors = IntArray(0), overlap = 40, contentMax = 3000))
        // Already showing the end.
        assertEquals(1500, StepScroll.forward(1500, 1000, max = 2500, anchors = IntArray(0), overlap = 40, contentMax = 1500))
    }
}
