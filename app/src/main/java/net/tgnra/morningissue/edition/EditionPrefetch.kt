package net.tgnra.morningissue.edition

import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import net.tgnra.morningissue.data.images.ImageStore
import net.tgnra.morningissue.data.repository.EditionRepository
import net.tgnra.morningissue.model.EditionPage
import net.tgnra.morningissue.sync.FullTextFetcher
import net.tgnra.morningissue.sync.FullTextResult

/** Fetches the pictures of an edition's printed stories so it reads offline. Never throws except on cancellation. */
fun interface ImagePrefetcher {
    /**
     * The pictures of an edition's printed stories: [articleIds] in page order, and
     * [pageImageUrls], the lead images its pages show.
     */
    suspend fun prefetchArticles(articleIds: List<Long>, pageImageUrls: List<String>)
}

/**
 * Puts the lead image and the body images of each printed story in the image store
 * (`data/images/`), re-encoded for display, so the edition and its articles read offline and
 * the pictures count against the storage budget.
 */
class StoringImagePrefetcher(private val store: ImageStore) : ImagePrefetcher {
    override suspend fun prefetchArticles(articleIds: List<Long>, pageImageUrls: List<String>) {
        store.storeForArticles(articleIds)
    }
}

/** What one prefetch stored; for logs and tests. */
data class PrefetchReport(val fullTextStored: Int = 0, val imagesRequested: Int = 0)

/**
 * After an edition is printed: the full text of every printed story (briefs included: tapping
 * one opens the reader), then the pictures, so the edition reads offline. Index entries are
 * fetched when opened.
 *
 * It runs after the print has finished, on the app's scope, and the reader does not wait for
 * it: the cover is on the stand at once. A failure or the [timeout] leaves what was fetched;
 * a story without its text is fetched when it is opened.
 */
class EditionPrefetch(
    private val editions: EditionRepository,
    private val fullTextFetcher: FullTextFetcher,
    private val images: ImagePrefetcher,
    private val timeout: Duration = Duration.ofMinutes(4),
) {
    /** Never throws except on cancellation. */
    suspend fun prefetch(editionId: Long): PrefetchReport = try {
        withTimeoutOrNull(timeout.toMillis()) { fetch(editionId) } ?: PrefetchReport()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        PrefetchReport()
    }

    private suspend fun fetch(editionId: Long): PrefetchReport {
        val pages = editions.getEdition(editionId)?.pages.orEmpty().filter { it !is EditionPage.Back }
        val articleIds = pages.flatMap { it.stories }.map { it.articleId }.distinct()
        val imageUrls = pages.filter { it !is EditionPage.InBrief }
            .flatMap { it.stories }
            .mapNotNull { it.imageUrl?.takeIf(String::isNotBlank) }
            .distinct()
        val stored = coroutineScope {
            val permits = Semaphore(PARALLEL_FETCHES)
            articleIds.map { id ->
                async {
                    try {
                        permits.withPermit { fullTextFetcher.ensureFullText(id) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().count { it is FullTextResult.Stored }
        }
        // After the text, so that the pictures in fetched bodies are known. The stories whose
        // pictures the pages show come first; briefs show none until they are opened.
        val pictured = pages.filter { it !is EditionPage.InBrief }.flatMap { it.stories }.map { it.articleId }
        images.prefetchArticles((pictured + articleIds).distinct(), imageUrls)
        return PrefetchReport(fullTextStored = stored, imagesRequested = imageUrls.size)
    }

    private companion object {
        const val PARALLEL_FETCHES = 4
    }
}
