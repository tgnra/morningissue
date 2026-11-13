package net.tgnra.morningissue.server.db

import androidx.room3.Dao

/** Global feeds and `feed_status`: refresh bookkeeping, repair, cadence. Owned by W-A (`docs/server-design.md`, section 8); empty until then. */
@Dao
interface FeedDao
