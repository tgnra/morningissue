package net.tgnra.morningissue.server.feeds

import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Clock
import net.tgnra.morningissue.api.OpmlReportDto
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.sync.Opml
import net.tgnra.morningissue.sync.OpmlDocument
import net.tgnra.morningissue.sync.OpmlFeed

/**
 * OPML import and export of one user's subscriptions, ported from the app's
 * `DefaultOpmlTransfer`: [Opml] for the format, [Subscriptions] for the feeds. Importing does
 * not fetch the new feeds; the next refresh does.
 */
class OpmlTransfer(
    database: ServerDatabase,
    private val subscriptions: Subscriptions,
    private val clock: Clock,
) {
    private val subscriptionDao = database.subscriptionDao()

    /** Reads an OPML document; throws [IOException] when it is not one. */
    fun read(bytes: ByteArray): OpmlDocument = Opml.read(ByteArrayInputStream(bytes))

    /**
     * Subscribes [userId] to every feed of [document] not already subscribed (matched on the
     * normalised URL), folder names becoming tags, and reports the counts.
     */
    suspend fun import(userId: Long, document: OpmlDocument, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): OpmlReportDto {
        var added = 0
        var skipped = 0
        document.feeds.forEachIndexed { index, feed ->
            val result = subscriptions.add(userId, url = feed.url, title = feed.title, siteUrl = feed.siteUrl, tags = feed.tags)
            if (result.added == Subscriptions.Added.ALREADY) skipped++ else added++
            progress(index + 1, document.feeds.size)
        }
        return OpmlReportDto(added = added, skipped = skipped)
    }

    /** [read] then [import]. */
    suspend fun import(userId: Long, bytes: ByteArray): OpmlReportDto = import(userId, read(bytes))

    /** The user's subscribed feeds as OPML 2.0, one folder per tag. */
    suspend fun export(userId: Long): String {
        val tags = subscriptionDao.tagLinks(userId).groupBy({ it.feedId }) { it.name }
        val feeds = subscriptionDao.subscribedDetails(userId).map { row ->
            OpmlFeed(
                url = row.feed.url,
                title = row.title,
                siteUrl = row.feed.siteUrl,
                tags = tags[row.feed.id].orEmpty().sortedBy { it.lowercase() },
            )
        }
        return Opml.write(feeds, created = clock.instant())
    }
}
