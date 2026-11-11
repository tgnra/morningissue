package net.tgnra.morningissue.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The budget: the on-open fallback edition build takes under 300 ms on a low-end phone.
 *
 * A macrobenchmark times app start-up and frames, not one function, so the build is wrapped in
 * a trace section in `RoomEditionPublisher.ensureCurrentEdition` and that section is what is
 * measured here ([TraceSectionMetric]): from asking for the build to the edition being stored,
 * including reading the candidates, relearning affinity, scoring, layout and the insert.
 *
 * Each iteration seeds the database with [POSTS] unread posts from [FEEDS] feeds and no edition
 * (through the benchmark build's `BenchmarkSeedReceiver`), kills the app and starts it cold;
 * the newsstand then asks for the current edition and the build runs once. The iteration ends
 * when the new edition's cover is on the stand.
 *
 * Run on a real low-end phone for numbers to hold against the budget: an emulator's are only
 * indicative. **This replaces the installed app's data.**
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class OnOpenEditionBuildBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun onOpenBuildOverOneThousandCandidates() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            // An async section, so it is not on a thread track: look in the whole trace by name.
            TraceSectionMetric(TRACE_ON_OPEN_BUILD, TraceSectionMetric.Mode.Sum, targetPackageOnly = false),
            StartupTimingMetric(),
        ),
        // What a phone has after the Play Store's install-time profile, or none: no warm-up help.
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            val result = device.executeShellCommand(
                "am broadcast -n $TARGET_PACKAGE/.benchmark.BenchmarkSeedReceiver --ei posts $POSTS --ei feeds $FEEDS",
            )
            assertTrue("seeding failed: $result", result.contains("seeded $POSTS posts"))
        },
    ) {
        startActivityAndWait()
        // The cover's description ("Edition No. 1, …") appears once the build has been stored.
        assertTrue(
            "no edition appeared on the stand",
            device.wait(Until.hasObject(By.descContains("Edition No.")), EDITION_TIMEOUT_MILLIS),
        )
    }

    private companion object {
        const val TARGET_PACKAGE = "net.tgnra.morningissue"
        const val TRACE_ON_OPEN_BUILD = "MorningIssue.onOpenEditionBuild"
        const val POSTS = 1000
        const val FEEDS = 40
        const val ITERATIONS = 5
        const val EDITION_TIMEOUT_MILLIS = 30_000L
    }
}
