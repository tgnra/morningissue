package net.tgnra.morningissue.server.db

import androidx.room3.Dao

/** Discovery verdicts and discovered subscriptions, per user. Owned by W-A (`docs/server-design.md`, section 8); empty until then. */
@Dao
interface DiscoveryDao
