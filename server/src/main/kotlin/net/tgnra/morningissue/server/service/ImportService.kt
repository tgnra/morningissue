package net.tgnra.morningissue.server.service

import java.io.File
import net.tgnra.morningissue.api.ImportMode
import net.tgnra.morningissue.api.ImportResponse

/** The legacy archive import (4.9). Implemented by W-B in `importer/`. */
interface ImportService {
    /**
     * `POST /import`: [archive] is the uploaded zip, already stored under `<dataDir>/imports/`.
     * A known [idempotencyKey] answers its report and does nothing; otherwise an IMPORT job
     * starts. [ApiFailure] `conflict` for REPLACE on an account with data, `busy` while another
     * import runs for the user.
     */
    suspend fun start(userId: Long, mode: ImportMode, idempotencyKey: String, archive: File): ImportResponse
}
