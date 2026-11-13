package net.tgnra.morningissue.server.db

import androidx.room3.Dao

/** Articles, their bodies and the FTS index: refresh writes, storage and search reads. Owned by W-A (`docs/server-design.md`, section 8); empty until then. */
@Dao
interface ArticleDao
