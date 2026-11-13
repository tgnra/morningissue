package net.tgnra.morningissue.server.db

import androidx.room3.Dao

/** The publisher's per-user candidate reads (`firstSeenBetween` and friends). Owned by W-B (`docs/server-design.md`, section 8); empty until then. */
@Dao
interface EditionArticleDao
