package net.tgnra.morningissue.data.repository

import androidx.room3.withWriteTransaction
import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.tgnra.morningissue.data.db.ArticleContentEntity
import net.tgnra.morningissue.data.db.ArticleRow
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.ArticleSummary
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.ReaderArticle
import net.tgnra.morningissue.model.ReadingTime
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.model.SearchQuery

class RoomArticleRepository(
    private val database: MorningIssueDatabase,
    private val clock: Clock,
    private val config: RankingConfig = RankingConfig.Default,
) : ArticleRepository {
    private val articleDao = database.articleDao()
    private val engagementDao = database.engagementDao()

    override fun observeReaderArticle(articleId: Long): Flow<ReaderArticle?> =
        combine(articleDao.observeRow(articleId), articleDao.observeContent(articleId)) { row, content ->
            row?.toReaderArticle(content)
        }

    override suspend fun getReaderArticle(articleId: Long): ReaderArticle? = observeReaderArticle(articleId).first()

    override suspend fun markOpened(articleId: Long) = engagementDao.markOpened(articleId, clock.instant())

    override suspend fun setReadFraction(articleId: Long, fraction: Float) = database.withWriteTransaction {
        engagementDao.recordReadFraction(articleId, fraction)
        if (fraction >= config.readScrollFraction) engagementDao.markRead(articleId, clock.instant())
    }

    override suspend fun markRead(articleId: Long) = engagementDao.markRead(articleId, clock.instant())

    override suspend fun setLiked(articleId: Long, liked: Boolean) =
        engagementDao.setLiked(articleId, if (liked) clock.instant() else null)

    override suspend fun setSaved(articleId: Long, saved: Boolean) =
        engagementDao.setSaved(articleId, if (saved) clock.instant() else null)

    override fun observeSaved(): Flow<List<ArticleSummary>> = articleDao.observeSaved().map(::toSummaries)

    override fun observeLiked(): Flow<List<ArticleSummary>> = articleDao.observeLiked().map(::toSummaries)

    override fun observeFeedArticles(feedId: Long, limit: Int): Flow<List<ArticleSummary>> =
        articleDao.observeForFeed(feedId, limit).map(::toSummaries)

    override suspend fun search(query: SearchQuery): List<ArticleSummary> {
        val match = FtsQuery.fromUserInput(query.text) ?: return emptyList()
        return articleDao.search(
            match = match,
            feedId = query.feedId,
            tag = query.tag,
            from = query.from,
            to = query.to,
            likedOnly = query.likedOnly,
            savedOnly = query.savedOnly,
            limit = query.limit,
        ).map { it.row.toSummary(snippet = it.snippet) }
    }

    private fun toSummaries(rows: List<ArticleRow>): List<ArticleSummary> = rows.map { it.toSummary() }

    private fun ArticleRow.toReaderArticle(content: ArticleContentEntity?): ReaderArticle {
        val stored = content?.let { ContentBlocks.decode(it.blocksJson) }.orEmpty()
        val blocks = stored.ifEmpty {
            article.summary?.takeIf { it.isNotBlank() }
                ?.let { listOf<ContentBlock>(ContentBlock.Paragraph(RichText(it))) }
                .orEmpty()
        }
        return ReaderArticle(
            articleId = article.id,
            feedId = article.feedId,
            feedTitle = feedTitle,
            title = article.title,
            author = article.author,
            url = article.url,
            imageUrl = article.imageUrl,
            publishedAt = article.publishedAt,
            wordCount = article.wordCount,
            readMinutes = ReadingTime.minutes(article.wordCount),
            summary = article.summary,
            blocks = blocks,
            contentSource = content?.source?.takeIf { stored.isNotEmpty() },
            isLiked = likedAt != null,
            isSaved = savedAt != null,
            readFraction = readFraction ?: 0f,
        )
    }
}
