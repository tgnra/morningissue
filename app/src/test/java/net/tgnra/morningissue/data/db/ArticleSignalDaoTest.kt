package net.tgnra.morningissue.data.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.model.ArticleCategories
import net.tgnra.morningissue.model.ArticleCategory
import net.tgnra.morningissue.model.ArticleTraitRules
import net.tgnra.morningissue.model.ArticleTraits
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.EnclosureKind
import net.tgnra.morningissue.model.ItemKind
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Categories, traits and embeddings on an in-memory database. */
@RunWith(AndroidJUnit4::class)
class ArticleSignalDaoTest {
    private lateinit var db: MorningIssueDatabase
    private lateinit var dao: ArticleSignalDao

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        dao = db.articleSignalDao()
    }

    @After
    fun tearDown() = db.close()

    private val podcast = ArticleTraits(3, 1, false, EnclosureKind.AUDIO, isLinkPost = false, titlePrefix = "podcast")

    private fun category(name: String) = ArticleCategories.of(name)!!

    private suspend fun post(feedId: Long, guid: String, body: String? = null, wordCount: Int = 0): Long =
        db.articleDao().insertNew(article(feedId, guid, summary = "Summary $guid").copy(wordCount = wordCount), body?.let { "[]" }, body)!!

    @Test
    fun categoriesAreKeptOncePerKeyAndAtMostTwelve() = runTest {
        val id = post(db.feedDao().insert(feed()), "a")
        dao.addCategories(id, listOf(category("Tech"), category("Long Reads"), category("TECH")))
        dao.addCategories(id, listOf(category("tech"), category("Essays")))
        dao.addCategories(id, emptyList())
        assertEquals(
            listOf(ArticleCategoryEntity(id, "essays", "Essays"), ArticleCategoryEntity(id, "long reads", "Long Reads"), ArticleCategoryEntity(id, "tech", "Tech")),
            dao.categoriesOf(listOf(id)),
        )

        val other = post(db.feedDao().insert(feed("https://b.example/feed")), "b")
        dao.addCategories(other, (1..20).map { category("Tag $it") })
        assertEquals(12, dao.categoriesOf(listOf(other)).size)
    }

    @Test
    fun traitsAreUpsertedAndReadBack() = runTest {
        val id = post(db.feedDao().insert(feed()), "a")
        assertNull(dao.getTraits(id))
        dao.upsertTraits(ArticleSignalDao.traitsRow(id, podcast))
        assertEquals(ArticleTraitsEntity(id, ArticleTraitRules.VERSION, 3, 1, false, EnclosureKind.AUDIO, false, "podcast"), dao.getTraits(id))
        assertEquals(podcast, dao.getTraits(id)!!.toTraits())

        dao.upsertTraits(ArticleSignalDao.traitsRow(id, podcast.copy(linkCount = 9, titlePrefix = null)))
        assertEquals(9, dao.getTraits(id)!!.linkCount)
        assertNull(dao.getTraits(id)!!.titlePrefix)
        dao.updateEnclosure(id, EnclosureKind.VIDEO)
        assertEquals(EnclosureKind.VIDEO, dao.getTraits(id)!!.enclosure)
        assertEquals(1, dao.traitsCount())
    }

    @Test
    fun removingAFeedRemovesItsPostsSignals() = runTest {
        val feedId = db.feedDao().insert(feed())
        val id = post(feedId, "a")
        dao.addCategories(id, listOf(category("Tech")))
        dao.upsertTraits(ArticleSignalDao.traitsRow(id, podcast))
        dao.upsertEmbeddings(listOf(ArticleEmbeddingEntity(id, 1, byteArrayOf(1, 2, 3))))

        db.feedDao().delete(feedId)
        assertEquals(0, dao.categoryCount())
        assertEquals(0, dao.traitsCount())
        assertEquals(0, dao.embeddingCount())
    }

    @Test
    fun storedPostsSaysWhatEachHeldPostOfAFeedStillLacks() = runTest {
        val feedId = db.feedDao().insert(feed())
        val elsewhere = db.feedDao().insert(feed("https://b.example/feed"))
        val bare = post(feedId, "bare")
        val full = post(feedId, "full")
        post(elsewhere, "bare")
        dao.addCategories(full, listOf(category("Tech")))
        dao.upsertTraits(ArticleSignalDao.traitsRow(full, podcast))

        val stored = dao.storedPosts(feedId, listOf("bare", "full", "unknown")).associateBy { it.guid }
        assertEquals(setOf("bare", "full"), stored.keys)
        assertEquals(StoredPostRow(bare, "bare", hasCategories = false, traitsVersion = null, enclosure = null), stored["bare"])
        assertEquals(StoredPostRow(full, "full", hasCategories = true, traitsVersion = ArticleTraitRules.VERSION, enclosure = EnclosureKind.AUDIO), stored["full"])
    }

    @Test
    fun postsWithoutAnEmbeddingAreHandedOutNewestFirstInBatchesWithTheirText() = runTest {
        val feedId = db.feedDao().insert(feed())
        val ids = (1..5).map { n -> post(feedId, "p$n", body = if (n == 2) null else "Body of p$n " + "x".repeat(50)) }
        // p4 is embedded by the current model, p3 by an older one.
        dao.upsertEmbeddings(listOf(ArticleEmbeddingEntity(ids[3], MODEL, byteArrayOf(4)), ArticleEmbeddingEntity(ids[2], MODEL - 1, byteArrayOf(3))))
        assertEquals(4, dao.countWithoutEmbedding(MODEL))

        val first = dao.articlesWithoutEmbedding(MODEL, Long.MAX_VALUE, limit = 2, bodyChars = 10)
        assertEquals(listOf(ids[4], ids[2]), first.map { it.articleId })
        assertEquals(EmbeddingInputRow(ids[4], "Title p5", "Summary p5", "Body of p5"), first[0])

        val second = dao.articlesWithoutEmbedding(MODEL, first.last().articleId, limit = 2, bodyChars = 10)
        assertEquals(listOf(ids[1], ids[0]), second.map { it.articleId })
        // No stored body: the title and summary are all there is.
        assertEquals(EmbeddingInputRow(ids[1], "Title p2", "Summary p2", ""), second[0])
        assertTrue(dao.articlesWithoutEmbedding(MODEL, second.last().articleId, limit = 2, bodyChars = 10).isEmpty())

        // Embedding replaces the older model's vector; nothing is left to do.
        dao.upsertEmbeddings((first + second).map { ArticleEmbeddingEntity(it.articleId, MODEL, byteArrayOf(9, 9)) })
        assertEquals(0, dao.countWithoutEmbedding(MODEL))
        assertEquals(5, dao.embeddingCount())
        val replaced = dao.getEmbedding(ids[2])!!
        assertEquals(MODEL, replaced.model)
        assertArrayEquals(byteArrayOf(9, 9), replaced.vector)
        assertTrue(dao.articlesWithoutEmbedding(MODEL, Long.MAX_VALUE, limit = 10, bodyChars = 10).isEmpty())
    }

    @Test
    fun signalsForCandidatesCarryFeedLengthCategoriesTraitsAndTheModelsVector() = runTest {
        val blog = db.feedDao().insert(feed())
        val news = db.feedDao().insert(feed("https://b.example/feed"))
        val full = post(blog, "full", wordCount = 900)
        val bare = post(news, "bare", wordCount = 40)
        val old = post(blog, "old")
        dao.addCategories(full, listOf(category("Tech"), category("Essays")))
        dao.upsertTraits(ArticleSignalDao.traitsRow(full, podcast))
        dao.upsertEmbeddings(listOf(ArticleEmbeddingEntity(full, MODEL, byteArrayOf(1, 2)), ArticleEmbeddingEntity(old, MODEL - 1, byteArrayOf(7))))

        val signals = dao.signalsFor(listOf(full, bare, old, full, 9999L), MODEL).associateBy { it.articleId }
        assertEquals(setOf(full, bare, old), signals.keys)

        val a = signals.getValue(full)
        assertEquals(blog, a.feedId)
        assertEquals(900, a.wordCount)
        assertEquals(listOf(ArticleCategory("essays", "Essays"), ArticleCategory("tech", "Tech")), a.categories)
        assertEquals(podcast, a.traits!!.toTraits())
        assertEquals(ArticleTraitRules.VERSION, a.traits!!.traitsVersion)
        assertArrayEquals(byteArrayOf(1, 2), a.embedding)

        val b = signals.getValue(bare)
        assertEquals(news, b.feedId)
        assertEquals(40, b.wordCount)
        assertTrue(b.categories.isEmpty())
        assertNull(b.traits)
        assertNull(b.embedding)
        // Another model's vector is not this model's.
        assertNull(signals.getValue(old).embedding)

        // Without a model the vectors are left out.
        assertNull(dao.signalsFor(listOf(full), null).single().embedding)
        assertTrue(dao.signalsFor(emptyList(), MODEL).isEmpty())
    }

    @Test
    fun signalsForMoreCandidatesThanOneQueryCanBind() = runTest {
        val feedId = db.feedDao().insert(feed())
        val ids = (1..(ArticleSignalDao.MAX_IDS + 20)).map { post(feedId, "p$it") }
        dao.addCategories(ids.last(), listOf(category("Last")))
        val signals = dao.signalsFor(ids, MODEL)
        assertEquals(ids.toSet(), signals.map { it.articleId }.toSet())
        assertEquals(listOf(ArticleCategory("last", "Last")), signals.single { it.articleId == ids.last() }.categories)
    }

    @Test
    fun evidenceIsAPrintedStoryOrEngagementSinceTheInstant() = runTest {
        val feedId = db.feedDao().insert(feed())
        val since = T0.minus(Duration.ofDays(60))
        val before = since.minus(Duration.ofDays(5))
        val after = since.plus(Duration.ofDays(5))
        val printed = post(feedId, "printed")
        val indexed = post(feedId, "indexed")
        val printedLongAgo = post(feedId, "printed-long-ago")
        val opened = post(feedId, "opened")
        val openedLongAgo = post(feedId, "opened-long-ago")
        val likedLately = post(feedId, "liked-lately")
        post(feedId, "untouched")

        suspend fun edition(day: Long, releaseAt: java.time.Instant, vararg items: Pair<Long, ItemKind>) = db.editionDao().insertEditionWithItems(
            EditionEntity(
                date = LocalDate.ofEpochDay(day), kind = EditionKind.MORNING, cutoffAt = releaseAt, releaseAt = releaseAt,
                builtAt = releaseAt, length = EditionLength.STANDARD, number = day.toInt(),
            ),
            items.mapIndexed { slot, (id, kind) -> EditionItemEntity(0, id, 1, null, slot, kind, 1.0) },
        )
        edition(1, before, printedLongAgo to ItemKind.LEAD)
        edition(2, after, printed to ItemKind.LEAD, indexed to ItemKind.INDEX)
        db.engagementDao().markOpened(opened, after)
        db.engagementDao().markOpened(openedLongAgo, before)
        // Opened before the window, Liked inside it.
        db.engagementDao().markOpened(likedLately, before)
        db.engagementDao().setLiked(likedLately, after)

        dao.addCategories(printed, listOf(category("Tech")))
        dao.addCategories(indexed, listOf(category("Never read")))
        dao.upsertTraits(ArticleSignalDao.traitsRow(opened, podcast))
        dao.upsertEmbeddings(listOf(ArticleEmbeddingEntity(likedLately, MODEL, byteArrayOf(5))))

        val signals = dao.signalsWithEvidenceSince(since, MODEL).associateBy { it.articleId }
        assertEquals(setOf(printed, opened, likedLately), signals.keys)
        assertEquals(listOf(ArticleCategory("tech", "Tech")), signals.getValue(printed).categories)
        assertEquals(podcast, signals.getValue(opened).traits!!.toTraits())
        assertArrayEquals(byteArrayOf(5), signals.getValue(likedLately).embedding)
        assertNull(dao.signalsWithEvidenceSince(since, null).single { it.articleId == likedLately }.embedding)

        // The same posts affinity learns from.
        val affinity = db.editionDao().getRunsSince(since).map { it.articleId } + db.engagementDao().engagedSince(since).map { it.articleId }
        assertEquals(affinity.toSet(), signals.keys)
    }

    private companion object {
        const val MODEL = 3
    }
}
