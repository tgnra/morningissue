package net.tgnra.morningissue.server.db

import androidx.room3.Dao

/** Per-user engagement, with `change_seq` for the engagement delta. Owned by W-B (`docs/server-design.md`, section 8); empty until then. */
@Dao
interface EngagementDao
