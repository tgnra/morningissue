package net.tgnra.morningissue.data.repository

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import net.tgnra.morningissue.model.FlaggedFeed
import net.tgnra.morningissue.model.RetryOutcome
import net.tgnra.morningissue.model.SliderStep

/**
 * The Subscriptions desk: feeds the health check has flagged, and the one-tap fixes. Called by
 * the UI. Implemented by `sync/DefaultFeedHealthRepository`. Nothing here unsubscribes; the
 * desk's Unsubscribe is `FeedRepository.removeFeed`, after the reader confirms.
 */
interface FeedHealthRepository {
    /**
     * Feeds with an undismissed flag, most urgent flag first, then by title. Emits again when
     * a flag, a dismissal or a feed changes.
     */
    fun observeFlagged(): Flow<List<FlaggedFeed>>

    /** Re-runs the health rules over stored data, without touching the network. */
    suspend fun refresh()

    /** Hides the feed's flag for six months. */
    suspend fun dismiss(feedId: Long)

    /** Clears the feed's flag for good, for example once a new address has been seen. */
    suspend fun acknowledge(feedId: Long)

    /** Sets the feed's slider (the fix for Ignored, Noisy and Loved) and clears its flag. */
    suspend fun applySlider(feedId: Long, step: SliderStep)

    /**
     * Tries a broken feed again now: its own address, then the usual alternatives. Fetches its
     * posts when it works. Uses the network; never throws except on cancellation.
     */
    suspend fun retry(feedId: Long): RetryOutcome

    /**
     * How many feeds need attention, if the edition [editionId] (published for [editionDate])
     * is the one that carries the monthly Subscriptions desk notice; null otherwise. At most
     * one edition per calendar month carries it, and none while no feed needs attention.
     */
    suspend fun deskNotice(editionId: Long, editionDate: LocalDate): Int?
}
