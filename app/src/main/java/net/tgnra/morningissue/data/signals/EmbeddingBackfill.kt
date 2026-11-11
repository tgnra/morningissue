package net.tgnra.morningissue.data.signals

import androidx.room3.withWriteTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.data.db.ArticleEmbeddingEntity
import net.tgnra.morningissue.data.db.ArticleSignalDao
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.embedding.EmbeddableText
import net.tgnra.morningissue.embedding.StaticEmbedder

/**
 * What an `article_embedding` row holds in `vector`. A row under the current model means "this
 * post has been dealt with", whether or not a vector came of it:
 *
 * - [StaticEmbedder.DIMENSIONS] bytes: the vector, as [StaticEmbedder.toBytes] wrote it;
 * - one byte: a mark that the post has no usable vector and why ([NO_KNOWN_WORDS],
 *   [NON_LATIN]). The row keeps the embedding pass from trying the post again every time; to
 *   the ranking it is the same as no row.
 *
 * The marks are part of the stored form, so they are tied to the model as vectors are: a new
 * `MODEL_ID` makes every row stale and every post is tried again.
 */
object StoredEmbedding {
    /** The embedder found no word it knows: no text, only emoji or digits, or symbols. */
    const val NO_KNOWN_WORDS: Byte = 0

    /** The text is mostly in a script the English model cannot read (`EmbeddableText.isMostlyNonLatin`). */
    const val NON_LATIN: Byte = 1

    fun mark(reason: Byte): ByteArray = byteArrayOf(reason)

    /** The mark a row carries, or null when it holds a vector. */
    fun markOf(stored: ByteArray): Byte? = stored.singleOrNull()

    /** The unit vector a row holds; null for no row and for a mark. */
    fun vectorOf(stored: ByteArray?): FloatArray? =
        stored?.takeIf { it.size == StaticEmbedder.DIMENSIONS }?.let(StaticEmbedder::fromBytes)
}

/**
 * Embeds every post that has no `article_embedding` row under the current model: new posts
 * after a sync, the whole library after an upgrade from database version 1 or a change of
 * model, and posts brought in by an archive import. Newest first, so the posts the next
 * edition ranks are done first if the pass is cut short.
 *
 * Every post the pass reads gets a row: its vector, or a mark ([StoredEmbedding]) when the
 * embedder knows none of its words or its text is mostly not in Latin script. A marked post is
 * therefore not read again by later passes. It is not embedded again when its text changes
 * either (full text fetched later); the title and the feed's own opening are what was embedded.
 *
 * Safe to run at any time and as often as wanted. A pass with nothing to do is one read and
 * does not load the embedder. A pass cut short leaves the batches it finished in place.
 * Cancellation is checked between batches; each batch is written in one transaction.
 *
 * @param embedder the shared embedder; only asked for when there is a post to embed
 */
class EmbeddingBackfill(
    private val database: MorningIssueDatabase,
    private val embedder: suspend () -> StaticEmbedder,
    /** Tokenizing and averaging run here, outside the transaction. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val batchSize: Int = BATCH,
) {
    init {
        require(batchSize in 1..ArticleSignalDao.MAX_IDS)
    }

    private val signalDao = database.articleSignalDao()

    /** What one pass did. */
    data class Result(
        /** Posts given a vector. */
        val embedded: Int = 0,
        /** Posts marked as having no usable vector. */
        val marked: Int = 0,
    ) {
        val total: Int get() = embedded + marked
    }

    suspend fun run(): Result {
        var embedded = 0
        var marked = 0
        var beforeId = Long.MAX_VALUE
        var model: StaticEmbedder? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = signalDao.articlesWithoutEmbedding(StaticEmbedder.MODEL_ID, beforeId, batchSize, EmbeddableText.BODY_CHARS)
            if (batch.isEmpty()) return Result(embedded, marked)
            beforeId = batch.last().articleId
            val loaded = model ?: embedder().also { model = it }
            val rows = withContext(dispatcher) {
                batch.map { post -> ArticleEmbeddingEntity(post.articleId, StaticEmbedder.MODEL_ID, stored(loaded, post.title, post.summary, post.body)) }
            }
            // Ask again: a feed may have been removed, or another pass have got here, meanwhile.
            val written = database.withWriteTransaction {
                val wanted = signalDao.stillWithoutEmbedding(StaticEmbedder.MODEL_ID, rows.map { it.articleId }).toSet()
                rows.filter { it.articleId in wanted }.also { signalDao.upsertEmbeddings(it) }
            }
            val vectors = written.count { StoredEmbedding.markOf(it.vector) == null }
            embedded += vectors
            marked += written.size - vectors
        }
    }

    companion object {
        /** Posts per transaction. Embedding is fast; the writes are the cost, so they go in few, large transactions. */
        const val BATCH = 500

        /** What to store for a post: its vector, or the mark that says why it has none. */
        fun stored(embedder: StaticEmbedder, title: String, summary: String?, body: String?): ByteArray {
            val text = EmbeddableText.of(title, summary, body)
            if (EmbeddableText.isMostlyNonLatin(text)) return StoredEmbedding.mark(StoredEmbedding.NON_LATIN)
            val vector = embedder.embed(text) ?: return StoredEmbedding.mark(StoredEmbedding.NO_KNOWN_WORDS)
            return StaticEmbedder.toBytes(vector)
        }
    }
}
