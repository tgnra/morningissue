package net.tgnra.morningissue.sync

sealed interface FullTextResult {
    /** Full text was fetched and stored by this call. */
    data object Stored : FullTextResult

    /** Nothing to do: complete text was already stored. */
    data object AlreadyStored : FullTextResult

    /** No better text could be had (no URL, offline, extraction failed); stored content is unchanged. */
    data class Unavailable(val message: String) : FullTextResult
}

/**
 * Gets an article's full text from its web page. **Owned by the sync agent**; the stub is
 * [NoOpFullTextFetcher]. Called by the reader when it opens an article and by the edition
 * prefetch for every printed story.
 */
interface FullTextFetcher {
    /**
     * Makes sure the best available body for [articleId] is stored. If fetched text is already
     * stored, or the feed-provided text is judged complete, returns [FullTextResult.AlreadyStored]
     * without touching the network. Otherwise downloads the page, extracts the article
     * (Readability), converts it to `ContentBlock`s and stores it with `ArticleDao.storeContent`
     * (source `FETCHED`), which makes `ArticleRepository.observeReaderArticle` emit the new body.
     * Never throws except on cancellation.
     */
    suspend fun ensureFullText(articleId: Long): FullTextResult
}

class NoOpFullTextFetcher : FullTextFetcher {
    override suspend fun ensureFullText(articleId: Long): FullTextResult =
        FullTextResult.Unavailable("Full text is not available yet")
}
