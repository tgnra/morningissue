package net.tgnra.morningissue.server.db

import androidx.room3.Dao

/** Post signals: categories, traits and embeddings, and their backfills. Owned by W-A (`docs/server-design.md`, section 8); empty until then. */
@Dao
interface SignalDao
