package net.tgnra.morningissue.server.simulation

import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Random
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The real feeds the simulation replays, and the deterministic generator that turns their
 * 10 to 40 real items into 180 days of posts. The fixtures and where they came from are
 * described in `src/test/resources/simulation/README.md`.
 */

/** One real item of a fixture: what a generated post is made from. */
class Template(
    val title: String,
    val link: String,
    val categories: List<String>,
    val enclosure: Enclosure?,
    /** The item's body, trimmed to its opening when the fixture was made. */
    val bodyHtml: String,
    /** The words of text in the item's full body when it was fetched (`<mi:words>`). */
    val originalWords: Int,
) {
    class Enclosure(val url: String, val type: String, val length: String)

    /** What the reader can tell at a glance: the post has code in it. */
    val hasCode: Boolean = bodyHtml.contains("<pre") || bodyHtml.contains("<code")
}

/** How often a feed posts. */
sealed interface Cadence {
    /** A Poisson number of posts a day, at times spread over [fromHour] until [toHour] UTC. */
    data class PerDay(val mean: Double, val fromHour: Int = 6, val toHour: Int = 23) : Cadence

    /** One post every [meanDays], give or take [jitterDays], at a time of day between 7 and 21 h. */
    data class EveryDays(val meanDays: Double, val jitterDays: Double) : Cadence
}

/**
 * A subscription: the fixture it replays, the address the server knows it by (the real one; the
 * simulated network answers for it, see [SimulatedInternet]), its tags (as an OPML import
 * would give them), its cadence, and how many items its document carries at a time.
 */
class FeedSpec(
    val key: String,
    val url: String,
    val tags: List<String>,
    val cadence: Cadence,
    val documentItems: Int,
) {
    lateinit var title: String
        private set
    lateinit var siteUrl: String
        private set
    lateinit var templates: List<Template>
        private set

    internal fun load() {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("simulation/feeds/$key.xml")) {
            "Missing fixture simulation/feeds/$key.xml"
        }
        val document = stream.use {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(it)
        }
        val channel = document.getElementsByTagName("channel").item(0) as Element
        title = channel.child("title")!!.textContent.trim()
        siteUrl = channel.child("link")!!.textContent.trim()
        val items = document.getElementsByTagName("item")
        templates = (0 until items.length).map { index ->
            val item = items.item(index) as Element
            val enclosure = item.children("enclosure").firstOrNull()
            Template(
                title = item.child("title")!!.textContent.trim(),
                link = item.child("link")!!.textContent.trim(),
                categories = item.children("category").map { it.textContent.trim() },
                enclosure = enclosure?.let { Template.Enclosure(it.getAttribute("url"), it.getAttribute("type"), it.getAttribute("length")) },
                bodyHtml = item.child("description")?.textContent.orEmpty(),
                originalWords = item.getElementsByTagNameNS(FIXTURE_NS, "words").item(0)?.textContent?.trim()?.toIntOrNull() ?: 0,
            )
        }
        check(templates.isNotEmpty()) { "$key has no items" }
    }

    private fun Element.children(name: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.localName == name && it.namespaceURI == null }
    }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()

    private companion object {
        const val FIXTURE_NS = "https://morningissue.invalid/fixture"
    }
}

/** The subscriptions every persona starts with. */
object SimulatedFeeds {
    const val BBC_NEWS = "bbc-news"
    const val BBC_SCOTLAND = "bbc-scotland"
    const val GUARDIAN_UK = "guardian-uk"
    const val GUARDIAN_BUSINESS = "guardian-business"
    const val JVNS = "jvns"
    const val SMITTEN = "smitten-kitchen"
    const val IRUNFAR = "irunfar"
    const val YARN_HARLOT = "yarn-harlot"
    const val BIKE_SNOB = "bike-snob"
    const val PHOTO = "35mmc"
    const val GAMES = "indie-games-plus"
    const val ATP = "atp"
    const val DARING_FIREBALL = "daring-fireball"
    const val DANLUU = "danluu"

    /**
     * Cadences follow each feed's real rate, rounded; see the README for the few nudged up.
     * Documents are shorter than the real ones (15 items for the news feeds, where the real
     * ones carry 30 to 40): with a sync every few hours no post is missed, and parsing is most
     * of the simulation's cost.
     */
    val all: List<FeedSpec> by lazy {
        listOf(
            FeedSpec(BBC_NEWS, "https://feeds.bbci.co.uk/news/rss.xml", listOf("News"), Cadence.PerDay(10.0), 15),
            FeedSpec(BBC_SCOTLAND, "https://feeds.bbci.co.uk/news/scotland/rss.xml", listOf("News"), Cadence.PerDay(6.0), 15),
            FeedSpec(GUARDIAN_UK, "https://www.theguardian.com/uk-news/rss", listOf("News"), Cadence.PerDay(4.0), 15),
            FeedSpec(GUARDIAN_BUSINESS, "https://www.theguardian.com/uk/business/rss", listOf("News"), Cadence.PerDay(9.0), 15),
            FeedSpec(JVNS, "https://jvns.ca/atom.xml", listOf("Tech"), Cadence.EveryDays(7.0, 3.0), 10),
            FeedSpec(SMITTEN, "https://smittenkitchen.com/feed/", listOf("Home"), Cadence.EveryDays(7.0, 3.0), 10),
            FeedSpec(IRUNFAR, "https://www.irunfar.com/feed", listOf("Outdoors"), Cadence.PerDay(1.5, 8, 20), 10),
            FeedSpec(YARN_HARLOT, "https://www.yarnharlot.ca/feed/", listOf("Home"), Cadence.EveryDays(7.0, 3.0), 10),
            FeedSpec(BIKE_SNOB, "https://bikesnobnyc.blogspot.com/feeds/posts/default", listOf("Outdoors"), Cadence.EveryDays(3.0, 1.5), 10),
            FeedSpec(PHOTO, "https://www.35mmc.com/feed/", listOf("Hobbies"), Cadence.PerDay(2.5, 7, 22), 10),
            FeedSpec(GAMES, "https://indiegamesplus.com/feed/", listOf("Hobbies"), Cadence.PerDay(0.5, 9, 21), 10),
            FeedSpec(ATP, "https://atp.fm/rss", listOf("Tech"), Cadence.EveryDays(7.0, 0.3), 10),
            FeedSpec(DARING_FIREBALL, "https://daringfireball.net/feeds/main", listOf("Tech"), Cadence.PerDay(3.0, 12, 23), 15),
            FeedSpec(DANLUU, "https://danluu.com/atom.xml", listOf("Tech"), Cadence.EveryDays(30.0, 10.0), 10),
        ).onEach { it.load() }
    }

    fun byKey(key: String): FeedSpec = all.single { it.key == key }
}

/**
 * One generated post: a template replayed at a new time, with its own GUID and address so the
 * app takes it for a new post.
 */
class SimPost(
    val feed: FeedSpec,
    /** The post's place in its feed's sequence, from 0. */
    val seq: Int,
    val template: Template,
    val publishedAt: Instant,
    val title: String,
) {
    val guid: String = "${feed.key}-$seq"
    val link: String = withMarker(template.link, seq)
    val categories: List<String> get() = template.categories

    /** The post's own link leaves the feed's site: a link blog's "go and read this". */
    val isLinkPost: Boolean = hostOf(template.link) != hostOf(feed.siteUrl)
    val hasCode: Boolean get() = template.hasCode
    val hasAudio: Boolean get() = template.enclosure?.type?.startsWith("audio") == true
    val words: Int get() = template.originalWords

    /** Title, then the summary's opening: what a persona's keyword rules look at. */
    val text: String by lazy { title + " " + template.bodyHtml.replace(TAG, " ").take(400) }

    override fun toString() = "$guid \"$title\""

    private companion object {
        val TAG = Regex("<[^>]+>")

        fun hostOf(url: String): String? = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()

        /** The template's address with a marker of this post, before any fragment. */
        fun withMarker(url: String, seq: Int): String {
            val hash = url.indexOf('#')
            val base = if (hash >= 0) url.substring(0, hash) else url
            return base + (if ('?' in base) "&" else "?") + "mi=" + seq
        }
    }
}

/**
 * Every post of every feed from well before the first simulated day to after the last,
 * generated once from [seed]. The same seed always gives the same posts at the same times.
 */
class PostSchedule(feeds: List<FeedSpec>, val start: Instant, days: Int, seed: Long) {
    /** Each feed's posts, oldest first. */
    val posts: Map<String, List<SimPost>>

    init {
        val from = start.minus(Duration.ofDays(HISTORY_DAYS))
        val until = start.plus(Duration.ofDays(days.toLong() + 1))
        posts = feeds.withIndex().associate { (index, feed) ->
            val random = Random(seed * 1_000_003 + index)
            // A seeded order of the templates, replayed in turn: each is used once before any is
            // used again. Drawn before the times, so that a shorter run is a prefix of a longer one.
            val order = feed.templates.indices.shuffled(random)
            val times = when (val cadence = feed.cadence) {
                is Cadence.PerDay -> perDay(cadence, from, until, random)
                is Cadence.EveryDays -> everyDays(cadence, from, until, random)
            }
            feed.key to times.mapIndexed { seq, at ->
                val template = feed.templates[order[seq % order.size]]
                val round = seq / order.size
                SimPost(feed, seq, template, at, varied(template.title, round))
            }
        }
    }

    /** Posts of [feed] published by [at], newest first, as many as its document carries. */
    fun documentAt(feed: FeedSpec, at: Instant): List<SimPost> {
        val all = posts.getValue(feed.key)
        var end = all.binarySearchBy(at) { it.publishedAt }.let { if (it >= 0) it + 1 else -it - 1 }
        while (end < all.size && !all[end].publishedAt.isAfter(at)) end++
        return all.subList(maxOf(0, end - feed.documentItems), end).asReversed()
    }

    fun byGuid(guid: String): SimPost? {
        val key = guid.substringBeforeLast('-')
        val seq = guid.substringAfterLast('-').toIntOrNull() ?: return null
        return posts[key]?.getOrNull(seq)
    }

    private fun perDay(cadence: Cadence.PerDay, from: Instant, until: Instant, random: Random): List<Instant> {
        val times = ArrayList<Instant>()
        var day = from
        while (day.isBefore(until)) {
            repeat(poisson(cadence.mean, random)) {
                val minutes = cadence.fromHour * 60 + random.nextInt((cadence.toHour - cadence.fromHour) * 60)
                times += day.plus(Duration.ofMinutes(minutes.toLong())).plusSeconds(random.nextInt(60).toLong())
            }
            day = day.plus(Duration.ofDays(1))
        }
        return times.sorted()
    }

    private fun everyDays(cadence: Cadence.EveryDays, from: Instant, until: Instant, random: Random): List<Instant> {
        val times = ArrayList<Instant>()
        // A random phase, so slow feeds do not all post on the same day.
        var at = from.plus(Duration.ofMinutes((random.nextDouble() * cadence.meanDays * 1440).toLong()))
        while (at.isBefore(until)) {
            val day = at.truncatedTo(java.time.temporal.ChronoUnit.DAYS)
            times += day.plus(Duration.ofMinutes(7L * 60 + random.nextInt(14 * 60)))
            val gap = cadence.meanDays + (random.nextDouble() * 2 - 1) * cadence.jitterDays
            at = at.plus(Duration.ofMinutes((gap.coerceAtLeast(0.5) * 1440).toLong()))
        }
        return times.sorted()
    }

    private fun poisson(mean: Double, random: Random): Int {
        val limit = Math.exp(-mean)
        var k = 0
        var p = random.nextDouble()
        while (p > limit) {
            k++
            p *= random.nextDouble()
        }
        return k
    }

    companion object {
        /** Posts are generated this far back, so a feed's first document has a history and a cadence. */
        const val HISTORY_DAYS = 365L

        /**
         * The title of a template's [round]th replay. Only a bracketed tail is added: it leaves
         * the subject (and the embedding) nearly as it was, and cannot create a title prefix.
         */
        fun varied(title: String, round: Int): String =
            if (round == 0) title else "$title (${VARIATIONS[(round - 1) % VARIATIONS.size]})"

        private val VARIATIONS = listOf("update", "follow-up", "latest", "revisited", "again", "more", "part two", "new")

        val RFC_1123: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
    }
}
