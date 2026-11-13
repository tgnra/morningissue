package net.tgnra.morningissue.server.service

import net.tgnra.morningissue.api.EventsRequest
import net.tgnra.morningissue.api.EventsResponse
import net.tgnra.morningissue.api.ManifestDto

/** Event upload, the events gate and the manifest (4.2). Implemented by W-B. Same rules for phones and Kobos. */
interface SyncService {
    /** `POST /sync/events`: merges events above the device's `acked_seq` in one transaction. */
    suspend fun postEvents(userId: Long, deviceId: String, request: EventsRequest): EventsResponse

    /**
     * The gate every gated route runs first. [eventsThrough] is the `X-Events-Through` header,
     * null when absent: [ApiFailure] `events_through_required`, or `events_pending` (with
     * `acked`) when it is above the device's `acked_seq`.
     */
    suspend fun checkGate(userId: Long, deviceId: String, eventsThrough: Long?)

    /** `GET /sync/manifest?editions=` (after [checkGate]). */
    suspend fun manifest(userId: Long, deviceId: String, editions: Int): ManifestDto
}
