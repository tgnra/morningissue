package net.tgnra.morningissue.ui.debug

import androidx.room3.withWriteTransaction
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.data.db.ArticleEntity
import net.tgnra.morningissue.data.db.EditionEntity
import net.tgnra.morningissue.data.db.EditionItemEntity
import net.tgnra.morningissue.data.db.FeedEntity
import net.tgnra.morningissue.data.db.MorningIssueDatabase
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ContentBlocks
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.InlineSpan
import net.tgnra.morningissue.model.InlineStyle
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.model.SliderStep

/**
 * Debug builds only: fills the database with invented feeds, articles and released editions so
 * the UI can be developed and looked at before sync and the edition builder exist. Every page
 * type appears in at least one edition. Nothing here ships: this file is in the debug source set.
 *
 * Feeds and headlines are made up; pictures come from picsum.photos and need a connection.
 */
class SampleDataSeeder(
    private val database: MorningIssueDatabase,
    private val clock: Clock,
    private val resetTime: LocalTime,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /**
     * Days by which the newest edition is behind the current one. 0 seeds the current edition;
     * 1 leaves the stand as it is on a day the feeds had nothing new ("No new edition today").
     */
    private val newestDaysBehind: Int = 0,
) {
    private class SampleFeed(
        val title: String,
        val slug: String,
        val tier: CadenceTier,
        /** Null leaves cadence unknown, like a feed that has only just been added. */
        val medianHours: Double?,
        val tags: List<String>,
        val author: String?,
        /** Hand-written headlines; feeds without them get generated news headlines. */
        val headlines: List<String> = emptyList(),
        /** Posts carry body text. Wire-style feeds give a summary only. */
        val hasBody: Boolean = true,
        /** The slider; between them the feeds cover every step. */
        val step: SliderStep = SliderStep.AUTO,
        /** Learned affinity, as if the reader had a history with the feed. */
        val affinity: Double = 0.5,
        /** Days of mute still to run. */
        val mutedForDays: Int? = null,
    )

    private val feeds = listOf(
        SampleFeed("The Lathe", "the-lathe", CadenceTier.DAILY, 7.0, listOf("Tech"), "Ines Okafor", TECH_HEADLINES.take(8), step = SliderStep.MORE, affinity = 0.74),
        SampleFeed("Wirefall", "wirefall", CadenceTier.FIREHOSE, 0.9, listOf("Tech"), null, step = SliderStep.LESS, affinity = 0.31),
        SampleFeed("Port 8080", "port-8080", CadenceTier.FIREHOSE, 0.4, emptyList(), null, hasBody = false, step = SliderStep.RARELY, affinity = 0.22),
        SampleFeed("Plain Text Weekly", "plain-text-weekly", CadenceTier.WEEKLY, 150.0, listOf("Tech", "Writing"), "Marek Dvořák", TECH_HEADLINES.drop(8), affinity = 0.66),
        SampleFeed("Harbour Lights", "harbour-lights", CadenceTier.RARE, 700.0, listOf("Personal"), "Aoife Brennan", PERSONAL_HEADLINES.take(5), step = SliderStep.FAVOURITE, affinity = 0.9),
        SampleFeed("Field Notes from the North", "field-notes", CadenceTier.RARE, 520.0, listOf("Personal", "Writing"), "Sören Lindqvist", PERSONAL_HEADLINES.drop(5), affinity = 0.71),
        SampleFeed("The Slow Almanac", "slow-almanac", CadenceTier.RARE, 900.0, listOf("Science"), "Dr. Hana Kimura", SCIENCE_HEADLINES.take(4), step = SliderStep.FAVOURITE, affinity = 0.83),
        SampleFeed("Tidewater Review", "tidewater", CadenceTier.WEEKLY, 60.0, listOf("Culture"), "Camille Arnaud", CULTURE_HEADLINES.take(5), step = SliderStep.MORE),
        SampleFeed("Signal & Margin", "signal-margin", CadenceTier.WEEKLY, 34.0, listOf("Science"), "Tobias Reyes", SCIENCE_HEADLINES.drop(4)),
        SampleFeed("The Daily Kettle", "daily-kettle", CadenceTier.DAILY, 5.0, listOf("Culture"), "Priya Raman", CULTURE_HEADLINES.drop(5), step = SliderStep.LESS, mutedForDays = 6),
        SampleFeed("Open Bench", "open-bench", CadenceTier.DAILY, 11.0, listOf("Tech"), null),
        SampleFeed("Corner Shop Radio", "corner-shop-radio", CadenceTier.WEEKLY, null, emptyList(), null, hasBody = false),
        SampleFeed("The Allotment Diary", "allotment-diary", CadenceTier.WEEKLY, 90.0, emptyList(), "Wim de Groot"),
    )

    private class Stored(val id: Long, val feed: SampleFeed, val hasImage: Boolean)

    /** Wipes everything and seeds afresh. Returns a line describing what was stored. */
    suspend fun seed(): String = withContext(Dispatchers.IO) {
        database.clearAllTables()
        val random = Random(SEED)
        var articleCount = 0
        var editionCount = 0
        database.withWriteTransaction {
            val now = clock.instant()
            val feedIds = feeds.associateWith { feed ->
                val id = database.feedDao().insert(
                    FeedEntity(
                        url = "https://example.org/${feed.slug}/feed.xml",
                        siteUrl = "https://example.org/${feed.slug}",
                        title = feed.title,
                        medianIntervalHrs = feed.medianHours,
                        burstiness = feed.medianHours?.let { 2.5 },
                        tier = feed.tier,
                        affinity = feed.affinity,
                        sliderStep = feed.step,
                        muteUntil = feed.mutedForDays?.let { now.plus(Duration.ofDays(it.toLong())) },
                        lastSyncAt = now,
                        addedAt = now.minus(Duration.ofDays(60)),
                    ),
                )
                database.tagDao().setFeedTags(id, feed.tags)
                id
            }

            // Before the reset time today's paper is not out yet, so the newest edition is yesterday's.
            val today = now.atZone(zone).toLocalDate()
            val current = if (now.atZone(zone).toLocalTime().isBefore(resetTime)) today.minusDays(1) else today
            val newest = current.minusDays(newestDaysBehind.toLong())
            val headlineCursor = HashMap<SampleFeed, Int>()
            var newsCursor = 0
            val news = NEWS_SUBJECTS.flatMap { subject -> NEWS_PREDICATES.map { "$subject $it" } }.shuffled(random)

            suspend fun store(feed: SampleFeed, seenAt: Instant, publishedAt: Instant = seenAt): Stored {
                val index = articleCount++
                val own = headlineCursor.getOrDefault(feed, 0)
                val title = if (own < feed.headlines.size) {
                    headlineCursor[feed] = own + 1
                    feed.headlines[own]
                } else {
                    news[newsCursor++ % news.size]
                }
                val longRead = feed.headlines.isNotEmpty()
                val hasImage = feed.hasBody && random.nextInt(100) < if (longRead) 70 else 45
                val imageUrl = if (hasImage) "https://picsum.photos/seed/morning-issue-$index/1200/800" else null
                val blocks = if (feed.hasBody) body(random, index, longRead) else emptyList()
                val text = ContentBlocks.plainText(blocks)
                val id = database.articleDao().insertNew(
                    article = ArticleEntity(
                        feedId = feedIds.getValue(feed),
                        guid = "sample-$index",
                        url = "https://example.org/${feed.slug}/posts/$index",
                        title = title,
                        summary = STANDFIRSTS[index % STANDFIRSTS.size],
                        imageUrl = imageUrl,
                        author = feed.author,
                        publishedAt = publishedAt,
                        firstSeenAt = seenAt,
                        wordCount = if (feed.hasBody) ContentBlocks.wordCount(text) else 0,
                        feedWordCount = if (feed.hasBody) ContentBlocks.wordCount(text) else 0,
                    ),
                    blocksJson = if (feed.hasBody) ContentBlocks.encode(blocks) else null,
                    plainText = text.ifEmpty { null },
                ) ?: error("sample article $index was not stored")
                return Stored(id, feed, hasImage)
            }

            val slow = feeds.filter { it.tier == CadenceTier.RARE || it.tier == CadenceTier.WEEKLY }.filter { it.hasBody }
            val quick = feeds.filter { it.tier == CadenceTier.FIREHOSE || it.tier == CadenceTier.DAILY }
            val wire = feeds.filter { it.tier == CadenceTier.FIREHOSE }

            // Newest first, so today's paper gets the hand-written headlines; numbers still run oldest to newest.
            EDITION_DAYS_AGO.sorted().forEachIndexed { order, daysAgo ->
                val number = EDITION_DAYS_AGO.size - 1 - order
                val date: LocalDate = newest.minusDays(daysAgo.toLong())
                val releaseAt = date.atTime(resetTime).atZone(zone).toInstant()
                val cutoffAt = releaseAt.minus(Duration.ofMinutes(75))
                val isNewest = daysAgo == 0
                val afterGap = (daysAgo + 1) !in EDITION_DAYS_AGO && daysAgo != EDITION_DAYS_AGO.max()
                fun seenAt(): Instant = cutoffAt.minus(Duration.ofMinutes(random.nextLong(10, 23 * 60)))

                val items = ArrayList<EditionItemEntity>()
                var page = 0
                suspend fun place(kind: ItemKind, feedsToUse: List<SampleFeed>, count: Int, section: String? = null, seen: () -> Instant = ::seenAt, published: ((Instant) -> Instant)? = null): List<Stored> {
                    if (kind != ItemKind.FRONT && kind != ItemKind.INDEX) page++
                    return (0 until count).map { slot ->
                        val seenInstant = seen()
                        val stored = store(feedsToUse[(slot + number) % feedsToUse.size], seenInstant, published?.invoke(seenInstant) ?: seenInstant)
                        items += EditionItemEntity(
                            editionId = 0,
                            articleId = stored.id,
                            page = if (kind == ItemKind.INDEX) 0 else page,
                            section = section,
                            slot = if (kind == ItemKind.FRONT) slot + 1 else slot,
                            kind = kind,
                            score = 1.0 - slot * 0.03 - page * 0.1,
                            // Some stories carry no reason, so the fallback wording is seen too.
                            reason = reasonFor(kind, section, slot + number),
                        )
                        stored
                    }
                }

                // Front page: the lead (from a slow, loved feed) and four more, one per feed.
                var lead = place(ItemKind.LEAD, slow.drop(number % slow.size) + slow, 1).single()
                // Leads come with an ordinary photo, without one, and with pictures of awkward
                // shapes (a tall comic strip, a wide panorama) so every framing can be seen.
                val leadImage = when (daysAgo) {
                    0 -> "1200/800"
                    1 -> "600/1700"
                    2 -> null
                    3 -> "1600/420"
                    5 -> "800/1100"
                    else -> if (number % 2 == 0) "1200/800" else null
                }
                database.articleDao().getById(lead.id)?.let {
                    database.articleDao().update(it.copy(imageUrl = leadImage?.let { size -> "https://picsum.photos/seed/morning-issue-lead-$number/$size" }))
                }
                lead = Stored(lead.id, lead.feed, leadImage != null)
                place(ItemKind.FRONT, quick + slow, 4)
                if (afterGap) place(ItemKind.AWAY, slow + quick.filter { it.tier == CadenceTier.DAILY }, 6, seen = { cutoffAt.minus(Duration.ofHours(random.nextLong(26, 70))) })
                place(ItemKind.TOP, quick + slow, if (isNewest) 8 else 6)
                if (isNewest) place(ItemKind.TOP, slow + quick, 7)
                if (isNewest || number % 3 == 0) {
                    // A page per tag, each a different size, then General for the untagged feeds.
                    place(ItemKind.SECTION, feeds.filter { "Tech" in it.tags }, 6, section = "Tech")
                    place(ItemKind.SECTION, feeds.filter { "Culture" in it.tags }, 4, section = "Culture")
                    place(ItemKind.SECTION, feeds.filter { "Science" in it.tags }, 3, section = "Science")
                    if (isNewest) {
                        place(ItemKind.SECTION, feeds.filter { "Personal" in it.tags }, 2, section = "Personal")
                        place(ItemKind.SECTION, feeds.filter { it.tags.isEmpty() }, 5, section = "General")
                    }
                }
                place(ItemKind.BRIEF, wire + quick, if (isNewest) 15 else 9)
                if (isNewest || number % 2 == 1) {
                    // Held over from a rare feed: first seen days ago, published earlier still.
                    val archive = place(
                        kind = ItemKind.ARCHIVE,
                        feedsToUse = feeds.filter { it.tier == CadenceTier.RARE },
                        count = 1,
                        seen = { cutoffAt.minus(Duration.ofDays(random.nextLong(12, 40))) },
                        published = { it.minus(Duration.ofDays(random.nextLong(200, 900))) },
                    ).single()
                    // The newest archive story has a picture; the others show the feature without one.
                    database.articleDao().getById(archive.id)?.let {
                        database.articleDao().update(it.copy(imageUrl = if (isNewest) "https://picsum.photos/seed/morning-issue-archive-$number/1200/800" else null))
                    }
                }
                place(ItemKind.INDEX, wire + quick + wire, if (isNewest) 22 else 10)

                // Older papers have been read to varying degrees; the newest is untouched.
                val finished = !isNewest && number % 4 != 2
                val editionId = database.editionDao().insertEditionWithItems(
                    EditionEntity(
                        date = date,
                        kind = EditionKind.MORNING,
                        cutoffAt = cutoffAt,
                        releaseAt = releaseAt,
                        builtAt = cutoffAt.plusSeconds(20),
                        length = EditionLength.STANDARD,
                        number = number + 1,
                        openedAt = if (isNewest) null else releaseAt.plus(Duration.ofHours(1)),
                        finishedAt = if (finished) releaseAt.plus(Duration.ofHours(2)) else null,
                    ),
                    items,
                )
                editionCount++
                if (!isNewest) {
                    items.filter { it.kind != ItemKind.INDEX }.forEachIndexed { index, item ->
                        if (index % 3 != 2) {
                            val at = releaseAt.plus(Duration.ofMinutes(60L + index))
                            database.engagementDao().markOpened(item.articleId, at)
                            if (index % 3 == 0) {
                                database.engagementDao().recordReadFraction(item.articleId, 1f)
                                database.engagementDao().markRead(item.articleId, at)
                            }
                            if (index % 11 == 0) database.engagementDao().setLiked(item.articleId, at)
                            if (index % 13 == 0) database.engagementDao().setSaved(item.articleId, at)
                        }
                    }
                    database.engagementDao().countUnopenedRun(editionId)
                } else {
                    // Today's paper has been started: a few stories read, liked or saved on each
                    // page, so the marks can be seen without touching anything.
                    val at = releaseAt.plus(Duration.ofMinutes(20))
                    items.forEachIndexed { index, item ->
                        when (index % 9) {
                            3 -> {
                                database.engagementDao().markOpened(item.articleId, at)
                                database.engagementDao().recordReadFraction(item.articleId, 1f)
                                database.engagementDao().markRead(item.articleId, at)
                                if (index % 2 == 1) database.engagementDao().setLiked(item.articleId, at.plusSeconds(index.toLong()))
                            }
                            6 -> database.engagementDao().setSaved(item.articleId, at.plusSeconds(index.toLong()))
                        }
                    }
                }
            }
        }
        "Stored ${feeds.size} feeds, $articleCount articles and $editionCount editions."
    }

    suspend fun clear() = withContext(Dispatchers.IO) { database.clearAllTables() }

    /** The builder's one-line reason, in the manner of v1's examples; null for a share of stories. */
    private fun reasonFor(kind: ItemKind, section: String?, turn: Int): String? = when (kind) {
        ItemKind.LEAD -> "Rare post: this source publishes about once a month"
        ItemKind.FRONT, ItemKind.TOP -> REASONS[turn % REASONS.size]
        ItemKind.SECTION -> if (turn % 3 == 0) null else "Top of your $section sources today"
        ItemKind.BRIEF -> if (turn % 2 == 0) "Fresh from a fast-moving source you follow" else null
        ItemKind.ARCHIVE -> "Held over from a source you love: still unread after three weeks"
        ItemKind.AWAY -> "From an edition you missed, ranked from your last visit"
        ItemKind.READING_LIST -> "From your reading list: you saved this from a source you don't subscribe to"
        ItemKind.INDEX -> null
    }

    /** An article body exercising every block type the reader renders. */
    private fun body(random: Random, index: Int, longRead: Boolean): List<ContentBlock> {
        fun paragraph(sentences: Int = random.nextInt(3, 6)): String =
            (0 until sentences).joinToString(" ") { SENTENCES[random.nextInt(SENTENCES.size)] }

        fun styled(text: String): RichText {
            val spans = ArrayList<InlineSpan>()
            val firstStop = text.indexOf('.').takeIf { it > 12 } ?: return RichText(text)
            when (random.nextInt(4)) {
                0 -> spans += InlineSpan(0, firstStop, InlineStyle.BOLD)
                1 -> spans += InlineSpan(0, firstStop, InlineStyle.ITALIC)
                2 -> spans += InlineSpan(firstStop - 12, firstStop, InlineStyle.LINK, href = "https://example.org/reference/$index")
            }
            return RichText(text, spans)
        }

        val blocks = ArrayList<ContentBlock>()
        blocks += ContentBlock.Paragraph(styled(paragraph()))
        blocks += ContentBlock.Paragraph(RichText(paragraph()))
        if (longRead || random.nextBoolean()) {
            blocks += ContentBlock.Image(
                url = "https://picsum.photos/seed/morning-issue-body-$index/1200/750",
                alt = "A photograph accompanying the article",
                caption = RichText("The view from the workshop window, late in the afternoon."),
                width = 1200,
                height = 750,
            )
        }
        blocks += ContentBlock.Heading(2, RichText(HEADINGS[index % HEADINGS.size]))
        blocks += ContentBlock.Paragraph(styled(paragraph()))
        blocks += ContentBlock.Quote(
            paragraphs = listOf(RichText(QUOTES[index % QUOTES.size])),
            citation = RichText(CITATIONS[index % CITATIONS.size]),
        )
        blocks += ContentBlock.Paragraph(RichText(paragraph()))
        blocks += ContentBlock.ItemList(
            ordered = index % 2 == 0,
            items = listOf(
                RichText("Start with the smallest thing that could possibly work."),
                RichText("Measure it honestly, then leave it alone for a week.", listOf(InlineSpan(11, 19, InlineStyle.ITALIC))),
                RichText("Write down what surprised you before you forget."),
            ),
        )
        if (index % 3 == 0) {
            blocks += ContentBlock.Heading(3, RichText("A small example"))
            blocks += ContentBlock.Preformatted(
                text = "fun halfLife(medianHours: Double): Double =\n    (1.5 * medianHours).coerceIn(12.0, 240.0)\n\nval freshness = 0.5.pow(ageHours / halfLife(medianHours))",
                language = "kotlin",
            )
            blocks += ContentBlock.Paragraph(
                RichText("The whole rule fits in a call to coerceIn, which is rather the point.", listOf(InlineSpan(33, 41, InlineStyle.CODE))),
            )
        }
        repeat(if (longRead) random.nextInt(6, 12) else random.nextInt(1, 4)) {
            blocks += ContentBlock.Paragraph(styled(paragraph()))
        }
        return blocks
    }

    private companion object {
        const val SEED = 20261110

        /** Days before the newest edition that have an edition; the missing day is a skipped one. */
        val EDITION_DAYS_AGO = listOf(0, 1, 2, 3, 5, 6, 7, 8, 9, 33, 34)

        val TECH_HEADLINES = listOf(
            "SQLite as an Application File Format, Revisited",
            "Things I Learned Writing a Feed Parser",
            "A Debugging Story: The Time Zone That Wasn’t",
            "Why Our Team Went Back to Server-Rendered Pages",
            "The Smallest Useful Search Index",
            "Notes on Reading Other People’s Code",
            "Dithering for E-ink, Explained With Pictures",
            "What Every Programmer Gets Wrong About Dates",
            "Making a Static Site Last Thirty Years",
            "The Unreasonable Effectiveness of Plain Text",
            "How I Stopped Fighting My Build System",
            "A Gentle Introduction to Cadence-Aware Ranking",
        )
        val PERSONAL_HEADLINES = listOf(
            "Walking the Old Coast Road in a Week of Rain",
            "What the Ferry Timetable Taught Me About Time",
            "A Year Without a Camera in My Pocket",
            "Notes on Building a Desk That Will Outlive Me",
            "The Quiet Pleasure of Maintaining Things",
            "Letters From a Lighthouse Keeper’s Granddaughter",
            "On Keeping a Paper Notebook for Ten Years",
            "The Last Typewriter Repair Shop in the City",
            "Why I Still Write the Newsletter by Hand First",
            "The Library That Lends Tools Instead of Books",
        )
        val SCIENCE_HEADLINES = listOf(
            "A Wind-Powered Website, Five Years On",
            "The Forgotten History of Compressed-Air Trams",
            "What a 400-Year-Old Tide Table Still Gets Right",
            "The Mathematics Hidden in a Sourdough Starter",
            "How Starlings Decide Where the Flock Turns",
            "Why the Ocean’s Twilight Zone Matters More Than We Thought",
            "Mapping a Forest by the Sounds It Makes",
            "The Long Search for a Perfectly Round Number",
            "Counting Every Tree in a City, Twice",
        )
        val CULTURE_HEADLINES = listOf(
            "The Typeface That Rebuilt a City’s Signs",
            "Reading the Margins: What Old Annotations Reveal",
            "A Short History of the Newspaper Front Page",
            "The Photographer Who Only Worked at Dawn",
            "The Case for Smaller Museums",
            "How a Village Choir Ended Up on a Film Soundtrack",
            "What We Lost When Maps Stopped Folding",
            "Forty Years of the Same Crossword Setter",
            "The Bookshop That Sells One Title a Week",
            "A Field Guide to Railway Station Clocks",
        )
        val NEWS_SUBJECTS = listOf(
            "A long-running open-source editor", "The regional rail operator", "A two-person browser team",
            "The national library", "A small phone maker", "The city’s bike-share scheme", "A veteran database project",
            "The standards body", "An independent search engine", "A community mapping project", "The public broadcaster",
            "A font foundry", "The observatory", "A repair co-operative", "An e-reader maker", "The postal service",
            "A solar-powered server farm", "The university press", "A Linux distribution", "The transit authority",
        )
        val NEWS_PREDICATES = listOf(
            "ships its biggest update in years", "quietly drops support for older hardware",
            "publishes a frank account of last week’s outage", "proposes a simpler standard for feeds",
            "opens its archive to researchers", "finally gets a dark mode", "wins a ruling on repairability",
            "moves to a six-week release cycle", "turns twenty-five", "cuts its build times in half",
            "raises prices for the first time since 2019", "explains why the old way was better",
            "finds a decade-old bug in a date parser", "partners with libraries on a lending scheme",
            "releases a tiny e-ink reference design", "says it will stay independent",
        )
        val STANDFIRSTS = listOf(
            "A long look at a small subject, and why it turned out to matter more than anyone expected when the work began.",
            "It started as a weekend experiment. Three years later it is still running, and the lessons have been surprising.",
            "The obvious answer was wrong, the clever answer was worse, and the boring one has now lasted a decade.",
            "What happens when you take an old idea seriously, follow it all the way through, and write down what you find.",
            "Nobody set out to build it this way. The reasons it ended up like this say a lot about how good tools get made.",
            "A reader’s question led to a week in the archives, a few dead ends and one very good story.",
            "The numbers are small and the stakes are low, which is exactly what makes it worth doing carefully.",
            "On patience, maintenance, and the particular satisfaction of things that keep working.",
        )
        val SENTENCES = listOf(
            "The first version took an afternoon and worked better than it had any right to.",
            "Most of what followed was a matter of taking things away.",
            "There is a kind of care that only shows up after the third or fourth year of looking after something.",
            "Nobody I asked could remember when the practice had started, only that it had always been done this way.",
            "The measurements told one story and the people using it told another, and both were true.",
            "I kept a notebook through all of it, mostly so that I could be wrong on paper rather than in my head.",
            "What looked like a limitation turned out to be the feature everyone came to rely on.",
            "It rained for most of the week, which gave me time to read the whole manual for once.",
            "A good default is a decision somebody made carefully so that thousands of others would not have to.",
            "The old system had exactly one moving part, and it was a person named Ruth.",
            "By the time the light changed we had stopped talking about the plan and started simply doing it.",
            "You can learn a great deal about a tool from the shape of its error messages.",
            "The cost was never the building; it was the remembering why it had been built that way.",
            "Every so often the simplest explanation is also the one with the best photographs.",
            "We tried the fashionable approach for a season and then quietly went back.",
            "It is slower than the alternative, and I have stopped apologising for that.",
            "The archive goes back further than the organisation that keeps it.",
            "None of this is new, which is the most encouraging thing about it.",
            "The trick, if there is one, is to finish each day at a point where tomorrow’s first step is obvious.",
            "Small things, done at the same time each day, add up to something that looks from a distance like a plan.",
        )
        val HEADINGS = listOf("How it began", "What the numbers say", "The part nobody mentions", "Doing less, on purpose", "A short detour", "What I would keep")
        val QUOTES = listOf(
            "We did not make it faster. We made it stop doing the things nobody had asked for.",
            "A newspaper is finished every morning. That is its whole advantage over everything that came after.",
            "If it still works in ten years, it was probably simple. If it was simple, somebody worked hard on it.",
            "I have never regretted writing something down, only the days I trusted myself to remember.",
        )
        val CITATIONS = listOf("from the project’s tenth-anniversary notes", "a night editor, in conversation", "the maintainer, in a mailing-list reply", "a reader’s letter")
        val REASONS = listOf(
            "Rare post: this source publishes about once a month",
            "You read most posts from this source",
            null,
            "Fresh from a source you set to Favourite",
            "You liked three of its last five posts",
        )
    }
}
