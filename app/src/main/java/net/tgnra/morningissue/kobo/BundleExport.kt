package net.tgnra.morningissue.kobo

import androidx.room3.withReadTransaction
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import net.tgnra.morningissue.data.db.ArticleSignalDao
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemRow
import net.tgnra.morningissue.data.db.MorningIssueDatabase

/**
 * Builds what a Kobo downloads: the manifest and an edition's bundle (`docs/kobo-sync.md`).
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
        val articles = sorted.associate { entry ->
            val row = entry.row
            val article = row.article
            article.id.toString() to BundleArticle(
                feedTitle = row.feedTitle,
                title = article.title,
                author = article.author,
                url = article.url,
                publishedAt = time(article.publishedAt),
                summary = article.summary,
                imageUrl = article.imageUrl,
                wordCount = article.wordCount,
                blocks = blocks(blocksJson[article.id]),
                engagement = BundleEngagement(
                    openedAt = row.openedAt?.let(::time),
                    readFraction = row.readFraction ?: 0f,
                    readAt = row.readAt?.let(::time),
                    savedAt = row.savedAt?.let(::time),
                    likedAt = row.likedAt?.let(::time),
                ),
            )
        }
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
        Manifest(
            dbId = dbId,
            ackedSeq = ackedSeq,
            editions = editions.map { BundleExport.manifestEntry(it, BundleExport.encode(bundle(it))) },
        )
    }

    /** The encoded bundle of edition [id], or null when there is none or it is not released yet. */
    suspend fun edition(id: Long): String? = database.withReadTransaction {
        val edition = database.editionDao().getById(id)?.takeIf { !it.releaseAt.isAfter(clock.instant()) }
        edition?.let { BundleExport.encode(bundle(it)) }
    }

    private suspend fun bundle(edition: EditionEntity): EditionBundle {
        val items = database.editionDao().getItems(edition.id)
        val blocks = items.map { it.item.articleId }.distinct().chunked(ArticleSignalDao.MAX_IDS)
            .flatMap { database.archiveDao().contents(it) }
            .associate { it.articleId to it.blocksJson }
        return BundleExport.edition(edition, items, blocks)
    }
}
