package net.tgnra.morningissue.server.db

import androidx.room3.Dao

/** The signal reads affinity and within-feed learning need, per user. Owned by W-B (`docs/server-design.md`, section 8); empty until then. */
@Dao
interface LearningDao
