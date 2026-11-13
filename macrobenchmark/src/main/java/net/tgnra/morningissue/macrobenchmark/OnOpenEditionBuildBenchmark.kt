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
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The budget: building an edition from stored posts takes under 300 ms on a low-end phone.
 * This was the on-open fallback build, and the class and trace section keep that name. The
 * app no longer builds on open: the same build (`RoomEditionPublisher.ensureCurrentEdition`)
 * now runs as the middle stage of Print now and of a late background run, and it is still
 * what the reader waits for between "Fetching your sources" and the stories.
 *
 * A macrobenchmark times app start-up and frames, not one function, so the build is wrapped in
 * a trace section in `RoomEditionPublisher.ensureCurrentEdition` and that section is what is
 * measured here ([TraceSectionMetric]): from asking for the build to the edition being stored,
 * including reading the candidates, relearning affinity, scoring, layout and the insert.
 *
 * Each iteration seeds the database with [POSTS] unread posts from [FEEDS] feeds and no edition
 * (through the benchmark build's `BenchmarkSeedReceiver`), kills the app and starts it cold.
 * The newsstand then shows the unprinted placeholder and builds nothing, so the benchmark
 * asks for the build itself: a second broadcast to the same receiver (`--ez print true`)
 * calls `ensureCurrentEdition()` in the running app, once. Pressing Print now is not used,
 * because that would first sync the seeded feeds, which do not exist on the network. The
 * iteration ends when the new edition's cover has replaced the placeholder on the stand.
 *
 * Run on a real low-end phone for numbers to hold against the budget: an emulator's are only
 * indicative. **This replaces the installed app's data.**
 *
 * Ignored since the phone became a client of a Morning Issue server: editions are built on the
 * server, so there is no build on the phone to time (`docs/server-design.md`, Phase 2
 * deviations, W-D).
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class OnOpenEditionBuildBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Ignore("Editions are built on the server; the phone has no build to time")
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
        val result = device.executeShellCommand(
            "am broadcast -n $TARGET_PACKAGE/.benchmark.BenchmarkSeedReceiver --ez print true",
        )
        assertTrue("the build did not run: $result", result.contains("printed edition"))
        // The cover's description ("Edition No. 1, …") appears once the build has been stored;
        // the placeholder's says "not printed" and has no number.
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
