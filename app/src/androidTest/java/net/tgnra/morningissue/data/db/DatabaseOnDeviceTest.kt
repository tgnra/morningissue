package net.tgnra.morningissue.data.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the schema, including the FTS4 table, on the device's own SQLite. */
@RunWith(AndroidJUnit4::class)
class DatabaseOnDeviceTest {
    private val now: Instant = Instant.parse("2026-11-10T06:30:00Z")
    private lateinit var db: MorningIssueDatabase

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun insertAndSearch() = runTest {
        val feedId = db.feedDao().insert(FeedEntity(url = "https://example.com/feed", title = "Example", addedAt = now))
        fun post(guid: String, title: String) =
            ArticleEntity(feedId = feedId, guid = guid, title = title, publishedAt = now, firstSeenAt = now)

        val id = db.articleDao().insertNew(post("1", "Morning papers"), blocksJson = "[]", plainText = "Ink and newsprint")
        db.articleDao().insertNew(post("2", "Something else"))
        assertNull(db.articleDao().insertNew(post("1", "Duplicate")))

        val hits = db.articleDao().search("\"newsp*\"", null, null, null, null, likedOnly = false, savedOnly = false, limit = 10)

        assertEquals(listOf(id), hits.map { it.row.article.id })
        assertEquals("Example", hits.single().row.feedTitle)
    }
}
