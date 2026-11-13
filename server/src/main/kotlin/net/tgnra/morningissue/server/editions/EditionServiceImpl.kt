package net.tgnra.morningissue.server.editions

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.YearMonth
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.tgnra.morningissue.api.DeskNoticeResponse
import net.tgnra.morningissue.api.EditionBundleDto
import net.tgnra.morningissue.api.EditionStateDto
import net.tgnra.morningissue.api.EditionSummaryDto
import net.tgnra.morningissue.api.EditionsResponse
import net.tgnra.morningissue.api.EngagementDeltaResponse
import net.tgnra.morningissue.api.EngagementDto
import net.tgnra.morningissue.api.SavedBundleDto
import net.tgnra.morningissue.engine.DeskNoticeRule
import net.tgnra.morningissue.engine.FeedHealthRules
import net.tgnra.morningissue.model.EditionSummary
import net.tgnra.morningissue.server.db.EditionEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.UserCoverRow
import net.tgnra.morningissue.server.db.UserStateEntity
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.EditionService

/** Editions, the saved shelf, the engagement delta and the desk notice (4.3). */
class EditionServiceImpl(
    private val database: ServerDatabase,
    private val bundles: EditionBundles,
    private val settings: UserSettingsStore,
    private val clock: Clock,
) : EditionService {
    private val editionDao = database.editionDao()
    private val engagementDao = database.engagementDao()
    private val userStateDao = database.userStateDao()
    private val articleDao = database.editionArticleDao()

    override suspend fun editions(userId: Long, before: Int?, limit: Int): EditionsResponse {
        val rows = editionDao.releasedCovers(userId, clock.instant(), before ?: Int.MAX_VALUE, limit.coerceIn(1, MAX_PAGE))
        return EditionsResponse(rows.map { it.toDto() })
    }

    override suspend fun bundle(userId: Long, editionId: Long): EditionBundleDto = bundles.bundle(userId, editionId)

    override suspend fun saved(userId: Long): SavedBundleDto = bundles.saved(userId)

    override suspend fun engagement(userId: Long, since: Long): EngagementDeltaResponse = database.withReadTransaction {
        val cursor = userStateDao.changeSeq(userId)
        EngagementDeltaResponse(
            cursor = cursor,
            engagement = engagementDao.changedSince(userId, since).map {
                EngagementDto(it.articleId, it.openedAt, it.readFraction, it.readAt, it.savedAt, it.likedAt)
            },
            editions = editionDao.changedSince(userId, since).map { EditionStateDto(it.id, it.openedAt, it.finishedAt) },
        )
    }

    /**
     * The month rule of the app's `DefaultFeedHealthRepository.deskNotice`, per user: the count
     * of the user's feeds needing attention, shown on one edition a month (the newest released
     * one when the month's first is asked for), claimed in `user_state`.
     */
    override suspend fun deskNotice(userId: Long, editionId: Long): DeskNoticeResponse = database.withWriteTransaction {
        val now = clock.instant()
        val edition = editionDao.getById(userId, editionId)?.takeIf { !it.releaseAt.isAfter(now) }
            ?: throw ApiFailure.notFound("No such edition")
        val needing = articleDao.flags(userId).count { row ->
            FeedHealthRules.visibleFlag(row.flag, row.dismissedUntil, now)?.let(FeedHealthRules::needsAttention) == true
        }
        val latest = editionDao.getLatestReleased(userId, now)?.id == editionId
        val today = now.atZone(settings.get(userId).zone).toLocalDate()
        val claim = userStateDao.get(userId, DESK_NOTICE_KEY)?.let { runCatching { json.decodeFromString(StoredClaim.serializer(), it) }.getOrNull() }
            ?.let { DeskNoticeRule.Claim(YearMonth.parse(it.month), it.editionId) }
        val decision = DeskNoticeRule.decide(editionId, edition.date, latest, today, needing, claim)
        if (decision == DeskNoticeRule.Decision.CLAIM) {
            val stored = StoredClaim(YearMonth.from(today).toString(), editionId)
            userStateDao.put(UserStateEntity(userId, DESK_NOTICE_KEY, json.encodeToString(StoredClaim.serializer(), stored)))
        }
        DeskNoticeResponse(needing.takeIf { decision != DeskNoticeRule.Decision.HIDE })
    }

    @Serializable
    private data class StoredClaim(val month: String, val editionId: Long)

    companion object {
        const val DESK_NOTICE_KEY = "deskNotice"
        private const val MAX_PAGE = 100
        private val json = Json { ignoreUnknownKeys = true }

        fun UserCoverRow.toDto() = EditionSummaryDto(
            id = edition.id,
            date = edition.date,
            kind = edition.kind,
            number = edition.number,
            length = edition.length,
            releaseAt = edition.releaseAt,
            cutoffAt = edition.cutoffAt,
            builtAt = edition.builtAt,
            openedAt = edition.openedAt,
            finishedAt = edition.finishedAt,
            pageViewsTracked = edition.pageViewsTracked,
            leadTitle = leadTitle,
            leadImageUrl = leadImageUrl,
            leadSource = leadFeedTitle,
            storyCount = storyCount,
        )

        /** What `DueEditions` needs of a released edition; it reads kind and date only. */
        fun EditionEntity.toSummary() = EditionSummary(
            id = id,
            date = date,
            kind = kind,
            number = number,
            length = length,
            releaseAt = releaseAt,
            cutoffAt = cutoffAt,
            leadHeadline = null,
            leadImageUrl = null,
            leadSource = null,
            storyCount = 0,
            isRead = finishedAt != null,
        )
    }
}
