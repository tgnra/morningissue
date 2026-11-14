package net.tgnra.morningissue.data.signals

import androidx.room3.withWriteTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.data.db.ArticleSignalDao
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.EnclosureKind

/**
 * Computes `article_traits` for every post that has no row under the current rules
 * (`ArticleTraitRules.VERSION`): the posts stored before the table existed, posts brought in
 * by an archive import, and all of them again whenever the rules change.
 *
 * It works from what is stored: the title, the link, the feed's site address and the body in
 * `article_content`. A body trimmed for the storage budget is gone, so such a post gets zero
 * counts and is judged by its title and link alone. The enclosure is not stored anywhere
 * else: a row that is being recomputed keeps its own, and a post without a row gets none until
 * the syncer sees it in its feed again (`DefaultFeedSyncer`).
 *
 * Safe to run at any time and as often as wanted: a pass with nothing to do is one indexed
 * read, a pass cut short leaves the posts it reached finished, and a row a sync writes while
 * a pass is running is left as the sync wrote it.
 */
class TraitsBackfill(
    private val database: MorningIssueDatabase,
    /** Decoding the stored bodies is the slow part; it runs here, outside the transaction. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val batchSize: Int = BATCH,
) {
    init {
        require(batchSize in 1..ArticleSignalDao.MAX_IDS)
    }

    private val signalDao = database.articleSignalDao()

    /** Returns how many posts were given traits. */
    suspend fun run(): Int {
        var done = 0
        var afterId = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = signalDao.articlesNeedingTraits(ArticleTraitRules.VERSION, afterId, batchSize)
            if (batch.isEmpty()) return done
            afterId = batch.last().articleId
            val rows = withContext(dispatcher) {
                batch.map { post ->
                    val traits = ArticleTraitRules.compute(
                        title = post.title,
                        url = post.url,
                        siteUrl = post.siteUrl,
                        blocks = post.blocksJson?.let(ContentBlocks::decode).orEmpty(),
                        enclosure = post.enclosure ?: EnclosureKind.NONE,
                    )
                    ArticleSignalDao.traitsRow(post.articleId, traits)
                }
            }
            // Ask again: a sync may have written a row, or a feed been removed, meanwhile.
            val written = database.withWriteTransaction {
                val wanted = signalDao.stillNeedingTraits(ArticleTraitRules.VERSION, rows.map { it.articleId }).toSet()
                val missing = rows.filter { it.articleId in wanted }
                signalDao.upsertTraits(missing)
                missing.size
            }
            done += written
        }
    }

    companion object {
        /** Posts per transaction: a few hundred bodies in memory at most, whatever the size of the library. */
        const val BATCH = 200
    }
}
