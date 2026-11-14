package net.tgnra.morningissue.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.edition.FakeSettings
import net.tgnra.morningissue.edition.MutableClock
import net.tgnra.morningissue.model.AppSettings
import net.tgnra.morningissue.model.SliderStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The gate in front of the model: its status, the setting and the download. */
@RunWith(AndroidJUnit4::class)
class OnDeviceAiTest {
    private val now: Instant = Instant.parse("2026-11-10T07:00:00Z")
    private lateinit var db: MorningIssueDatabase
    private var downloadsAsked = 0
    private var shortlist = emptyList<Long>()

    private class Model(var status: NanoStatus) : NanoModel {
        var calls = 0
        var downloads = 0

        override suspend fun status() = status

        override suspend fun generate(prompt: String): String {
            calls++
            return "{\"1\": 5}"
        }

        override suspend fun download(): Boolean {
            downloads++
            status = NanoStatus.AVAILABLE
            return true
        }
    }

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun onePostToRate() {
        val feed = db.feedDao().insert(FeedEntity(url = "https://example.com/f.xml", title = "Loved", sliderStep = SliderStep.FAVOURITE, addedAt = now))
        shortlist = listOf(db.articleDao().insertNew(ArticleEntity(feedId = feed, guid = "a", title = "A post", publishedAt = now, firstSeenAt = now))!!)
    }

    private fun TestScope.ai(model: NanoModel, settings: FakeSettings = FakeSettings()) = OnDeviceAi(
        model = model,
        settings = settings,
        rater = InterestRater(db, model, MutableClock(now), shortlist = { shortlist }, isForeground = { true }),
        scope = this,
        scheduleDownload = { downloadsAsked++ },
    )

    @Test
    fun theStatusIsWhatTheModelSays() = runTest {
        for (status in NanoStatus.entries) {
            val ai = ai(Model(status))
            assertNull(ai.status.value)
            assertEquals(status, ai.refresh())
            assertEquals(status, ai.status.value)
        }
    }

    @Test
    fun aCheckThatFailsCountsAsUnavailable() = runTest {
        val broken = object : NanoModel {
            override suspend fun status(): NanoStatus = throw IllegalStateException("no AICore")
            override suspend fun generate(prompt: String): String = error("never")
            override suspend fun download() = false
        }
        assertEquals(NanoStatus.UNAVAILABLE, ai(broken).refresh())
    }

    @Test
    fun theDownloadIsAskedForOnlyWhenWantedAndPossible() = runTest {
        ai(Model(NanoStatus.DOWNLOADABLE)).refresh()
        assertEquals(1, downloadsAsked)

        for (status in listOf(NanoStatus.AVAILABLE, NanoStatus.DOWNLOADING, NanoStatus.UNAVAILABLE)) ai(Model(status)).refresh()
        assertEquals(1, downloadsAsked)

        ai(Model(NanoStatus.DOWNLOADABLE), FakeSettings(AppSettings(onDeviceAiRanking = false))).refresh()
        assertEquals(1, downloadsAsked)
    }

    @Test
    fun theDownloadJobFetchesTheModelOnlyWhenItIsStillWanted() = runTest {
        val wanted = Model(NanoStatus.DOWNLOADABLE)
        assertTrue(ai(wanted).download())
        assertEquals(1, wanted.downloads)
        assertEquals(NanoStatus.AVAILABLE, wanted.status)

        val switchedOff = Model(NanoStatus.DOWNLOADABLE)
        assertTrue(ai(switchedOff, FakeSettings(AppSettings(onDeviceAiRanking = false))).download())
        assertEquals(0, switchedOff.downloads)

        val unsupported = Model(NanoStatus.UNAVAILABLE)
        assertTrue(ai(unsupported).download())
        assertEquals(0, unsupported.downloads)
    }

    @Test
    fun ratingNeedsTheSettingAndTheModel() = runTest {
        onePostToRate()

        val off = Model(NanoStatus.AVAILABLE)
        assertNull(ai(off, FakeSettings(AppSettings(onDeviceAiRanking = false))).rateNow())
        assertEquals(0, off.calls)

        for (status in listOf(NanoStatus.UNAVAILABLE, NanoStatus.DOWNLOADABLE, NanoStatus.DOWNLOADING)) {
            val absent = Model(status)
            assertNull(ai(absent).rateNow())
            assertEquals(0, absent.calls)
        }
        assertEquals(0, db.interestDao().count())

        val on = Model(NanoStatus.AVAILABLE)
        val report = ai(on).rateNow()
        assertNotNull(report)
        assertEquals(1, report!!.rated)
        assertEquals(1, db.interestDao().count())
    }

    @Test
    fun rateSoonRunsAPassOnTheScope() = runTest {
        onePostToRate()
        val model = Model(NanoStatus.AVAILABLE)
        val ai = ai(model)

        val pass = ai.rateSoon()
        assertFalse(model.calls > 0)
        pass.join()

        assertEquals(1, model.calls)
    }

    // The print's rating is held to a budget on the clock, and the database answers on its own
    // threads, so these run on real time rather than the test's virtual time.

    @Test
    fun thePrintsRatingNeedsTheSettingAndTheModelAndReportsProgress() = runTest {
        withContext(Dispatchers.Default) {
            onePostToRate()
            val off = Model(NanoStatus.AVAILABLE)
            assertNull(realAi(off, FakeSettings(AppSettings(onDeviceAiRanking = false))).rateFor(shortlist, Duration.ofSeconds(45)) { _, _ -> })
            assertNull(realAi(Model(NanoStatus.DOWNLOADABLE)).rateFor(shortlist, Duration.ofSeconds(45)) { _, _ -> })
            assertEquals(0, off.calls)

            val progress = mutableListOf<Pair<Int, Int>>()
            val report = realAi(Model(NanoStatus.AVAILABLE)).rateFor(shortlist, Duration.ofSeconds(45)) { done, total -> progress += done to total }
            assertEquals(1, report!!.rated)
            assertEquals(listOf(0 to 1, 1 to 1), progress)
        }
    }

    @Test
    fun thePrintsRatingWaitsForAPassUnderWayAndDoesNotAskAgain() = runTest {
        withContext(Dispatchers.Default) {
            onePostToRate()
            val gate = CompletableDeferred<Unit>()
            val model = object : NanoModel {
                val calls = AtomicInteger()
                override suspend fun status() = NanoStatus.AVAILABLE
                override suspend fun generate(prompt: String): String {
                    calls.incrementAndGet()
                    gate.await()
                    return "{\"1\": 2}"
                }
                override suspend fun download() = true
            }
            val ai = realAi(model)
            ai.rateSoon()
            withTimeout(5_000) { while (model.calls.get() == 0) delay(10) }
            launch {
                delay(300)
                gate.complete(Unit)
            }

            val report = ai.rateFor(shortlist, Duration.ofSeconds(45)) { _, _ -> }

            assertNotNull("waited for the pass instead of giving up at once", report)
            assertEquals("the pass under way rated it; it is not asked again", 1, model.calls.get())
            assertEquals(2, db.interestDao().ratingsOf(shortlist).values.single().rating)
        }
    }

    @Test
    fun thePrintsRatingGivesUpAtItsBudget() = runTest {
        withContext(Dispatchers.Default) {
            onePostToRate()
            val model = object : NanoModel {
                override suspend fun status() = NanoStatus.AVAILABLE
                override suspend fun generate(prompt: String): String = CompletableDeferred<String>().await()
                override suspend fun download() = true
            }
            val started = System.nanoTime()
            assertNull(realAi(model).rateFor(shortlist, Duration.ofMillis(300)) { _, _ -> })
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        }
    }

    private fun realAi(model: NanoModel, settings: FakeSettings = FakeSettings()) = OnDeviceAi(
        model = model,
        settings = settings,
        rater = InterestRater(db, model, MutableClock(now), shortlist = { shortlist }, isForeground = { true }),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        scheduleDownload = { downloadsAsked++ },
    )
}
