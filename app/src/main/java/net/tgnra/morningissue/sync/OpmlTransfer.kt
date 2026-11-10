package net.tgnra.morningissue.sync

import java.io.InputStream
import java.io.OutputStream

/** "Added 38, skipped 4". */
data class OpmlImportReport(
    val added: Int = 0,
    /** Feeds already subscribed (matched on normalised URL). */
    val skipped: Int = 0,
)

/**
 * OPML import and export of the subscription list. **Owned by the sync agent** (on top of the
 * pure reader/writer in `sync/Opml.kt`); the stub is [NoOpOpmlTransfer]. Called by the Sources
 * screen with streams opened through the Storage Access Framework. The caller owns and closes
 * the streams.
 */
interface OpmlTransfer {
    /**
     * Reads an OPML document, subscribes to every feed not already present through
     * `FeedRepository.addFeed` (folder names become tags), and reports the counts. Does not
     * sync the new feeds. Throws `IOException` when the document cannot be read as OPML.
     */
    suspend fun importFrom(input: InputStream): OpmlImportReport

    /** Writes all subscriptions as OPML 2.0, one folder per tag, and returns the number of feeds written. */
    suspend fun exportTo(output: OutputStream): Int
}

class NoOpOpmlTransfer : OpmlTransfer {
    override suspend fun importFrom(input: InputStream): OpmlImportReport = OpmlImportReport()
    override suspend fun exportTo(output: OutputStream): Int = 0
}
