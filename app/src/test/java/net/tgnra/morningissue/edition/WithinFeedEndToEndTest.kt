package net.tgnra.morningissue.edition

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.data.repository.AffinityLearner
import net.tgnra.morningissue.data.repository.RoomArticleRepository
import net.tgnra.morningissue.data.repository.WithinFeedLearner
import net.tgnra.morningissue.data.signals.EmbeddingBackfill
import net.tgnra.morningissue.data.signals.SignalsUpkeep
import net.tgnra.morningissue.data.signals.TraitsBackfill
import net.tgnra.morningissue.embedding.EmbeddingFixtures
import net.tgnra.morningissue.embedding.StaticEmbedder
import net.tgnra.morningissue.engine.WithinFeedSignalKind
import net.tgnra.morningissue.model.ArticleCategories
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.ItemKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Within-feed ranking against a real database: posts stored with their categories as sync
 * stores them, traits and embeddings filled in by `SignalsUpkeep`, editions published and read
 * over simulated days, and the result read back from the stored edition.
 */
@RunWith(AndroidJUnit4::class)
class WithinFeedEndToEndTest {
    /** Half an hour after the 06:30 reset (UTC) on day 0. */
    private val firstMorning: Instant = Instant.parse("2026-11-10T07:00:00Z")
    private val clock = MutableClock(firstMorning)
    private val settings = FakeSettings()
    private lateinit var db: MorningIssueDatabase
    private lateinit var upkeep: SignalsUpkeep
    private var guid = 0

    @Before
    fun setUp() {
        db = MorningIssueDatabase.inMemory(ApplicationProvider.getApplicationContext())
        upkeep = SignalsUpkeep(TraitsBackfill(db), EmbeddingBackfill(db, embedder = { EmbeddingFixtures.embedder }))
    }

    @After
    fun tearDown() = db.close()

    private fun publisher() = RoomEditionPublisher(db, settings, clock, zone = { ZoneOffset.UTC })

    private suspend fun addFeed(title: String, tier: CadenceTier = CadenceTier.DAILY): Long = db.feedDao().insert(
        FeedEntity(
            url = "https://example.com/$title.xml",
            siteUrl = "https://example.com/",
            title = title,
            tier = tier,
            medianIntervalHrs = if (tier == CadenceTier.DAILY) 24.0 else 168.0,
            addedAt = firstMorning.minus(Duration.ofDays(60)),
        ),
    )

    /** A post as sync stores it: the article, then the categories the feed files it under. */
    private suspend fun addPost(feedId: Long, seenAt: Instant, title: String, category: String? = null, publishedAt: Instant = seenAt): Long {
        val id = "post-${guid++}"
        val articleId = db.articleDao().insertNew(
            ArticleEntity(feedId = feedId, guid = id, url = "https://example.com/$id", title = title, publishedAt = publishedAt, firstSeenAt = seenAt),
        )!!
        if (category != null) db.articleSignalDao().addCategories(articleId, listOf(ArticleCategories.of(category)!!))
        return articleId
    }

    /** Moves the clock to 07:00 on [day] and returns the time posts of that morning were fetched. */
    private fun morningOf(day: Int): Instant {
        clock.now = firstMorning.plus(Duration.ofDays(day.toLong()))
        return clock.now.minus(Duration.ofMinutes(30))
    }

    private suspend fun itemOf(editionId: Long, articleId: Long): EditionItemEntity =
        db.editionDao().getItems(editionId).single { it.item.articleId == articleId }.item

    private val spaceTitles = listOf(
        "A new telescope images a distant exoplanet", "Rocket launch carries two satellites into orbit",
        "Astronomers map the dust around a young star", "The lunar lander sends back its first pictures",
    )
    private val sportTitles = listOf(
        "The cup final goes to penalties again", "A late goal settles the derby",
        "The champion withdraws from the tennis tournament", "Transfer window closes with a record signing",
    )

    @Test
    fun theCategoryTheReaderOpensIsRankedAboveTheOneTheySkipAndTheStorySaysWhy() = runTest {
        val publisher = publisher()
        val articles = RoomArticleRepository(db, clock)
        val planet = addFeed("Planet")
        val other = addFeed("Other")

        val editions = ArrayList<Long>()
        val space = ArrayList<Long>()
        val sport = ArrayList<Long>()
        val days = 0..14
        for (day in days) {
            val seenAt = morningOf(day)
            // The sport post is the newer of the two by an hour, so on age alone it comes first.
            space += addPost(planet, seenAt, spaceTitles[day % spaceTitles.size] + ", day $day", "Space", publishedAt = seenAt.minus(Duration.ofHours(1)))
            sport += addPost(planet, seenAt, sportTitles[day % sportTitles.size] + ", day $day", "Sport")
            addPost(other, seenAt, "Council approves the new budget, day $day", publishedAt = seenAt.minus(Duration.ofHours(3)))
            // As after a sync: traits and embeddings for the new posts.
            upkeep.run()

            editions += publisher.ensureCurrentEdition()!!
            // The reader turns through the whole paper.
            db.viewPages(editions.last(), clock.now)

            // Every day but the last the reader opens and finishes the space story, and nothing else.
            if (day != days.last) {
                clock.now = clock.now.plus(Duration.ofHours(1))
                articles.markOpened(space.last())
                articles.setReadFraction(space.last(), 0.9f)
            }
        }
        assertEquals(days.count(), editions.distinct().size)

        // Day 0: nothing is known. The newer post leads, the older one follows among the top stories.
        val firstSport = itemOf(editions.first(), sport.first())
        val firstSpace = itemOf(editions.first(), space.first())
        assertEquals(ItemKind.LEAD, firstSport.kind)
        assertEquals(ItemKind.TOP, firstSpace.kind)
        assertTrue(firstSport.score > firstSpace.score)
        assertEquals("New from Planet since the last edition", firstSpace.reason)

        // The last edition: the feed's space post has taken the lead from its sport post ...
        val lastSpace = itemOf(editions.last(), space.last())
        val lastSport = itemOf(editions.last(), sport.last())
        assertEquals(ItemKind.LEAD, lastSpace.kind)
        assertEquals(ItemKind.TOP, lastSport.kind)
        assertTrue(lastSpace.score > lastSport.score)
        // ... and "Why is this here?" says so. (The category is named by its lowercased key.)
        assertEquals("You usually read Planet's posts about space", lastSpace.reason)
        // A post held down by W is not given W as its reason.
        assertEquals("New from Planet since the last edition", lastSport.reason)

        // Feed affinity has not moved: the reader reads half of what Planet posts, as a neutral
        // feed's prior assumes. The reordering is the within-feed weight's alone.
        assertEquals(0.5, db.feedDao().getById(planet)!!.affinity, 0.02)
        // The feed whose posts carry no category is untouched by W: its story is where it is on the old terms.
        val otherStory = db.editionDao().getItems(editions.last()).single { it.row.article.feedId == other }.item
        assertEquals("New from Other since the last edition", otherStory.reason)

        // What the publisher learned, read the same way: the two categories pull apart, and
        // the stored vectors reached the engine.
        val learner = AffinityLearner(db)
        val pending = db.editionDao().getPending().mapTo(HashSet()) { it.id }
        val evidence = learner.evidence(clock.now, pending)
        // The last edition has not been superseded: its untouched stories are not evidence yet.
        assertFalse(space.last() in evidence)
        val within = WithinFeedLearner(db)
        val model = within.learn(evidence, learner.windowStart(clock.now), at = clock.now)
        val traits = within.traitsOf(listOf(space.last(), sport.last()), model)
        val up = model.weight(traits.getValue(space.last()))
        val down = model.weight(traits.getValue(sport.last()))
        assertTrue("W of the space post is ${up.multiplier}", up.multiplier >= 1.15)
        assertTrue("W of the sport post is ${down.multiplier}", down.multiplier < 0.9)
        assertEquals(WithinFeedSignalKind.CATEGORY, up.signal!!.kind)
        assertEquals("space", up.signal?.label)
        assertNotNull("the text taste was learned from stored vectors", model.text)
        assertEquals(StaticEmbedder.DIMENSIONS, model.text!!.dimension)
        assertEquals(0, db.articleSignalDao().countWithoutEmbedding(StaticEmbedder.MODEL_ID))
    }

    @Test
    fun anExtraAndAnEveningEditionAreRankedWithTheSameModel() = runTest {
        val publisher = publisher()
        val articles = RoomArticleRepository(db, clock)
        val planet = addFeed("Planet")
        addFeed("Other").also { other -> addPost(other, morningOf(0), "Council approves the new budget") }
        for (day in 0..13) {
            val seenAt = morningOf(day)
            val read = addPost(planet, seenAt, spaceTitles[day % spaceTitles.size] + ", day $day", "Space")
            addPost(planet, seenAt, sportTitles[day % sportTitles.size] + ", day $day", "Sport")
            upkeep.run()
            db.viewPages(publisher.ensureCurrentEdition()!!, clock.now)
            clock.now = clock.now.plus(Duration.ofHours(1))
            articles.markOpened(read)
            articles.setReadFraction(read, 0.9f)
        }

        // Later the same day two more posts arrive, the sport one the newer again.
        clock.now = firstMorning.plus(Duration.ofDays(13)).plus(Duration.ofHours(6))
        val space = addPost(planet, clock.now.minus(Duration.ofHours(2)), "Astronomers find water on a distant moon", "Space")
        val sport = addPost(planet, clock.now.minus(Duration.ofHours(1)), "The league announces next season's fixtures", "Sport")
        upkeep.run()

        // An Extra edition supersedes nothing, and still ranks with what has been learned.
        val extra = publisher.publishExtraEdition(setOf(planet))!!
        assertEquals(EditionKind.EXTRA, db.editionDao().getById(extra)!!.kind)
        assertEquals(ItemKind.LEAD, itemOf(extra, space).kind)
        assertEquals("You usually read Planet's posts about space", itemOf(extra, space).reason)
        assertTrue(itemOf(extra, space).score > itemOf(extra, sport).score)

        // So does the Evening edition, for the posts that arrive after the Extra.
        settings.setEveningEditionEnabled(true)
        clock.now = firstMorning.plus(Duration.ofDays(13)).plus(Duration.ofHours(12))
        val eveningSpace = addPost(planet, clock.now.minus(Duration.ofHours(2)), "A comet passes closer than any in a decade", "Space")
        val eveningSport = addPost(planet, clock.now.minus(Duration.ofHours(1)), "The marathon record falls by four seconds", "Sport")
        upkeep.run()
        val evening = publisher.ensureCurrentEdition()!!
        assertEquals(EditionKind.EVENING, db.editionDao().getById(evening)!!.kind)
        assertEquals(ItemKind.LEAD, itemOf(evening, eveningSpace).kind)
        assertEquals("You usually read Planet's posts about space", itemOf(evening, eveningSpace).reason)
        assertTrue(itemOf(evening, eveningSpace).score > itemOf(evening, eveningSport).score)
    }
}
