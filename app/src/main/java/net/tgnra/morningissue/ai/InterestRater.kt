package net.tgnra.morningissue.ai

import java.time.Clock
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import net.tgnra.morningissue.data.db.ArticleInterestEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.engine.RankingConfig

/** What one rating pass did. */
data class RatingReport(
    /** Posts the model gave a usable rating. */
    val rated: Int = 0,
    /** Calls made to the model. */
    val calls: Int = 0,
    /** Why the pass stopped before rating everything it meant to, if it did. */
    val stoppedBy: StopReason? = null,
)

enum class StopReason {
    /** Nothing is known about the reader yet, so there is nothing to rate for. */
    NO_PROFILE,

    /** The model is not available on this phone (or not yet). */
    MODEL_UNAVAILABLE,

    /** The app left the foreground; AICore would refuse. */
    BACKGROUND,

    /** The time budget ran out. */
    BUDGET,

    /** A call took too long. */
    TIMEOUT,

    /** AICore refused (background use, a battery quota, busy) or the model went away. */
    REFUSED,
}

/**
 * Asks Gemini Nano to rate the posts most likely to make the next edition, and stores what it
 * says (`article_interest`). Runs only while the app is in the foreground, because AICore will
 * not serve an app in the background: as a stage of the print on open (`edition/EditionPress`),
 * just before the build, and ahead of time when the app is open anyway. The build itself only
 * reads the stored ratings.
 *
 * One pass:
 *
 * 1. Builds the [TasteProfile] from what the app has learned (strongest tags, feeds set to More
 *    or Favourite, recent Likes and saves). With nothing learned yet it stops: the model would
 *    be rating for nobody in particular.
 * 2. Takes the [shortlist], the next edition's best candidates by their score without N (about
 *    40), and keeps those with no rating, then those rated under a different profile.
 * 3. Asks the model about them [InterestPrompt.BATCH_SIZE] at a time, each call within
 *    [callTimeout] and the whole pass within [budget], stopping as soon as the app leaves the
 *    foreground, a call times out or AICore refuses. A failed call leaves its posts unrated.
 * 4. Stores each usable rating with the time and the profile's hash.
 *
 * A post the pass did not reach, or whose rating could not be read, stays unrated and scores as
 * if there were no model (N = 1).
 */
class InterestRater(
    private val database: MorningIssueDatabase,
    private val model: NanoModel,
    private val clock: Clock,
    /** The next edition's best candidates, best first (`RoomEditionPublisher.shortlist`). */
    private val shortlist: suspend () -> List<Long>,
    /** Whether an activity of the app is started; checked before every call. */
    private val isForeground: () -> Boolean,
    private val config: RankingConfig = RankingConfig.Default,
    private val budget: Duration = DEFAULT_BUDGET,
    private val callTimeout: Duration = DEFAULT_CALL_TIMEOUT,
) {
    private val dao = database.interestDao()

    /** The reader as the model is told about them; see [TasteProfile]. */
    suspend fun profile(): TasteProfile {
        val tags = database.tagDao().getAll().map { it.name to it.affinity }
        val feeds = database.feedDao().getSubscribedWithDetails().map { it.feed.title to it.feed.sliderStep }
        val recent = dao.recentLikedOrSavedTitles(TasteProfile.MAX_RECENT)
        return TasteProfile.build(tags, feeds, recent, config.neutralAffinity)
    }

    /**
     * One pass over [articleIds] (by default the [shortlist]) within [budget]. [onProgress] is
     * told how many of the posts to rate have been asked about, first with 0 once the model is
     * really going to be asked, then after each call.
     */
    suspend fun rate(
        articleIds: List<Long>? = null,
        budget: Duration = this.budget,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RatingReport {
        val started = clock.instant()
        val profile = profile()
        if (profile.isEmpty) return RatingReport(stoppedBy = StopReason.NO_PROFILE)
        val hash = profile.hash()

        val ids = articleIds ?: shortlist()
        if (ids.isEmpty()) return RatingReport()
        val stored = dao.ratingsOf(ids)
        // Never rated first, then rated under an older profile; each group in shortlist order.
        val todo = ids.filter { it !in stored } + ids.filter { id -> stored[id]?.let { it.profileHash != hash } == true }
        if (todo.isEmpty()) return RatingReport()
        if (!isForeground()) return RatingReport(stoppedBy = StopReason.BACKGROUND)
        if (model.status() != NanoStatus.AVAILABLE) return RatingReport(stoppedBy = StopReason.MODEL_UNAVAILABLE)

        val posts = dao.postsForRating(todo).associateBy { it.articleId }
        val asked = todo.mapNotNull { posts[it] }
        var rated = 0
        var calls = 0
        var done = 0
        onProgress(0, asked.size)
        for (batch in asked.chunked(InterestPrompt.BATCH_SIZE)) {
            if (!isForeground()) return RatingReport(rated, calls, StopReason.BACKGROUND)
            if (Duration.between(started, clock.instant()) >= budget) return RatingReport(rated, calls, StopReason.BUDGET)
            val reply = try {
                calls++
                withTimeout(callTimeout.toMillis()) { model.generate(InterestPrompt.build(profile, batch)) }
            } catch (_: TimeoutCancellationException) {
                return RatingReport(rated, calls, StopReason.TIMEOUT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NanoException) {
                if (e.failure == NanoFailure.OTHER) {
                    done += batch.size
                    onProgress(done, asked.size)
                    continue
                }
                return RatingReport(rated, calls, StopReason.REFUSED)
            }
            val now = clock.instant()
            val ratings = InterestReply.parse(reply, batch.size).map { (number, rating) ->
                ArticleInterestEntity(batch[number - 1].articleId, rating, now, hash)
            }
            if (ratings.isNotEmpty()) dao.upsert(ratings)
            rated += ratings.size
            done += batch.size
            onProgress(done, asked.size)
        }
        return RatingReport(rated, calls)
    }

    companion object {
        /** A pass gives up after this long; with Nano at a second or two per call, about 40 posts take well under it. */
        val DEFAULT_BUDGET: Duration = Duration.ofSeconds(30)

        /** One call that takes longer than this is abandoned and the pass ends. */
        val DEFAULT_CALL_TIMEOUT: Duration = Duration.ofSeconds(12)
    }
}
