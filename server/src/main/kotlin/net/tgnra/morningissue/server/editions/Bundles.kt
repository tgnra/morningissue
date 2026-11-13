package net.tgnra.morningissue.server.editions

import androidx.room3.withReadTransaction
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.tgnra.morningissue.api.ApiJson
import net.tgnra.morningissue.api.BundleArticleDto
import net.tgnra.morningissue.api.BundleEngagementDto
import net.tgnra.morningissue.api.BundleItemDto
import net.tgnra.morningissue.api.BundlePageDto
import net.tgnra.morningissue.api.EditionBundleDto
import net.tgnra.morningissue.api.PageViewDto
import net.tgnra.morningissue.api.SavedBundleDto
import net.tgnra.morningissue.server.db.BundleKeyRow
import net.tgnra.morningissue.server.db.EditionArticleDao
import net.tgnra.morningissue.server.db.EditionEntity
import net.tgnra.morningissue.server.db.EditionPageViewEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.db.StoredBodyRow
import net.tgnra.morningissue.server.db.UserArticleRow
import net.tgnra.morningissue.server.db.UserItemRow
import net.tgnra.morningissue.server.service.ApiFailure

/**
 * The `docs/kobo-sync.md` edition bundle and saved shelf, with the fields the phone reads added
 * (4.3): the app's `BundleExport`, building the API's DTOs. Pure; [EditionBundles] reads the
 * database and hands the rows in.
 */
object BundleExport {
    /**
     * An article's stored blocks as a JSON value, embedded as they are, so a client sees
     * exactly the `ContentBlocks` format. `[]` when there is no stored text, or when what is
     * stored is not a JSON array.
     */
    fun blocks(stored: String?): JsonElement {
        if (stored.isNullOrBlank()) return JsonArray(emptyList())
        return runCatching { Json.parseToJsonElement(stored) }.getOrNull() as? JsonArray ?: JsonArray(emptyList())
    }

    /** The bundle of [edition]: its items by page then slot, and every article they name. */
    fun edition(
        edition: EditionEntity,
        items: List<UserItemRow>,
        bodies: Map<Long, StoredBodyRow>,
        pageViews: List<EditionPageViewEntity>,
    ): EditionBundleDto {
        val sorted = items.sortedWith(compareBy({ it.item.page }, { it.item.slot }))
        val pages = sorted.groupBy { it.item.page }.map { (page, onPage) ->
            BundlePageDto(
                page = page,
                items = onPage.map { BundleItemDto(it.item.articleId, it.item.slot, it.item.kind, it.item.section, it.item.reason) },
            )
        }
        val articles = sorted.associate { entry -> entry.row.article.id.toString() to article(entry.row, bodies[entry.row.article.id]) }
        return EditionBundleDto(
            id = edition.id,
            date = edition.date,
            kind = edition.kind,
            number = edition.number,
            length = edition.length,
            releaseAt = edition.releaseAt,
            finishedAt = edition.finishedAt,
            pages = pages,
            articles = articles,
            cutoffAt = edition.cutoffAt,
            builtAt = edition.builtAt,
            openedAt = edition.openedAt,
            pageViewsTracked = edition.pageViewsTracked,
            pageViews = pageViews.map { PageViewDto(it.page, it.msVisible, it.firstSeenAt) },
        )
    }

    /** One article as a client gets it, in an edition or on the saved shelf. */
    fun article(row: UserArticleRow, body: StoredBodyRow?): BundleArticleDto {
        val article = row.article
        return BundleArticleDto(
            feedTitle = row.feedTitle,
            title = article.title,
            author = article.author,
            url = article.url,
            publishedAt = article.publishedAt,
            summary = article.summary,
            imageUrl = article.imageUrl,
            wordCount = article.wordCount,
            blocks = blocks(body?.blocksJson),
            engagement = BundleEngagementDto(
                openedAt = row.openedAt,
                readFraction = row.readFraction ?: 0f,
                readAt = row.readAt,
                savedAt = row.savedAt,
                likedAt = row.likedAt,
            ),
            feedId = article.feedId,
            guid = article.guid,
            firstSeenAt = row.seenAt,
            feedWordCount = article.feedWordCount,
            contentSource = body?.source,
        )
    }

    /**
     * The saved shelf: [rows] newest saved first, with their bodies. Its `version` is the hash
     * of the shelf encoded with an empty version, so it changes exactly when the shelf does.
     */
    fun saved(rows: List<UserArticleRow>, bodies: Map<Long, StoredBodyRow>): SavedBundleDto {
        val unversioned = SavedBundleDto(
            version = "",
            order = rows.map { it.article.id },
            articles = rows.associate { it.article.id.toString() to article(it, bodies[it.article.id]) },
        )
        return unversioned.copy(version = version(encode(unversioned)))
    }

    /**
     * Every picture a Kobo fetches for [articles]: each lead `imageUrl` and every `img`
     * block's `url`, in order of first appearance, without repeats (`kobo/src/model.rs`).
     */
    fun imageUrls(articles: Collection<BundleArticleDto>): List<String> {
        val urls = LinkedHashSet<String>()
        for (article in articles) {
            article.imageUrl?.takeIf { it.isNotEmpty() }?.let(urls::add)
            for (block in (article.blocks as? JsonArray).orEmpty()) {
                val obj = block as? JsonObject ?: continue
                if ((obj["t"] as? JsonPrimitive)?.contentOrNull != "img") continue
                (obj["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let(urls::add)
            }
        }
        return urls.toList()
    }

    fun encode(bundle: EditionBundleDto): String = ApiJson.json.encodeToString(EditionBundleDto.serializer(), bundle)

    fun encode(saved: SavedBundleDto): String = ApiJson.json.encodeToString(SavedBundleDto.serializer(), saved)

    /** A manifest `version`: the SHA-256 of the encoded bytes, in hex. */
    fun version(encoded: String): String =
        MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * Reads the database for [BundleExport], per user: bundles of released editions only, the saved
 * shelf, and the manifest's versions, with each edition's version cached by what its bundle
 * depends on (`EditionDao.bundleKey`).
 */
class EditionBundles(
    private val database: ServerDatabase,
    private val clock: Clock,
) {
    private val editionDao = database.editionDao()
    private val articleDao = database.editionArticleDao()

    private class Cached(val key: BundleKeyRow, val version: String)

    private val versions = ConcurrentHashMap<Long, Cached>()

    /** The bundle of the user's released edition [editionId]; [ApiFailure] `not_found` otherwise. */
    suspend fun bundle(userId: Long, editionId: Long): EditionBundleDto = database.withReadTransaction {
        val edition = editionDao.getById(userId, editionId)?.takeIf { !it.releaseAt.isAfter(clock.instant()) }
            ?: throw ApiFailure.notFound("No such edition")
        bundleOf(userId, edition)
    }

    /** The bundle of [edition], released or not: for work after a build (warming Kobo pictures). */
    suspend fun bundleUnreleased(userId: Long, edition: EditionEntity): EditionBundleDto =
        database.withReadTransaction { bundleOf(userId, edition) }

    /** The saved shelf. */
    suspend fun saved(userId: Long): SavedBundleDto = database.withReadTransaction {
        val rows = articleDao.saved(userId)
        BundleExport.saved(rows, bodies(rows.map { it.article.id }))
    }

    /** The manifest `version` of [edition], one of the user's. */
    suspend fun version(userId: Long, edition: EditionEntity): String {
        val key = editionDao.bundleKey(userId, edition.id) ?: return BundleExport.version(BundleExport.encode(bundleOf(userId, edition)))
        versions[edition.id]?.takeIf { it.key == key }?.let { return it.version }
        val version = database.withReadTransaction {
            // Read again inside the transaction, so the key matches the bundle hashed.
            val current = editionDao.bundleKey(userId, edition.id) ?: key
            val fresh = editionDao.getById(userId, edition.id) ?: edition
            Cached(current, BundleExport.version(BundleExport.encode(bundleOf(userId, fresh))))
        }
        versions[edition.id] = version
        return version.version
    }

    private suspend fun bundleOf(userId: Long, edition: EditionEntity): EditionBundleDto {
        val items = editionDao.getItems(userId, edition.id)
        return BundleExport.edition(edition, items, bodies(items.map { it.item.articleId }), editionDao.getPageViews(userId, edition.id))
    }

    private suspend fun bodies(articleIds: List<Long>): Map<Long, StoredBodyRow> =
        articleIds.distinct().chunked(EditionArticleDao.MAX_IDS).flatMap { articleDao.contents(it) }.associateBy { it.articleId }
}
