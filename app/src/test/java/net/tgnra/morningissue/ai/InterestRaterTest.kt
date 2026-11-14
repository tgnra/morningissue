package net.tgnra.morningissue.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.ArticleInterestEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.edition.MutableClock
import net.tgnra.morningissue.model.SliderStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A rating pass against a fake model, on an in-memory database. */
@RunWith(AndroidJUnit4::class)
class InterestRaterTest {
    private val now: Instant = Instant.parse("2026-11-10T07:00:00Z")
    private val clock = MutableClock(now)
    private lateinit var db: MorningIssueDatabase
    private var foreground = true
    private var guid = 0

    /** Answers every prompt by rating each post in it [rating], unless told otherwise. */
    private inner class FakeModel(
        var status: NanoStatus = NanoStatus.AVAILABLE,
        var reply: (prompt: String, count: Int) -> String = { _, count -> (1..count).joinToString(", ", "{", "}") { "\"$it\": 5" } },
    ) : NanoModel {
        val prompts = mutableListOf<String>()
        var failure: NanoFailure? = null
        var slow = false

        override suspend fun status() = status

        override suspend fun generate(prompt: String): String {
            prompts += prompt
            failure?.let { throw NanoException(it) }
            if (slow) delay(60_000)
            val count = Regex("(?m)^\\d+\\. ").findAll(prompt).count()
            return reply(prompt, count)
        }

        override suspend fun download() = true
    }

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun addFeed(title: String, slider: SliderStep = SliderStep.AUTO): Long =
        db.feedDao().insert(FeedEntity(url = "https://example.com/$title.xml", title = title, sliderStep = slider, addedAt = now))

    private suspend fun addPost(feedId: Long, title: String = "Post ${guid}", summary: String? = "A summary."): Long {
        val id = "p${guid++}"
        return db.articleDao().insertNew(
            ArticleEntity(feedId = feedId, guid = id, url = "https://example.com/$id", title = title, summary = summary, publishedAt = now, firstSeenAt = now),
        )!!
    }

    /** A reader with a Favourite feed, so the profile is not empty, and [n] posts to rate. */
    private suspend fun library(n: Int): List<Long> {
        val feed = addFeed("Daring Fireball", SliderStep.FAVOURITE)
        return (0 until n).map { addPost(feed) }
    }

    private fun rater(model: NanoModel, shortlist: List<Long>, budget: Duration = InterestRater.DEFAULT_BUDGET) = InterestRater(
        database = db,
        model = model,
        clock = clock,
        shortlist = { shortlist },
        isForeground = { foreground },
        budget = budget,
    )

    @Test
    fun theShortlistIsRatedTenAtATimeAndStoredWithTheTimeAndProfile() = runTest {
        val ids = library(25)
        val model = FakeModel()
        val rater = rater(model, ids)

        val report = rater.rate()

        assertEquals(RatingReport(rated = 25, calls = 3), report)
        assertEquals(3, model.prompts.size)
        assertTrue(model.prompts[0].contains("Sources they asked to see more of: Daring Fireball"))
        assertTrue(model.prompts[0].contains("10. "))
        assertTrue(model.prompts[2].contains("5. ") && !model.prompts[2].contains("6. "))
        val stored = db.interestDao().ratingsOf(ids)
        assertEquals(25, stored.size)
        val hash = rater.profile().hash()
        assertTrue(stored.values.all { it.rating == 5 && it.ratedAt == now && it.profileHash == hash })
    }

    @Test
    fun eachRatingGoesToThePostItWasGivenFor() = runTest {
        val ids = library(4)
        // Rates post n of each batch n (1 to 4), so a mix-up would show.
        val model = FakeModel(reply = { _, count -> (1..count).joinToString(", ", "{", "}") { "\"$it\": ${1 + (it - 1) % 5}" } })

        rater(model, ids).rate()

        val stored = db.interestDao().ratingsOf(ids)
        assertEquals(listOf(1, 2, 3, 4), ids.map { stored[it]!!.rating })
    }

    @Test
    fun aRebuildReusesStoredRatingsAndTheModelIsNotAskedAgain() = runTest {
        val ids = library(12)
        val model = FakeModel()
        rater(model, ids).rate()
        model.prompts.clear()

        val again = rater(model, ids).rate()

        assertEquals(RatingReport(), again)
        assertTrue(model.prompts.isEmpty())
    }

    @Test
    fun aNewProfileGetsTheRatingsRedoneUnratedPostsFirst() = runTest {
        val ids = library(3)
        val rater = rater(FakeModel(), ids)
        val oldHash = "0000000000000000"
        db.interestDao().upsert(listOf(ArticleInterestEntity(ids[0], 2, now.minusSeconds(3600), oldHash)))
        val model = FakeModel()

        rater(model, ids).rate()

        val prompt = model.prompts.single()
        // The never-rated posts come first, then the one rated under the old profile.
        val order = Regex("(?m)^(\\d+)\\. Post (\\d+)").findAll(prompt).map { it.groupValues[2].toInt() }.toList()
        assertEquals(listOf(1, 2, 0), order)
        val stored = db.interestDao().ratingsOf(ids)
        assertTrue(stored.values.all { it.profileHash == rater.profile().hash() && it.rating == 5 })
    }

    @Test
    fun malformedRepliesLeavePostsUnrated() = runTest {
        val ids = library(3)
        val model = FakeModel(reply = { _, _ -> """{"1": 4, "2": "very", "7": 5}""" })

        val report = rater(model, ids).rate()

        assertEquals(1, report.rated)
        assertEquals(setOf(ids[0]), db.interestDao().ratingsOf(ids).keys)
    }

    @Test
    fun nothingIsAskedForAReaderNobodyKnows() = runTest {
        val feed = addFeed("Plain")
        val ids = listOf(addPost(feed))
        val model = FakeModel()

        assertEquals(StopReason.NO_PROFILE, rater(model, ids).rate().stoppedBy)
        assertTrue(model.prompts.isEmpty())
    }

    @Test
    fun aLikeIsEnoughOfAProfile() = runTest {
        val feed = addFeed("Plain")
        val liked = addPost(feed, title = "Rockets")
        db.engagementDao().setLiked(liked, now)
        val model = FakeModel()

        rater(model, listOf(addPost(feed))).rate()

        assertTrue(model.prompts.single().contains("- Rockets"))
    }

    @Test
    fun nothingIsAskedWhenTheModelIsNotThere() = runTest {
        val ids = library(3)
        for (status in listOf(NanoStatus.UNAVAILABLE, NanoStatus.DOWNLOADABLE, NanoStatus.DOWNLOADING)) {
            val model = FakeModel(status = status)
            assertEquals(StopReason.MODEL_UNAVAILABLE, rater(model, ids).rate().stoppedBy)
            assertTrue(model.prompts.isEmpty())
        }
        assertEquals(0, db.interestDao().count())
    }

    @Test
    fun nothingIsAskedFromTheBackground() = runTest {
        val ids = library(3)
        foreground = false
        val model = FakeModel()

        assertEquals(StopReason.BACKGROUND, rater(model, ids).rate().stoppedBy)
        assertTrue(model.prompts.isEmpty())
    }

    @Test
    fun thePassStopsWhenTheAppLeavesTheForeground() = runTest {
        val ids = library(30)
        val model = FakeModel(reply = { _, count ->
            foreground = false
            (1..count).joinToString(", ", "{", "}") { "\"$it\": 4" }
        })

        val report = rater(model, ids).rate()

        assertEquals(RatingReport(rated = 10, calls = 1, stoppedBy = StopReason.BACKGROUND), report)
    }

    @Test
    fun aRefusalEndsThePassAndAnOrdinaryFailureSkipsOneBatch() = runTest {
        val ids = library(20)
        val refused = FakeModel().apply { failure = NanoFailure.BACKGROUND_BLOCKED }
        assertEquals(RatingReport(calls = 1, stoppedBy = StopReason.REFUSED), rater(refused, ids).rate())

        val flaky = FakeModel()
        var first = true
        flaky.reply = { _, count ->
            if (first) {
                first = false
                throw NanoException(NanoFailure.OTHER)
            }
            (1..count).joinToString(", ", "{", "}") { "\"$it\": 2" }
        }
        val report = rater(flaky, ids).rate()
        assertEquals(RatingReport(rated = 10, calls = 2), report)
        assertEquals(10, db.interestDao().count())
    }

    @Test
    fun aSlowCallTimesOut() = runTest {
        val ids = library(20)
        val model = FakeModel().apply { slow = true }

        val report = rater(model, ids).rate()

        assertEquals(StopReason.TIMEOUT, report.stoppedBy)
        assertEquals(0, db.interestDao().count())
    }

    @Test
    fun thePassKeepsToItsBudget() = runTest {
        val ids = library(40)
        val model = FakeModel(reply = { _, count ->
            clock.now = clock.now.plusSeconds(20)
            (1..count).joinToString(", ", "{", "}") { "\"$it\": 3" }
        })

        val report = rater(model, ids, budget = Duration.ofSeconds(30)).rate()

        assertEquals(RatingReport(rated = 20, calls = 2, stoppedBy = StopReason.BUDGET), report)
    }

    @Test
    fun anEmptyShortlistAsksNothing() = runTest {
        library(3)
        val model = FakeModel()
        assertEquals(RatingReport(), rater(model, emptyList()).rate())
        assertNull(rater(model, emptyList()).rate().stoppedBy)
        assertTrue(model.prompts.isEmpty())
    }
}
