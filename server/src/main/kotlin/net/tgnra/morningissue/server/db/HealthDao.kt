package net.tgnra.morningissue.server.db

import androidx.room3.Dao

/** Feed health: `feed_status` stats and per-user `feed_flag`. Owned by W-A (`docs/server-design.md`, section 8); empty until then. */
@Dao
interface HealthDao
