package net.tgnra.morningissue.benchmark

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.room3.withWriteTransaction
import java.time.Duration
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.appContainer
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.SliderStep

/**
 * Only in the `benchmark` build type, for `:macrobenchmark`: replaces everything stored with
 * [EXTRA_FEEDS] feeds and [EXTRA_POSTS] unread posts first seen in the last day and **no
 * edition**, so the build from stored posts has that many candidates to work through.
 * Onboarding is marked done so the newsstand shows.
 *
 * Sent from the shell: `am broadcast -n net.tgnra.morningissue/.benchmark.BenchmarkSeedReceiver
 * --ei posts 1000`. The result data says what was stored. Guarded by the DUMP permission, which
 * the shell holds and other apps do not.
 *
 * With `--ez print true` it stores nothing and instead **runs that build**:
 * `EditionPublisher.ensureCurrentEdition()`, the build from stored posts that Print now and a
 * late background run perform between their sync and their prefetch. Opening the app no
 * longer builds an edition, and the seeded feeds do not exist on the network, so the
 * benchmark asks for the build this way instead of pressing Print now. The result data is
 * "printed edition 12", or "printed nothing".
 */
class BenchmarkSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val posts = intent.getIntExtra(EXTRA_POSTS, 1000)
        val feeds = intent.getIntExtra(EXTRA_FEEDS, 40)
        val print = intent.getBooleanExtra(EXTRA_PRINT, false)
        val pending = goAsync()
        val container = context.appContainer
        container.appScope.launch {
            try {
                if (print) {
                    val editionId = container.editionPublisher.ensureCurrentEdition()
                    pending.resultCode = Activity.RESULT_OK
                    pending.resultData = if (editionId != null) "printed edition $editionId" else "printed nothing"
                    return@launch
                }
                seed(context, posts, feeds)
                container.settings.setOnboardingDone(true)
                pending.resultCode = Activity.RESULT_OK
                pending.resultData = "seeded $posts posts in $feeds feeds"
            } catch (e: Exception) {
                pending.resultCode = Activity.RESULT_CANCELED
                pending.resultData = "failed: $e"
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun seed(context: Context, posts: Int, feeds: Int) = withContext(Dispatchers.IO) {
        val container = context.appContainer
        val database = container.core.database
        val now = container.clock.instant()
        val random = Random(SEED)
        database.clearAllTables()
        database.withWriteTransaction {
            // A realistic mix: a few wires that post constantly, dailies, weeklies and rare blogs,
            // spread over a handful of tags, with affinities and sliders that differ.
            val tiers = listOf(
                Triple(CadenceTier.FIREHOSE, 0.6, 3),
                Triple(CadenceTier.DAILY, 8.0, 12),
                Triple(CadenceTier.WEEKLY, 90.0, 15),
                Triple(CadenceTier.RARE, 600.0, 10),
            )
            val tags = listOf("Tech", "Science", "Culture", "Personal", "Writing")
            val feedIds = ArrayList<Pair<Long, Int>>()
            for (index in 0 until feeds) {
                val (tier, median, weight) = tiers[index % tiers.size]
                val id = database.feedDao().insert(
                    FeedEntity(
                        url = "https://example.org/bench-$index/feed.xml",
                        siteUrl = "https://example.org/bench-$index",
                        title = "Benchmark feed $index",
                        medianIntervalHrs = median,
                        burstiness = 2.5,
                        tier = tier,
                        affinity = 0.2 + random.nextDouble() * 0.7,
                        sliderStep = SliderStep.entries[index % SliderStep.entries.size],
                        lastSyncAt = now,
                        addedAt = now.minus(Duration.ofDays(90)),
                    ),
                )
                database.tagDao().setFeedTags(id, if (index % 6 == 5) emptyList() else listOf(tags[index % tags.size]))
                // Fast feeds contribute most of the posts, as they do in life.
                feedIds += id to when (tier) {
                    CadenceTier.FIREHOSE -> weight * 20
                    CadenceTier.DAILY -> weight * 3
                    else -> weight
                }
            }
            val totalWeight = feedIds.sumOf { it.second }
            for (index in 0 until posts) {
                var pick = random.nextInt(totalWeight)
                val feedId = feedIds.first { (_, weight) ->
                    pick -= weight
                    pick < 0
                }.first
                val seenAt = now.minus(Duration.ofMinutes(random.nextLong(5, 23 * 60)))
                val text = "Benchmark post $index. " + "The quick brown fox jumps over the lazy dog. ".repeat(random.nextInt(4, 40))
                database.articleDao().insertNew(
                    article = ArticleEntity(
                        feedId = feedId,
                        guid = "bench-$index",
                        url = "https://example.org/posts/$index",
                        title = "Benchmark headline number $index about something or other",
                        summary = "A standfirst of ordinary length for benchmark post $index, as a feed would give.",
                        imageUrl = if (index % 3 == 0) "https://example.org/images/$index.jpg" else null,
                        author = "A. Writer",
                        publishedAt = seenAt.minus(Duration.ofMinutes(random.nextLong(0, 90))),
                        firstSeenAt = seenAt,
                        wordCount = text.length / 6,
                    ),
                    blocksJson = null,
                    plainText = text,
                ) ?: error("benchmark post $index was not stored")
            }
        }
    }

    private companion object {
        const val EXTRA_POSTS = "posts"
        const val EXTRA_FEEDS = "feeds"
        const val EXTRA_PRINT = "print"
        const val SEED = 20261110
    }
}
