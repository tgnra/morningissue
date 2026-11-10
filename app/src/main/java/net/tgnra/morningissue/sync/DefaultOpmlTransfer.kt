package net.tgnra.morningissue.sync

import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.data.repository.FeedRepository
import net.tgnra.morningissue.model.AddFeedResult
import net.tgnra.morningissue.model.NewFeed

/** The real [OpmlTransfer]: [Opml] for the format, [FeedRepository] for the subscriptions. */
class DefaultOpmlTransfer(
    private val feeds: FeedRepository,
    private val clock: Clock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OpmlTransfer {

    override suspend fun importFrom(input: InputStream): OpmlImportReport {
        val document = withContext(ioDispatcher) { Opml.read(input) }
        var added = 0
        var skipped = 0
        for (feed in document.feeds) {
            // addFeed matches on the normalised URL, so a feed already subscribed is left as it is.
            when (feeds.addFeed(NewFeed(url = feed.url, title = feed.title, siteUrl = feed.siteUrl, tags = feed.tags))) {
                is AddFeedResult.Added -> added++
                is AddFeedResult.AlreadySubscribed -> skipped++
            }
        }
        return OpmlImportReport(added = added, skipped = skipped)
    }

    override suspend fun exportTo(output: OutputStream): Int {
        val subscriptions = feeds.getFeeds().map { OpmlFeed(url = it.url, title = it.title, siteUrl = it.siteUrl, tags = it.tags) }
        withContext(ioDispatcher) { Opml.write(subscriptions, output, created = clock.instant()) }
        return subscriptions.size
    }
}
