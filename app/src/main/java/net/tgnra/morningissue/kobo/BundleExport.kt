package net.tgnra.morningissue.kobo

import androidx.room3.withReadTransaction
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.tgnra.morningissue.data.db.ArticleRow
import net.tgnra.morningissue.data.db.ArticleSignalDao
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemRow
import net.tgnra.morningissue.data.db.MorningIssueDatabase

/**
 * Builds what a Kobo downloads: the manifest, an edition's bundle and the saved list (`docs/kobo-sync.md`).
 * Pure; [KoboExporter] reads the database and hands the rows in.
 */
object BundleExport {
    /** An instant on the wire: ISO-8601 in UTC, to the second. */
    fun time(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()

    /**
     * An article's stored blocks as a JSON value, embedded as they are rather than decoded and
     * encoded again, so the Kobo sees exactly the `ContentBlocks` format. `[]` when there is no
     * stored text, or when what is stored is not a JSON array.
     */
    fun blocks(stored: String?): JsonElement {
        if (stored.isNullOrBlank()) return JsonArray(emptyList())
        return runCatching { Json.parseToJsonElement(stored) }.getOrNull() as? JsonArray ?: JsonArray(emptyList())
    }

    /**
     * The bundle of [edition]: its items by page then slot, and every article they name with
     * its stored blocks ([blocksJson], by article ID) and the phone's engagement.
     */
    fun edition(edition: EditionEntity, items: List<EditionItemRow>, blocksJson: Map<Long, String>): EditionBundle {
        val sorted = items.sortedWith(compareBy({ it.item.page }, { it.item.slot }))
        val pages = sorted.groupBy { it.item.page }.map { (page, onPage) ->
            BundlePage(
                page = page,
                items = onPage.map { BundleItem(it.item.articleId, it.item.slot, it.item.kind.name, it.item.section, it.item.reason) },
            )
        }
        val articles = sorted.associate { entry -> entry.row.article.id.toString() to article(entry.row, blocksJson[entry.row.article.id]) }
        return EditionBundle(
            id = edition.id,
            date = edition.date.toString(),
            kind = edition.kind.name,
            number = edition.number,
            length = edition.length.name,
            releaseAt = time(edition.releaseAt),
            finishedAt = edition.finishedAt?.let(::time),
            pages = pages,
            articles = articles,
        )
    }

    /** One article as a Kobo gets it, in an edition or in the saved list: [blocksJson] is its stored body, if any. */
    fun article(row: ArticleRow, blocksJson: String?): BundleArticle {
        val article = row.article
        return BundleArticle(
            feedTitle = row.feedTitle,
            title = article.title,
            author = article.author,
            url = article.url,
            publishedAt = time(article.publishedAt),
            summary = article.summary,
            imageUrl = article.imageUrl,
            wordCount = article.wordCount,
            blocks = blocks(blocksJson),
            engagement = BundleEngagement(
                openedAt = row.openedAt?.let(::time),
                readFraction = row.readFraction ?: 0f,
                readAt = row.readAt?.let(::time),
                savedAt = row.savedAt?.let(::time),
                likedAt = row.likedAt?.let(::time),
            ),
        )
    }

    /**
     * The saved list: [rows] as the database gives them, newest saved first, with their stored
     * bodies. Its `version` is the hash of the list encoded with an empty version, so it
     * changes exactly when the list does.
     */
    fun saved(rows: List<ArticleRow>, blocksJson: Map<Long, String>): SavedBundle {
        val unversioned = SavedBundle(
            version = "",
            order = rows.map { it.article.id },
            articles = rows.associate { it.article.id.toString() to article(it, blocksJson[it.article.id]) },
        )
        return unversioned.copy(version = version(KoboProtocol.json.encodeToString(unversioned)))
    }

    /**
     * Every image a Kobo fetches for [articles]: each lead `imageUrl` and every `img` block's
     * `url`, in order of first appearance, without repeats. The Kobo fetches the same list
     * (`kobo/src/model.rs`), so this is what the phone prepares ahead of it.
     */
    fun imageUrls(articles: Collection<BundleArticle>): List<String> {
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

    fun encode(bundle: EditionBundle): String = KoboProtocol.json.encodeToString(bundle)

    /** The bundle's `version` in the manifest: the first 16 bytes of the SHA-256 of its bytes, in hex. */
    fun version(encoded: String): String =
        MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    /** The manifest entry for [edition], whose bundle encodes to [encoded]. */
    fun manifestEntry(edition: EditionEntity, encoded: String) = ManifestEdition(
        id = edition.id,
        date = edition.date.toString(),
        kind = edition.kind.name,
        number = edition.number,
        releaseAt = time(edition.releaseAt),
        version = version(encoded),
    )
}

/** Reads the database for [BundleExport]. Only ever exports editions released by now. */
class KoboExporter(private val database: MorningIssueDatabase, private val clock: Clock) {
    /** The manifest for a device whose merged events reach [ackedSeq]. */
    suspend fun manifest(dbId: String, ackedSeq: Long): Manifest = database.withReadTransaction {
        val editions = database.editionDao().getRecentReleased(clock.instant(), KoboProtocol.EDITIONS_OFFERED)
        val saved = savedBundle()
        Manifest(
            dbId = dbId,
            ackedSeq = ackedSeq,
            editions = editions.map { BundleExport.manifestEntry(it, BundleExport.encode(bundle(it))) },
            saved = ManifestSaved(count = saved.order.size, version = saved.version),
        )
    }

    /**
     * Everything a relay package carries, read in one transaction so that the manifest and the
     * bodies agree: the manifest (its `ackedSeq` left at 0, since a package has one per
     * device), each offered edition's encoded bundle by ID, the encoded saved list, and every
     * article in them, for the images.
     */
    suspend fun snapshot(dbId: String): Snapshot = database.withReadTransaction {
        val editions = database.editionDao().getRecentReleased(clock.instant(), KoboProtocol.EDITIONS_OFFERED)
        val bundles = editions.map { it to bundle(it) }
        val encoded = bundles.map { (edition, bundle) -> edition to BundleExport.encode(bundle) }
        val saved = savedBundle()
        Snapshot(
            manifest = Manifest(
                dbId = dbId,
                ackedSeq = 0,
                editions = encoded.map { (edition, text) -> BundleExport.manifestEntry(edition, text) },
                saved = ManifestSaved(count = saved.order.size, version = saved.version),
            ),
            editions = encoded.map { (edition, text) -> edition.id to text },
            saved = KoboProtocol.json.encodeToString(saved),
            articles = bundles.flatMap { it.second.articles.values } + saved.articles.values,
        )
    }

    class Snapshot(
        val manifest: Manifest,
        /** Edition ID to its encoded bundle, as `GET /v1/editions/{id}` sends it. */
        val editions: List<Pair<Long, String>>,
        /** As `GET /v1/saved` sends it. */
        val saved: String,
        val articles: List<BundleArticle>,
    )

    /** `GET /v1/saved`, encoded. */
    suspend fun saved(): String = KoboProtocol.json.encodeToString(database.withReadTransaction { savedBundle() })

    /** The editions the manifest offers and the saved list, for the pre-warm: every article a Kobo can be sent. */
    suspend fun offeredArticles(): Offered = database.withReadTransaction {
        val editions = database.editionDao().getRecentReleased(clock.instant(), KoboProtocol.EDITIONS_OFFERED).map { bundle(it) }
        Offered(editions.flatMap { it.articles.values }, savedBundle().articles.values.toList())
    }

    class Offered(val editions: List<BundleArticle>, val saved: List<BundleArticle>)

    private suspend fun savedBundle(): SavedBundle {
        val rows = database.articleDao().getSaved()
        return BundleExport.saved(rows, contents(rows.map { it.article.id }))
    }

    private suspend fun contents(articleIds: List<Long>): Map<Long, String> =
        articleIds.distinct().chunked(ArticleSignalDao.MAX_IDS)
            .flatMap { database.archiveDao().contents(it) }
            .associate { it.articleId to it.blocksJson }

    /** The encoded bundle of edition [id], or null when there is none or it is not released yet. */
    suspend fun edition(id: Long): String? = database.withReadTransaction {
        val edition = database.editionDao().getById(id)?.takeIf { !it.releaseAt.isAfter(clock.instant()) }
        edition?.let { BundleExport.encode(bundle(it)) }
    }

    private suspend fun bundle(edition: EditionEntity): EditionBundle {
        val items = database.editionDao().getItems(edition.id)
        return BundleExport.edition(edition, items, contents(items.map { it.item.articleId }))
    }
}
