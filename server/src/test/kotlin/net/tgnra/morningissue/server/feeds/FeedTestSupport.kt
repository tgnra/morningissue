package net.tgnra.morningissue.server.feeds

import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import net.tgnra.morningissue.api.JobDto
import net.tgnra.morningissue.api.JobKind
import net.tgnra.morningissue.api.JobOutcomeDto
import net.tgnra.morningissue.api.ExtraRequest
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.server.db.ArticleEntity
import net.tgnra.morningissue.server.db.FeedEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.SubscriptionEntity
import net.tgnra.morningissue.server.service.JobProgress
import net.tgnra.morningissue.server.service.JobService
import net.tgnra.morningissue.server.service.JobStart

// Helpers for the feeds tests. Tables other workstreams own (users, engagement, editions) are
// written with plain SQL, since their DAOs are not this workstream's.

/** A fixture from core's test resources (`core/src/test/resources/sync/`); tests run in `server/`. */
object CoreFixtures {
    fun bytes(name: String): ByteArray = File("../core/src/test/resources/sync/$name").readBytes()
    fun text(name: String): String = String(bytes(name), Charsets.UTF_8)
}

suspend fun ServerDatabase.exec(vararg sql: String) = useWriterConnection { connection ->
    sql.forEach { connection.executeSQL(it) }
}

suspend fun ServerDatabase.long(sql: String): Long = useReaderConnection { connection ->
    connection.usePrepared(sql) { statement ->
        check(statement.step())
        statement.getLong(0)
    }
}

internal val T0: Instant = Instant.parse("2026-11-10T06:30:00Z")

/** An article row as the app's `TestData.article` builds it. */
internal fun article(
    feedId: Long,
    guid: String,
    title: String = "Title $guid",
    summary: String? = null,
    firstSeenAt: Instant = T0,
    publishedAt: Instant = firstSeenAt,
) = ArticleEntity(
    feedId = feedId, guid = guid, url = "https://example.com/$guid", title = title, summary = summary,
    publishedAt = publishedAt, firstSeenAt = firstSeenAt,
)

private fun ms(at: Instant?): String = at?.toEpochMilli()?.toString() ?: "NULL"

private fun quoted(text: String?): String = text?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"

/** Creates a user and returns its ID. */
suspend fun ServerDatabase.newUser(name: String): Long {
    exec(
        "INSERT INTO users (username, password_hash, db_id, created_at) VALUES " +
            "(${quoted(name)}, 'x', ${quoted(UUID.randomUUID().toString())}, 0)",
    )
    return long("SELECT id FROM users WHERE username = ${quoted(name)}")
}

/** A global feed row for [url]. */
fun feedEntity(url: String, title: String = "Feed", addedAt: Instant, siteUrl: String? = null) =
    FeedEntity(url = url, urlKey = FeedUrls.normalize(url), siteUrl = siteUrl, title = title, addedAt = addedAt)

/** Inserts a global feed and subscribes each of [userIds] to it, as of [addedAt]; returns the feed's ID. */
suspend fun ServerDatabase.feedFor(
    url: String,
    addedAt: Instant,
    vararg userIds: Long,
    title: String = "Feed",
    siteUrl: String? = null,
    status: FeedStatus = FeedStatus.SUBSCRIBED,
): Long {
    val id = feedDao().insert(feedEntity(url, title, addedAt, siteUrl))
    check(id > 0) { "feed $url exists" }
    for (user in userIds) subscribe(user, id, addedAt, status)
    return id
}

suspend fun ServerDatabase.subscribe(userId: Long, feedId: Long, addedAt: Instant, status: FeedStatus = FeedStatus.SUBSCRIBED) {
    subscriptionDao().insert(
        SubscriptionEntity(
            userId = userId, feedId = feedId, addedAt = addedAt, status = status,
            discoveredAt = addedAt.takeIf { status == FeedStatus.DISCOVERED },
        ),
    )
}

/** Writes (or replaces) the user's engagement row for an article. */
suspend fun ServerDatabase.engage(
    userId: Long,
    articleId: Long,
    openedAt: Instant? = null,
    readAt: Instant? = null,
    savedAt: Instant? = null,
    likedAt: Instant? = null,
) = exec(
    "INSERT OR REPLACE INTO engagement (user_id, articleId, openedAt, readFraction, readAt, savedAt, likedAt) VALUES " +
        "($userId, $articleId, ${ms(openedAt)}, ${if (readAt != null) 1 else 0}, ${ms(readAt)}, ${ms(savedAt)}, ${ms(likedAt)})",
)

/** A released MORNING edition of the user's with [articleIds] printed on page 1 (the first as the lead); returns its ID. */
suspend fun ServerDatabase.edition(userId: Long, release: Instant, number: Int, articleIds: List<Long>, date: LocalDate? = null): Long {
    val day = date ?: LocalDate.ofEpochDay(release.epochSecond / 86_400)
    exec(
        "INSERT INTO edition (user_id, date, kind, cutoffAt, releaseAt, builtAt, length, number, pageViewsTracked) VALUES " +
            "($userId, ${day.toEpochDay()}, 'MORNING', ${ms(release)}, ${ms(release)}, ${ms(release)}, 'STANDARD', $number, 0)",
    )
    val id = long("SELECT id FROM edition WHERE user_id = $userId AND date = ${day.toEpochDay()} AND kind = 'MORNING'")
    articleIds.forEachIndexed { slot, articleId ->
        val kind = if (slot == 0) ItemKind.LEAD else ItemKind.FRONT
        exec("INSERT INTO edition_item (editionId, articleId, page, slot, kind, score) VALUES ($id, $articleId, 1, $slot, '${kind.name}', 1.0)")
    }
    return id
}

/** A [JobService] that runs an OPML job at once and keeps its outcome. */
class InlineJobs : JobService {
    val outcomes = mutableListOf<JobOutcomeDto>()

    override suspend fun print(userId: Long): JobStart = error("not used")
    override suspend fun extra(userId: Long, request: ExtraRequest): JobStart = error("not used")
    override suspend fun job(userId: Long, jobId: String): JobDto = error("not used")
    override suspend fun start(userId: Long, kind: JobKind, work: suspend (JobProgress) -> JobOutcomeDto): JobStart {
        outcomes += work(JobProgress { _, _, _ -> })
        return JobStart("job-${outcomes.size}", alreadyRunning = false)
    }
}
