package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.SliderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 5b of edition assembly: the section placement rules agreed for milestone 2. */
class EditionSectionsTest {
    private val release: Instant = Instant.parse("2026-11-10T06:30:00Z")

    /** The mixed pages hold a lead and nothing else, so every other story is offered to the sections. */
    private val builder = EditionBuilder(RankingConfig(frontPageSecondaries = 0, topStoriesPages = 0))
    private var nextArticleId = 1L
    private var nextFeedId = 1L

    private fun feed(title: String, vararg tags: String, tier: CadenceTier = CadenceTier.WEEKLY) = FeedProfile(
        id = nextFeedId++,
        title = title,
        tier = tier,
        medianIntervalHours = if (tier == CadenceTier.DAILY) 24.0 else 168.0,
        tags = tags.toList(),
    )

    /** Within one tier a younger post always scores higher. */
    private fun post(feed: FeedProfile, ageHours: Double) = Candidate(
        articleId = nextArticleId++,
        feedId = feed.id,
        publishedAt = release.minus(Duration.ofMinutes((ageHours * 60).toLong())),
        firstSeenAt = release.minus(Duration.ofHours(1)),
    )

    /** Builds with one extra story, from a favourite, that takes the lead and stays out of the way. */
    private fun build(
        posts: List<Candidate>,
        feeds: List<FeedProfile>,
        tagAffinity: Map<String, Double> = emptyMap(),
        length: EditionLength = EditionLength.STANDARD,
    ): EditionDraft {
        val leadFeed = feed("Lead").copy(sliderStep = SliderStep.FAVOURITE)
        val draft = builder.build(posts + post(leadFeed, 0.0), feeds + leadFeed, length, release, tagAffinity)
        assertEquals(leadFeed.id, draft.lead?.feedId)
        return draft
    }

    /** Section pages in page order: name to article IDs in slot order. */
    private fun EditionDraft.sections(): List<Pair<String, List<Long>>> =
        items.filter { it.kind == ItemKind.SECTION }.groupBy { it.page }.toSortedMap().values.map { page ->
            assertEquals("one section name per page", 1, page.map { it.section }.distinct().size)
            assertEquals(page.indices.toList(), page.sortedBy { it.slot }.map { it.slot })
            page.first().section!! to page.sortedBy { it.slot }.map { it.articleId }
        }

    private fun EditionDraft.kindOf(post: Candidate): ItemKind = items.single { it.articleId == post.articleId }.kind

    private fun ids(vararg posts: Candidate): List<Long> = posts.map { it.articleId }

    @Test
    fun eachTagGetsAPageRankedWithinTheTagAndUntaggedFeedsGoInGeneral() {
        val tech = feed("Tech blog", "Tech")
        val personal = feed("Friend", "Personal")
        val plain = feed("Plain")
        val t1 = post(tech, 3.0)
        val t2 = post(tech, 1.0)
        val p1 = post(personal, 2.0)
        val p2 = post(personal, 9.0)
        val g1 = post(plain, 5.0)
        val g2 = post(plain, 4.0)

        val draft = build(listOf(t1, t2, p1, p2, g1, g2), listOf(tech, personal, plain), mapOf("Tech" to 0.6, "Personal" to 0.8))

        assertEquals(
            listOf("Personal" to ids(p1, p2), "Tech" to ids(t2, t1), "General" to ids(g2, g1)),
            draft.sections(),
        )
        // Section pages follow the mixed pages, one page each.
        assertEquals(listOf(2, 3, 4), draft.items.filter { it.kind == ItemKind.SECTION }.map { it.page }.distinct())
        assertTrue(draft.index.isEmpty())
    }

    @Test
    fun sectionsRunByTagAffinityHighestFirstWithGeneralLast() {
        val names = listOf("Alpha", "Bravo", "Charlie", "Delta")
        val feeds = names.map { feed("$it feed", it) } + feed("Plain")
        // The untagged feed has the freshest stories of all; General still comes last.
        val posts = feeds.flatMap { f -> listOf(post(f, if (f.tags.isEmpty()) 0.1 else 20.0), post(f, if (f.tags.isEmpty()) 0.2 else 30.0)) }

        // Charlie is not listed and counts as neutral, tying with Delta: name order decides.
        val draft = build(posts, feeds, mapOf("Alpha" to 0.3, "Bravo" to 0.9, "Delta" to 0.5))

        assertEquals(listOf("Bravo", "Charlie", "Delta", "Alpha", "General"), draft.sections().map { it.first })
    }

    @Test
    fun aStoryWithSeveralTagsGoesToItsHighestAffinityTag() {
        val both = feed("Both", "Tech", "Personal")
        val tech = feed("Tech only", "Tech")
        val personal = feed("Personal only", "Personal")
        val story = post(both, 1.0)
        val posts = listOf(story, post(tech, 2.0), post(tech, 3.0), post(personal, 2.0), post(personal, 3.0))
        val feeds = listOf(both, tech, personal)

        val personalFirst = build(posts, feeds, mapOf("Tech" to 0.6, "Personal" to 0.8)).sections().toMap()
        assertTrue(story.articleId in personalFirst.getValue("Personal"))
        assertTrue(story.articleId !in personalFirst.getValue("Tech"))
        assertEquals(3, personalFirst.getValue("Personal").size)

        val techFirst = build(posts, feeds, mapOf("Tech" to 0.9, "Personal" to 0.8)).sections().toMap()
        assertTrue(story.articleId in techFirst.getValue("Tech"))
        assertTrue(story.articleId !in techFirst.getValue("Personal"))
    }

    /** [count] feeds tagged [tag] with one post each, the first [firstAge] hours old and each next one an hour older. */
    private fun oneEach(tag: String, count: Int, firstAge: Double, tier: CadenceTier = CadenceTier.WEEKLY): Pair<List<FeedProfile>, List<Candidate>> {
        val feeds = (1..count).map { feed("$tag $it", tag, tier = tier) }
        return feeds to feeds.mapIndexed { i, f -> post(f, firstAge + i) }
    }

    @Test
    fun aFullSectionSpillsTheStoryToItsOtherTag() {
        // Six feeds of their own fill Personal (one feed's six would share it: see
        // aBusyFeedSharesItsSectionWithTheTagsOtherFeeds).
        val (personal, six) = oneEach("Personal", 6, firstAge = 1.0)
        val both = feed("Both", "Tech", "Personal")
        val tech = feed("Tech only", "Tech")
        val spilled = post(both, 10.0)
        val techStory = post(tech, 20.0)

        val draft = build(six + spilled + techStory, personal + listOf(both, tech), mapOf("Tech" to 0.6, "Personal" to 0.8))

        assertEquals(
            listOf("Personal" to six.map { it.articleId }, "Tech" to ids(spilled, techStory)),
            draft.sections(),
        )
    }

    @Test
    fun aSpilledStoryMustMakeTheCutInItsOtherSection() {
        val (personal, personalSix) = oneEach("Personal", 6, firstAge = 1.0)
        val both = feed("Both", "Tech", "Personal")
        val (tech, techSix) = oneEach("Tech", 6, firstAge = 2.0)
        val plain = feed("Plain")
        val affinity = mapOf("Tech" to 0.6, "Personal" to 0.8)
        val general = listOf(post(plain, 1.0), post(plain, 2.0))
        val feeds = personal + both + tech + plain

        // Worse than everything in both of its sections: it is not printed in either, and it
        // does not fall back to General.
        val tooWeak = post(both, 10.0)
        val left = build(personalSix + techSix + general + tooWeak, feeds, affinity)
        assertEquals(
            listOf("Personal" to personalSix.map { it.articleId }, "Tech" to techSix.map { it.articleId }, "General" to general.map { it.articleId }),
            left.sections(),
        )
        assertEquals(ItemKind.INDEX, left.kindOf(tooWeak))

        // Good enough for Tech: it takes its place in the ranking and Tech's last story drops out.
        val strong = post(both, 6.5)
        val made = build(personalSix + techSix + general + strong, feeds, affinity)
        val techPage = made.sections().toMap().getValue("Tech")
        assertEquals(techSix.take(5).map { it.articleId } + strong.articleId, techPage)
        assertEquals(ItemKind.INDEX, made.kindOf(techSix[5]))
        assertEquals(personalSix.map { it.articleId }, made.sections().toMap().getValue("Personal"))
    }

    @Test
    fun aBusyFeedSharesItsSectionWithTheTagsOtherFeeds() {
        // A Favourite firehose filed under News: every one of its twenty posts (about 0.97 to
        // 0.87) outscores the four daily News feeds' posts (about 0.62).
        val wire = feed("Wire", "News").copy(tier = CadenceTier.FIREHOSE, medianIntervalHours = 0.5, sliderStep = SliderStep.FAVOURITE)
        val (others, otherPosts) = oneEach("News", 4, firstAge = 10.0, tier = CadenceTier.DAILY)
        val wirePosts = (0 until 20).map { post(wire, 0.5 + it * 0.1) }

        val draft = build(wirePosts + otherPosts, others + wire)
        val news = draft.sections().toMap().getValue("News")

        // The wire's second story counts ×0.7 (0.68, still ahead), its third ×0.49 (0.47,
        // behind every other feed): the page is shared, still full, and the wire still opens it.
        assertEquals(wirePosts.take(2).map { it.articleId } + otherPosts.map { it.articleId }, news)
        // Without the penalty the wire would have taken the whole page.
        val unpenalised = EditionBuilder(RankingConfig(frontPageSecondaries = 0, topStoriesPages = 0, sameFeedPagePenalty = 1.0))
        val leadFeed = feed("Lead").copy(sliderStep = SliderStep.FAVOURITE)
        val before = unpenalised.build(wirePosts + otherPosts + post(leadFeed, 0.0), others + wire + leadFeed, EditionLength.STANDARD, release)
        assertEquals(wirePosts.take(6).map { it.articleId }, before.sections().toMap().getValue("News"))
    }

    @Test
    fun aSectionWithOneFeedStillFills() {
        val only = feed("Only", "Solo")
        val posts = (0 until 10).map { post(only, 1.0 + it) }

        val solo = build(posts, listOf(only)).sections().toMap().getValue("Solo")

        assertEquals(posts.take(6).map { it.articleId }, solo)
    }

    @Test
    fun theSameFeedPenaltyDoesNotSendAStoryPastItsTags() {
        // A busy feed tagged Tech and Personal, whose posts outscore everything: once both its
        // sections are full its stories stay out of General, penalty or not.
        val busy = feed("Busy", "Tech", "Personal")
        val busyPosts = (0 until 20).map { post(busy, 0.1 + it * 0.1) }
        val plain = feed("Plain")
        val general = listOf(post(plain, 30.0), post(plain, 31.0))

        val draft = build(busyPosts + general, listOf(busy, plain), mapOf("Tech" to 0.6, "Personal" to 0.8))
        val sections = draft.sections().toMap()

        assertEquals(listOf("Personal", "Tech", "General"), draft.sections().map { it.first })
        assertEquals(busyPosts.take(6).map { it.articleId }, sections.getValue("Personal"))
        assertEquals(busyPosts.drop(6).take(6).map { it.articleId }, sections.getValue("Tech"))
        assertEquals(general.map { it.articleId }, sections.getValue("General"))
    }

    @Test
    fun aTagWithOneStoryGetsNoPageAndItsStoryMovesToGeneral() {
        val solo = feed("Solo", "Poetry")
        val plain = feed("Plain")
        val poem = post(solo, 2.0)
        val other = post(plain, 1.0)

        val draft = build(listOf(poem, other), listOf(solo, plain), mapOf("Poetry" to 0.9))

        assertEquals(listOf("General" to ids(other, poem)), draft.sections())
    }

    @Test
    fun aTagWithOneStoryGivesItToTheStorysOtherTag() {
        val duo = feed("Duo", "Poetry", "Tech")
        val tech = feed("Tech only", "Tech")
        val plain = feed("Plain")
        val poem = post(duo, 2.0)
        val techStory = post(tech, 1.0)
        val general = listOf(post(plain, 1.0), post(plain, 2.0))

        // Poetry is the story's best tag, but it would be alone there.
        val draft = build(general + poem + techStory, listOf(duo, tech, plain), mapOf("Poetry" to 0.9, "Tech" to 0.6))

        assertEquals(listOf("Tech" to ids(techStory, poem), "General" to general.map { it.articleId }), draft.sections())
    }

    @Test
    fun thinTagsAreDissolvedHighestFirstSoALowerTagCanFillUp() {
        val x = feed("X", "Alpha", "Bravo")
        val y = feed("Y", "Bravo")
        val first = post(x, 1.0)
        val second = post(y, 2.0)

        // Alpha and Bravo each start with one story. Dissolving Alpha moves its story to Bravo,
        // which then has two; dissolving both at once would have sent them to General.
        val draft = build(listOf(first, second), listOf(x, y), mapOf("Alpha" to 0.9, "Bravo" to 0.6))

        assertEquals(listOf("Bravo" to ids(first, second)), draft.sections())
    }

    @Test
    fun generalWithOneStoryGetsNoPageEither() {
        val slow = feed("Slow")
        val slowStory = post(slow, 1.0)
        val alone = build(listOf(slowStory), listOf(slow))
        assertTrue(alone.sections().isEmpty())
        // A slow feed's leftover is listed on the back page; it stays findable.
        assertEquals(ItemKind.INDEX, alone.kindOf(slowStory))

        // A lone tagged story fares the same: no page for its tag, none for General.
        val tagged = feed("Tagged", "Tech")
        val taggedStory = post(tagged, 1.0)
        val lone = build(listOf(taggedStory), listOf(tagged), mapOf("Tech" to 0.9))
        assertTrue(lone.sections().isEmpty())
        assertEquals(ItemKind.INDEX, lone.kindOf(taggedStory))

        // A Daily feed's leftover becomes a brief instead.
        val fast = feed("Fast", tier = CadenceTier.DAILY)
        val fastStory = post(fast, 1.0)
        assertEquals(ItemKind.BRIEF, build(listOf(fastStory), listOf(fast)).kindOf(fastStory))
    }

    @Test
    fun sectionSizeFollowsTheEditionLength() {
        val tech = feed("Tech blog", "Tech")
        val plain = feed("Plain")
        val techPosts = (1..12).map { post(tech, it.toDouble()) }
        val plainPosts = (1..12).map { post(plain, it.toDouble()) }

        fun sizes(length: EditionLength) = build(techPosts + plainPosts, listOf(tech, plain), length = length).sections()

        assertEquals(listOf(3, 3), sizes(EditionLength.SHORT).map { it.second.size })
        assertEquals(listOf(6, 6), sizes(EditionLength.STANDARD).map { it.second.size })
        assertEquals(listOf(9, 9), sizes(EditionLength.LONG).map { it.second.size })
        // The best of the tag, best first.
        assertEquals(techPosts.take(6).map { it.articleId }, sizes(EditionLength.STANDARD).first().second)
    }

    @Test
    fun aTagCalledGeneralIsTheGeneralSection() {
        val tagged = feed("Tagged", "general")
        val plain = feed("Plain")
        val a = post(tagged, 1.0)
        val b = post(plain, 2.0)

        assertEquals(listOf("General" to ids(a, b)), build(listOf(a, b), listOf(tagged, plain), mapOf("general" to 0.9)).sections())
    }

    @Test
    fun topStoriesFillFirstRegardlessOfTag() {
        val standard = EditionBuilder()
        val tech = feed("Tech blog", "Tech")
        val posts = (1..30).map { post(tech, it.toDouble()) }

        val draft = standard.build(posts, listOf(tech), EditionLength.STANDARD, release, mapOf("Tech" to 0.9))

        // Lead, two Top stories pages, and only then the tag's page with the next six.
        assertEquals(listOf(1, 8, 8, 6), draft.printed.groupBy { it.page }.toSortedMap().values.map { it.size })
        assertEquals(posts.subList(17, 23).map { it.articleId }, draft.items.filter { it.kind == ItemKind.SECTION }.map { it.articleId })
        assertTrue(draft.items.filter { it.kind == ItemKind.SECTION }.all { it.section == "Tech" && it.page == 4 })
    }

    @Test
    fun noStoryAppearsTwiceWhateverItsTags() {
        val standard = EditionBuilder()
        val tags = listOf("Tech", "Personal", "Science", "Local", "Art")
        // Every combination of none, one or two tags, on feeds of every pace.
        val feeds = (0 until 30).map { i ->
            val own = when (i % 3) {
                0 -> emptyList()
                1 -> listOf(tags[i % 5])
                else -> listOf(tags[i % 5], tags[(i / 3 + 1) % 5])
            }
            val tier = if (i % 4 == 0) CadenceTier.DAILY else CadenceTier.WEEKLY
            feed("Feed $i", *own.toTypedArray(), tier = tier)
        }
        val posts = feeds.flatMap { f -> (0 until 5).map { post(f, 1.0 + f.id * 0.37 + it * 7.0) } }
        val affinity = mapOf("Tech" to 0.9, "Personal" to 0.7, "Science" to 0.5, "Local" to 0.4, "Art" to 0.2)

        val draft = standard.build(posts, feeds, EditionLength.STANDARD, release, affinity)

        assertEquals(posts.size, draft.items.size)
        assertEquals(posts.map { it.articleId }.toSet(), draft.items.map { it.articleId }.toSet())
        val sections = draft.sections()
        assertEquals(listOf("Tech", "Personal", "Science", "Local", "Art", "General"), sections.map { it.first })
        assertTrue(sections.all { it.second.size in 2..6 })
        // Every story sits in a section its feed is tagged with.
        val feedsById = feeds.associateBy { it.id }
        draft.items.filter { it.kind == ItemKind.SECTION }.forEach { item ->
            val feedTags = feedsById.getValue(item.feedId).tags
            if (item.section == "General") assertTrue(feedTags.isEmpty()) else assertTrue(item.section in feedTags)
        }
        // Pages come in the order front, Top stories, sections, In brief, index.
        fun order(kind: ItemKind) = when (kind) {
            ItemKind.LEAD, ItemKind.FRONT -> 0
            ItemKind.TOP -> 1
            ItemKind.SECTION -> 2
            ItemKind.BRIEF -> 3
            ItemKind.ARCHIVE -> 4
            else -> 5
        }
        val pageOrder = draft.items.groupBy { it.page }.toSortedMap().values.map { order(it.first().kind) }
        assertEquals(pageOrder.sorted(), pageOrder)
    }
}
