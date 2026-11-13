package net.tgnra.morningissue.server.service

import net.tgnra.morningissue.api.StandDto

/** `GET /stand` (4.3): `DueEditions.status` and the Extra offer, in the user's zone. Implemented by W-B. */
interface StandService {
    suspend fun stand(userId: Long): StandDto
}
