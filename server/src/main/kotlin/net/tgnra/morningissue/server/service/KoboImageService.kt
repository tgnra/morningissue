package net.tgnra.morningissue.server.service

import java.io.OutputStream
import net.tgnra.morningissue.api.KoboImagesRequest

/**
 * Pictures prepared for e-ink (4.8): `KoboImages` ported to the JVM (ImageIO and TwelveMonkeys,
 * core's `GreyImage` encoders), cached under `<dataDir>/kobo-images/`. Implemented by W-B.
 */
interface KoboImageService {
    /**
     * `POST /kobo/images` (gated by the route): writes the zip of stored entries
     * `images/<sha256(url)>.<jpg|png>` and `images.json` to [output]. URLs that cannot be got or
     * decoded are left out.
     */
    suspend fun writeZip(userId: Long, request: KoboImagesRequest, output: OutputStream)

    /** Warms the cache for edition [editionId]'s pictures at each of [widths], after a build. */
    suspend fun prepare(editionId: Long, widths: Set<Int>)
}
