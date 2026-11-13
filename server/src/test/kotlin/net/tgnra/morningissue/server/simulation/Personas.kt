package net.tgnra.morningissue.server.simulation

import java.util.Random
import net.tgnra.morningissue.model.EditionPage
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.ATP
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.BBC_NEWS
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.BBC_SCOTLAND
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.BIKE_SNOB
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.DANLUU
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.DARING_FIREBALL
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.GUARDIAN_BUSINESS
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.GUARDIAN_UK
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.IRUNFAR
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.JVNS
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.SMITTEN
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.YARN_HARLOT

/** How the reader came to the paper on a day. */
enum class Visit {
    /** Did not open the app: the edition is built and superseded unread. */
    NONE,

    /** Opened the edition, looked at the front page and closed it again. */
    FRONT_ONLY,

    /** Went through the paper to the back page. */
    FULL,
}

/**
 * How likely the reader is to act on a post they see on a page: open it, and once opened
 * read it through, Like it, save it.
 */
/** How long a page was on screen, and whether the reader turned past it without reading it. */
class Glance(val millis: Long, val turnedPast: Boolean)

data class Interest(val open: Double, val read: Double = 0.8, val like: Double = 0.0, val save: Double = 0.0) {
    companion object {
        val NONE = Interest(0.0)
    }
}

/**
 * A simulated reader. Every rule is explicit and written out in [rules] for the report;
 * nothing is learned. Decisions are made on what the reader can see of a post (its feed,
 * title, the summary's opening, whether it is a link or carries code or audio): never on what
 * the server has stored about it.
 */
abstract class Persona(val name: String) {
    abstract val rules: List<String>

    /** Whether and how the reader opens the app on [day]. */
    abstract fun visit(day: Int, random: Random): Visit

    /** How the reader feels about [post] on [day], wherever it is printed. */
    abstract fun interest(day: Int, post: SimPost): Interest

    /** The persona's own label for a post, used by the assertions and the report; null for "nothing in particular". */
    open fun topic(post: SimPost): String? = null

    /**
     * How much of the open chance applies on a page: a headline in In brief is easier to pass
     * over than a story with a picture, and the back page's index is skimmed.
     */
    open fun pageFactor(kind: ItemKind): Double = when (kind) {
        ItemKind.BRIEF -> 0.6
        ItemKind.INDEX -> 0.2
        else -> 1.0
    }

    /**
     * How long [page] stays on screen while the reader looks at it, opened stories apart.
     * Every persona scans headlines alike: two seconds to take the page in, then two to four
     * seconds a story (one to two a line in In brief and the index), so even a page it opens
     * nothing on is up for well over five seconds. One page in ten after the front page is
     * turned past instead, in one to four seconds, and nothing on it is read. The same three
     * draws are made for every page.
     */
    open fun pageMillis(page: EditionPage, random: Random): Glance {
        val turnPast = random.nextDouble() < TURN_PAST_RATE
        val quick = 1_000L + random.nextInt(3_000)
        val perStory = if (page is EditionPage.InBrief || page is EditionPage.Back) 1_000 + random.nextInt(1_000) else 2_000 + random.nextInt(2_000)
        if (turnPast && page.number > 1) return Glance(quick, turnedPast = true)
        return Glance(2_000L + perStory.toLong() * page.stories.size, turnedPast = false)
    }

    companion object {
        const val TURN_PAST_RATE = 0.1

        /** The page-time rule, for every persona's report. */
        const val PAGE_RULE = "Each page looked at is on screen for 2 s plus 2-4 s a story (1-2 s a line in In brief and the index); " +
            "one page in ten after the front page is turned past in 1-4 s and not read, so its stories are not seen."
    }
}

/**
 * The habits all personas but the lapsed one share: about one day in fourteen missed, a
 * week's holiday, and about one day in eight with only the front page looked at.
 */
class Habits(
    val holiday: IntRange = 70..76,
    private val missRate: Double = 0.07,
    private val frontOnlyRate: Double = 0.12,
) {
    fun visit(day: Int, random: Random): Visit {
        // Both draws are made every day, so the rest of the run does not depend on the holiday.
        val miss = random.nextDouble() < missRate
        val frontOnly = random.nextDouble() < frontOnlyRate
        return when {
            day in holiday || miss -> Visit.NONE
            frontOnly -> Visit.FRONT_ONLY
            else -> Visit.FULL
        }
    }

    fun describe() = "Misses about 7% of days and every day of a holiday on days ${holiday.first}-${holiday.last}; " +
        "about 12% of visits look at the front page only. ${Persona.PAGE_RULE}"
}

private val SCOTLAND = Regex("(?i)\\b(scotland|scottish|glasgow|edinburgh|aberdeen|dundee|highlands?|holyrood|swinney|snp|inverness|fife|motherwell|dalkeith|prestwick|burns)\\b")

/** BBC Scotland's football coverage, by its headlines in the fixture. */
private val SCOTTISH_FOOTBALL = Regex("(?i)world cup|gossip|recall|aberdeen job|applicants|captain group|purgatory|mctominay|pocognoli")

private val NEWS = setOf(BBC_NEWS, BBC_SCOTLAND, GUARDIAN_UK, GUARDIAN_BUSINESS)

/**
 * 1. Reads the BBC's and the Guardian's Scottish news, never business, and leaves most other news.
 */
class ScotlandReader(private val habits: Habits = Habits()) : Persona("scotland-reader") {
    override val rules = listOf(
        "BBC Scotland: opens 85% of posts, reads 80% of those, Likes 10%; never opens its football posts (headline rules).",
        "BBC News: opens posts that mention Scotland (keywords) 80% of the time, anything else 4%.",
        "Guardian UK news: never opens a post filed under Business; opens Scottish posts 80%, the rest 30%.",
        "Guardian Business: never opens anything.",
        "Every other feed: opens 3%.",
        habits.describe(),
    )

    override fun visit(day: Int, random: Random) = habits.visit(day, random)

    override fun topic(post: SimPost): String? = when {
        "Business" in post.categories || post.feed.key == GUARDIAN_BUSINESS -> "business"
        post.feed.key == BBC_SCOTLAND && SCOTTISH_FOOTBALL.containsMatchIn(post.title) -> "football"
        post.feed.key == BBC_SCOTLAND || (post.feed.key in NEWS && (SCOTLAND.containsMatchIn(post.text) || "Scotland" in post.categories)) -> "scotland"
        else -> null
    }

    override fun interest(day: Int, post: SimPost): Interest = when (post.feed.key) {
        BBC_SCOTLAND -> if (topic(post) == "football") Interest.NONE else Interest(0.85, read = 0.8, like = 0.1)
        BBC_NEWS -> if (topic(post) == "scotland") Interest(0.8, read = 0.8) else Interest(0.04, read = 0.5)
        GUARDIAN_UK -> when (topic(post)) {
            "business" -> Interest.NONE
            "scotland" -> Interest(0.8, read = 0.8, like = 0.1)
            else -> Interest(0.3, read = 0.6)
        }
        GUARDIAN_BUSINESS -> Interest.NONE
        else -> Interest(0.03, read = 0.5)
    }
}

/**
 * 2. Loves a knitting blog and a cycling blog, skims the news, never opens a podcast.
 */
class Hobbyist(val habits: Habits = Habits()) : Persona("hobbyist") {
    val loved = setOf(YARN_HARLOT, BIKE_SNOB)

    override val rules = listOf(
        "Yarn Harlot and BIKESNOBNYC: opens 95% of posts, reads 90% of those, Likes 35% and saves 15%.",
        "News feeds (BBC, Guardian): opens 8%, reads half of those.",
        "Accidental Tech Podcast: never opens an episode.",
        "Every other feed: opens 6%.",
        habits.describe(),
    )

    override fun visit(day: Int, random: Random) = habits.visit(day, random)

    override fun topic(post: SimPost): String? = when (post.feed.key) {
        in loved -> "loved"
        ATP -> "podcast"
        else -> null
    }

    override fun interest(day: Int, post: SimPost): Interest = when (post.feed.key) {
        in loved -> Interest(0.95, read = 0.9, like = 0.35, save = 0.15)
        in NEWS -> Interest(0.08, read = 0.5)
        ATP -> Interest.NONE
        else -> Interest(0.06, read = 0.5)
    }

    // A loved blog's post is not passed over just because it is on the back page.
    override fun pageFactor(kind: ItemKind): Double = if (kind == ItemKind.INDEX) 0.5 else super.pageFactor(kind)
}

/**
 * 3. Reads posts with code and long-form tech writing; skips link posts.
 */
class TechReader(private val habits: Habits = Habits()) : Persona("tech-reader") {
    override val rules = listOf(
        "Daring Fireball: opens 85% of its own articles, reads 85%, Likes 15%; opens 4% of its link posts.",
        "Julia Evans: opens 95% of posts with code (reads 90%, Likes 30%), 45% of the others.",
        "Dan Luu (long-form): opens 95%, reads 90%, Likes 40%, saves 30%.",
        "Accidental Tech Podcast: opens 25%.",
        "News feeds: opens 5%. Every other feed: opens 3%.",
        habits.describe(),
    )

    override fun visit(day: Int, random: Random) = habits.visit(day, random)

    override fun topic(post: SimPost): String? = when {
        post.feed.key == DARING_FIREBALL -> if (post.isLinkPost) "link" else "article"
        post.feed.key == JVNS -> if (post.hasCode) "code" else "prose"
        post.feed.key == DANLUU -> "long-form"
        else -> null
    }

    override fun interest(day: Int, post: SimPost): Interest = when (post.feed.key) {
        DARING_FIREBALL -> if (post.isLinkPost) Interest(0.04, read = 0.3) else Interest(0.85, read = 0.85, like = 0.15)
        JVNS -> if (post.hasCode) Interest(0.95, read = 0.9, like = 0.3) else Interest(0.45, read = 0.6)
        DANLUU -> Interest(0.95, read = 0.9, like = 0.4, save = 0.3)
        ATP -> Interest(0.25, read = 0.5)
        in NEWS -> Interest(0.05, read = 0.5)
        else -> Interest(0.03, read = 0.5)
    }
}

/**
 * 4. Loves cooking until [shiftDay], then loses interest in it and takes up running.
 */
class Drifter(private val habits: Habits = Habits(), val shiftDay: Int = 120) : Persona("drifter") {
    override val rules = listOf(
        "Until day $shiftDay: smitten kitchen opens 95% (reads 90%, Likes 30%); iRunFar opens 5%.",
        "From day $shiftDay: smitten kitchen opens 5%; iRunFar opens 90% (reads 80%, Likes 25%).",
        "News feeds: opens 12%. Every other feed: opens 5%.",
        habits.describe(),
    )

    override fun visit(day: Int, random: Random) = habits.visit(day, random)

    override fun topic(post: SimPost): String? = when (post.feed.key) {
        SMITTEN -> "cooking"
        IRUNFAR -> "running"
        else -> null
    }

    override fun interest(day: Int, post: SimPost): Interest {
        val before = day < shiftDay
        return when (post.feed.key) {
            SMITTEN -> if (before) Interest(0.95, read = 0.9, like = 0.3) else Interest(0.05, read = 0.5)
            IRUNFAR -> if (before) Interest(0.05, read = 0.5) else Interest(0.9, read = 0.8, like = 0.25)
            in NEWS -> Interest(0.12, read = 0.6)
            else -> Interest(0.05, read = 0.5)
        }
    }
}

/**
 * 5. Rarely opens the app: long, irregular gaps, and a story or two when they do.
 */
class LapsedReader : Persona("lapsed-reader") {
    private var nextVisit = 0

    override val rules = listOf(
        "Opens the app after gaps of 4 to 13 days (and not at all on days 100-125), front page only on half the visits.",
        "On a visit: opens 10% of what it sees from any feed, reads half of those.",
        PAGE_RULE,
    )

    override fun visit(day: Int, random: Random): Visit {
        val gap = 4 + random.nextInt(10)
        val frontOnly = random.nextBoolean()
        if (day < nextVisit || day in 100..125) return Visit.NONE
        nextVisit = day + gap
        return if (frontOnly) Visit.FRONT_ONLY else Visit.FULL
    }

    override fun interest(day: Int, post: SimPost) = Interest(0.1, read = 0.5)
}

/**
 * The baseline: opens what it sees at random, whatever the feed or the post, at a rate that
 * puts the expected engagement at the affinity prior's own neutral 0.5 "posts read" per post
 * printed (0.55 × (1 + 2 × 0.8 + 3 × 0.05) / 3 ≈ 0.50). Nothing it does says anything about
 * taste, so nothing should be learned.
 */
class RandomReader(private val habits: Habits = Habits()) : Persona("random-reader") {
    override val rules = listOf(
        "Opens 55% of every printed story it sees, on any page and from any feed; reads 80% of those, Likes 5%.",
        "Never opens the back page's index: an index entry is no exposure unless opened, so opening entries at random " +
            "would credit the feeds with the most unprinted posts.",
        habits.describe(),
    )

    override fun visit(day: Int, random: Random) = habits.visit(day, random)

    override fun interest(day: Int, post: SimPost) = Interest(0.55, read = 0.8, like = 0.05)

    override fun pageFactor(kind: ItemKind) = if (kind == ItemKind.INDEX) 0.0 else 1.0
}
