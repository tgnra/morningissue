package net.tgnra.morningissue.server.db

import androidx.room3.Dao

/** Cross-table inserts for the legacy archive import. Owned by W-B (`docs/server-design.md`, section 8); empty until then. */
@Dao
interface ImportDao
