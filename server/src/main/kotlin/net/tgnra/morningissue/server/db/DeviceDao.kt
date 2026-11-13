package net.tgnra.morningissue.server.db

import androidx.room3.Dao

/** Devices and their token hashes, `acked_seq` and last-seen times. Owned by W-C (`docs/server-design.md`, section 8); empty until then. */
@Dao
interface DeviceDao
