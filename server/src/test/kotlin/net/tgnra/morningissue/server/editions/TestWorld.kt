package net.tgnra.morningissue.server.editions

import androidx.room3.useWriterConnection
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import net.tgnra.morningissue.engine.EditionBuilder
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.engine.Scorer
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.ContentSource
import net.tgnra.morningissue.model.Edition
import net.tgnra.morningissue.model.EditionPage
import net.tgnra.morningissue.model.EditionStory
import net.tgnra.morningissue.model.FeedStatus
import net.tgnra.morningissue.model.FeedUrls
import net.tgnra.morningissue.model.IndexGroup
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.ReadingTime
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.server.db.ArticleEntity
import net.tgnra.morningissue.server.db.EditionEntity
import net.tgnra.morningissue.server.db.FeedEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.SubscriptionEntity
import net.tgnra.morningissue.server.db.UserItemRow

/** A clock the test moves; its zone is fixed, as the server's is (users' zones come from their settings). */
class TestClock(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = TestClock(now, zone)
}

/**
 * An in-memory server database with helpers to make users, devices, feeds, subscriptions and
 * posts, and to do what a reader does. The account and feed tables belong to other
 * workstreams; users and devices are inserted with plain SQL, feeds and posts through
 * `ImportDao`.
 */
class TestWorld(start: Instant, val defaultZone: ZoneId = ZoneOffset.UTC) {
    val clock = TestClock(start)
    val db: ServerDatabase = ServerDatabase.inMemory()
    val settings = UserSettingsStore(db, clock, defaultZone)
    private var guid = 0
    private var users = 0

    fun close() = db.close()

    suspend fun exec(vararg sql: String) = db.useWriterConnection { connection ->
        sql.forEach { connection.usePrepared(it) { statement -> statement.step() } }
    }

    /** A new enabled user; returns its ID. */
    suspend fun addUser(name: String = "user${++users}", dbId: String = UUID.randomUUID().toString()): Long {
        exec(
            "INSERT INTO users (username, password_hash, must_change_password, disabled, db_id, created_at, change_seq) " +
                "VALUES ('$name', 'x', 0, 0, '$dbId', 0, 0)",
        )
        return db.useWriterConnection { c -> c.usePrepared("SELECT id FROM users WHERE username = '$name'") { it.step(); it.getLong(0) } }
    }

    suspend fun disableUser(userId: Long) = exec("UPDATE users SET disabled = 1 WHERE id = $userId")

    /** A device of [userId]; a Kobo when [width] is given. */
    suspend fun addDevice(userId: Long, id: String = UUID.randomUUID().toString(), width: Int? = null, ackedSeq: Long = 0): String {
        val kind = if (width != null) "KOBO" else "PHONE"
        exec(
            "INSERT INTO device (id, user_id, kind, name, width, token_hash, acked_seq, created_at) " +
                "VALUES ('$id', $userId, '$kind', 'Device', ${width ?: "NULL"}, '${UUID.randomUUID()}', $ackedSeq, 0)",
        )
        return id
    }

    /** A global feed; returns its ID. */
    suspend fun addGlobalFeed(
        title: String,
        tier: CadenceTier = CadenceTier.WEEKLY,
        medianHours: Double? = 168.0,
        url: String = "https://example.com/${title.replace(' ', '-')}.xml",
        lastAttemptAt: Instant? = null,
    ): Long = db.importDao().insertFeed(
        FeedEntity(
            url = url,
            urlKey = FeedUrls.normalize(url),
            title = title,
            tier = tier,
            medianIntervalHrs = medianHours,
            addedAt = clock.now,
            lastAttemptAt = lastAttemptAt,
        ),
    )

    /** [userId] subscribes to [feedId]. */
    suspend fun subscribe(
        userId: Long,
        feedId: Long,
        addedAt: Instant = clock.now.minus(Duration.ofDays(30)),
        status: FeedStatus = FeedStatus.SUBSCRIBED,
        muteUntil: Instant? = null,
        sliderStep: SliderStep = SliderStep.AUTO,
        tags: List<String> = emptyList(),
        affinity: Double = 0.5,
    ) {
        db.importDao().insertSubscription(
            SubscriptionEntity(
                userId = userId,
                feedId = feedId,
                sliderStep = sliderStep,
                muteUntil = muteUntil,
                affinity = affinity,
                status = status,
                addedAt = addedAt,
                discoveredAt = if (status == FeedStatus.DISCOVERED) addedAt else null,
            ),
        )
        if (tags.isNotEmpty()) db.importDao().addSubscriptionTags(userId, feedId, tags)
    }

    /** A global feed [userId] subscribes to; returns its ID. */
    suspend fun addFeed(
        userId: Long,
        title: String,
        tier: CadenceTier = CadenceTier.WEEKLY,
        medianHours: Double? = 168.0,
        muteUntil: Instant? = null,
        addedAt: Instant = clock.now.minus(Duration.ofDays(30)),
        status: FeedStatus = FeedStatus.SUBSCRIBED,
        sliderStep: SliderStep = SliderStep.AUTO,
        tags: List<String> = emptyList(),
    ): Long {
        val feedId = addGlobalFeed(title, tier, medianHours)
        subscribe(userId, feedId, addedAt, status, muteUntil, sliderStep, tags)
        return feedId
    }

    /** A post of [feedId]; returns its ID. */
    suspend fun addPost(
        feedId: Long,
        firstSeenAt: Instant,
        publishedAt: Instant = firstSeenAt,
        imageUrl: String? = null,
        title: String? = null,
        url: String? = null,
        wordCount: Int = 0,
        feedWordCount: Int = 0,
        blocksJson: String? = null,
        plainText: String? = null,
    ): Long {
        val id = "post-${guid++}"
        return db.importDao().insertNew(
            ArticleEntity(
                feedId = feedId,
                guid = id,
                url = url ?: "https://example.com/$id",
                title = title ?: "Title $id",
                imageUrl = imageUrl,
                publishedAt = publishedAt,
                firstSeenAt = firstSeenAt,
                wordCount = wordCount,
                feedWordCount = feedWordCount,
            ),
            blocksJson = blocksJson,
            plainText = plainText,
        )!!
    }

    /** Stores a body for [articleId], as full-text fetching would. */
    suspend fun storeBody(articleId: Long, blocksJson: String, text: String, words: Int, source: ContentSource = ContentSource.FETCHED) =
        db.importDao().storeContent(articleId, blocksJson, text, words, source, clock.now)

    // --- What a reader does -------------------------------------------------------------------

    private suspend fun seq(userId: Long) = db.userStateDao().nextChangeSeq(userId)

    suspend fun open(userId: Long, articleId: Long, at: Instant = clock.now) = db.engagementDao().mergeOpened(userId, articleId, at, seq(userId))
    suspend fun read(userId: Long, articleId: Long, at: Instant = clock.now) = db.engagementDao().mergeRead(userId, articleId, at, seq(userId))
    suspend fun like(userId: Long, articleId: Long, at: Instant? = clock.now) = db.engagementDao().setLiked(userId, articleId, at, seq(userId))
    suspend fun save(userId: Long, articleId: Long, at: Instant? = clock.now) = db.engagementDao().setSaved(userId, articleId, at, seq(userId))
    suspend fun readFraction(userId: Long, articleId: Long, fraction: Float) =
        db.engagementDao().recordReadFraction(userId, articleId, fraction, seq(userId))

    suspend fun openEdition(userId: Long, editionId: Long, at: Instant = clock.now) = db.editionDao().mergeOpened(userId, editionId, at, seq(userId))
    suspend fun finishEdition(userId: Long, editionId: Long, at: Instant = clock.now) = db.editionDao().mergeFinished(userId, editionId, at, seq(userId))

    /**
     * The reader turns through [editionId], leaving each page on screen long enough to count as
     * seen (`pageSeenMinMillis`), except the pages in [skipping].
     */
    suspend fun viewPages(userId: Long, editionId: Long, at: Instant = clock.now, skipping: Set<Int> = emptySet()) {
        db.editionDao().getItems(userId, editionId).map { it.item.page }.distinct().filter { it !in skipping }
            .forEach { db.editionDao().recordPageView(userId, editionId, it, RankingConfig.Default.pageSeenMinMillis, at) }
    }

    suspend fun edition(userId: Long, editionId: Long): EditionEntity? = db.editionDao().getById(userId, editionId)

    suspend fun articleIdsOf(userId: Long, editionId: Long): Set<Long> = db.editionDao().getItems(userId, editionId).map { it.item.articleId }.toSet()

    /** The edition as the page model, as the app's `EditionPages` turns it. */
    suspend fun pages(userId: Long, editionId: Long): Edition {
        val edition = db.editionDao().getById(userId, editionId)!!
        return EditionPages.toEdition(edition, db.editionDao().getItems(userId, editionId))
    }
}

/** The app's `EditionPages`, over the server's rows: stored items into the page model. */
object EditionPages {
    fun toEdition(edition: EditionEntity, items: List<UserItemRow>): Edition {
        val printed = items.filter { it.item.kind != ItemKind.INDEX }
        val index = items.filter { it.item.kind == ItemKind.INDEX }
        val pages = mutableListOf<EditionPage>()
        printed.groupBy { it.item.page }.toSortedMap().values.forEach { rows ->
            val sorted = rows.sortedBy { it.item.slot }
            val stories = sorted.map { it.toStory() }
            val number = pages.size + 1
            pages += when (stories.first().kind) {
                ItemKind.LEAD, ItemKind.FRONT -> {
                    val lead = stories.firstOrNull { it.kind == ItemKind.LEAD }
                    EditionPage.Front(number, lead, stories.filter { it !== lead })
                }
                ItemKind.TOP -> EditionPage.TopStories(number, stories)
                ItemKind.SECTION -> EditionPage.Section(number, sorted.first().item.section ?: EditionBuilder.GENERAL_SECTION, stories)
                ItemKind.BRIEF -> EditionPage.InBrief(number, stories)
                ItemKind.ARCHIVE -> EditionPage.FromTheArchive(number, stories)
                ItemKind.AWAY -> EditionPage.WhileYouWereAway(number, stories)
                ItemKind.READING_LIST -> EditionPage.FromYourReadingList(number, stories)
                ItemKind.INDEX -> error("index items are filtered out above")
            }
        }
        val groups = LinkedHashMap<Long, MutableList<EditionStory>>()
        index.sortedBy { it.item.slot }.forEach { row -> groups.getOrPut(row.row.article.feedId) { mutableListOf() } += row.toStory() }
        pages += EditionPage.Back(pages.size + 1, groups.map { (feedId, stories) -> IndexGroup(feedId, stories.first().feedTitle, stories) }, null)
        return Edition(
            id = edition.id,
            date = edition.date,
            kind = edition.kind,
            number = edition.number,
            length = edition.length,
            cutoffAt = edition.cutoffAt,
            releaseAt = edition.releaseAt,
            builtAt = edition.builtAt,
            pages = pages,
            readMinutes = printed.filter { it.item.kind != ItemKind.BRIEF }.sumOf { it.toStory().readMinutes },
            isRead = edition.finishedAt != null,
        )
    }

    private fun UserItemRow.toStory() = EditionStory(
        articleId = row.article.id,
        feedId = row.article.feedId,
        feedTitle = row.feedTitle,
        feedIconUrl = row.feedIconUrl,
        title = row.article.title,
        summary = row.article.summary,
        imageUrl = row.article.imageUrl,
        author = row.article.author,
        url = row.article.url,
        publishedAt = row.article.publishedAt,
        readMinutes = ReadingTime.minutes(row.article.wordCount),
        kind = item.kind,
        slot = item.slot,
        score = item.score,
        reason = item.reason,
        isOpened = row.openedAt != null,
        isRead = row.readAt != null,
        isLiked = row.likedAt != null,
        isSaved = row.savedAt != null,
        comebackLabel = Scorer.comebackLabelOf(item.reason),
        feedSubscribed = row.feedStatus == FeedStatus.SUBSCRIBED,
    )
}
