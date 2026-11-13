package net.tgnra.morningissue.server.db

import androidx.room3.Dao

/** Editions, their items and page views, per user. Owned by W-B (`docs/server-design.md`, section 8); empty until then. */
@Dao
interface EditionDao
