package net.tgnra.morningissue.simulation

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.simulation.SimulatedFeeds.ATP
import net.tgnra.morningissue.simulation.SimulatedFeeds.BBC_SCOTLAND
import net.tgnra.morningissue.simulation.SimulatedFeeds.DANLUU
import net.tgnra.morningissue.simulation.SimulatedFeeds.DARING_FIREBALL
import net.tgnra.morningissue.simulation.SimulatedFeeds.GUARDIAN_BUSINESS
import net.tgnra.morningissue.simulation.SimulatedFeeds.GUARDIAN_UK
import net.tgnra.morningissue.simulation.SimulatedFeeds.IRUNFAR
import net.tgnra.morningissue.simulation.SimulatedFeeds.JVNS
import net.tgnra.morningissue.simulation.SimulatedFeeds.SMITTEN
import net.tgnra.morningissue.simulation.SimulationRuns.DRIFTER
import net.tgnra.morningissue.simulation.SimulationRuns.HOBBYIST
import net.tgnra.morningissue.simulation.SimulationRuns.LAPSED
import net.tgnra.morningissue.simulation.SimulationRuns.RANDOM
import net.tgnra.morningissue.simulation.SimulationRuns.SCOTLAND
import net.tgnra.morningissue.simulation.SimulationRuns.TECH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A regression suite for ranking: simulated readers use the app for 180 days (see
 * [Simulation] for what runs, [Personas] for who reads, [SimulatedFeeds] for the feeds), and
 * the assertions check that the paper learns their taste.
 *
 * The assertions are thresholds and orderings measured over many days after a warm-up, not
 * snapshots. Each states what was measured when it was written, so a regression can be
 * told from noise: the run is deterministic, so a change in a number is a change in the
 * engine (or the fixtures). Set `-PsimulationReport` to get each persona's report, with the
 * numbers each assertion measured, in `app/build/reports/simulation/`.
 *
 * All personas run once per JVM, four at a time ([SimulationRuns]); the first test to ask
 * waits for them (about a minute and a half on a laptop).
 */
@RunWith(AndroidJUnit4::class)
class RankingSimulationTest {
    private val runs get() = SimulationRuns.get(ApplicationProvider.getApplicationContext())

    // -- Scotland reader ------------------------------------------------------------------

    @Test
    fun scotlandReader() = checks(runs[SCOTLAND]) { r ->
        val after = r.from(WARM_UP)
        val affinity = r.meanAffinity(after)
        // Measured: BBC Scotland 0.46; no other feed above 0.15.
        check(affinity.maxByOrNull { it.value }!!.key == BBC_SCOTLAND, "BBC Scotland is the feed with the highest affinity", affinity.fmt())
        check(affinity.getValue(GUARDIAN_BUSINESS) < 0.05, "the never-opened Guardian Business feed sinks below 0.05", affinity.getValue(GUARDIAN_BUSINESS).f())

        // Measured: 93% of editions after the warm-up.
        val scottishFront = after.share { day -> day.items.any { it.onFront && it.topic == "scotland" } }
        check(scottishFront >= 0.9, "a Scottish story is on the front page of at least 90% of editions after day $WARM_UP", scottishFront.pct())

        // Within BBC Scotland (no categories: only the text taste and title prefixes can tell
        // its posts apart), football goes down and the rest up. Measured: 0.88 against 1.02.
        val bbcScotland = after.items(BBC_SCOTLAND)
        val football = bbcScotland.filter { it.topic == "football" }.meanW()
        val scottish = bbcScotland.filter { it.topic == "scotland" }.meanW()
        check(football < 0.95, "BBC Scotland's football posts have a mean W under 0.95", football.f())
        check(scottish - football >= 0.08, "and at least 0.08 below the feed's other posts", "${scottish.f()} - ${football.f()}")

        // Within Guardian UK, posts filed under Business go down by their category.
        val guardian = after.items(GUARDIAN_UK)
        val business = guardian.filter { it.topic == "business" }
        val rest = guardian.filter { it.topic != "business" }
        check(business.meanW() < 0.97, "Guardian UK's Business posts have a mean W under 0.97", business.meanW().f())
        check(business.meanW() < rest.meanW() - 0.03, "and below the feed's other posts by at least 0.03", "${business.meanW().f()} vs ${rest.meanW().f()}")
        val businessDown = business.count { it.w < 1.0 }.toDouble() / business.size
        check(businessDown >= 0.75, "at least 75% of them have W < 1", businessDown.pct())

        // Business, from either feed, rarely makes the front page or Top stories, and never leads.
        val allBusiness = after.flatMap { it.items }.filter { it.topic == "business" }
        val prominent = allBusiness.count { it.prominent }.toDouble() / allBusiness.size
        val scottishProminent = after.flatMap { it.items }.filter { it.topic == "scotland" }.let { s -> s.count { it.prominent }.toDouble() / s.size }
        check(scottishProminent >= prominent + 0.3, "Scottish posts reach the front page or Top stories at least 30 points more often than business posts", "${scottishProminent.pct()} vs ${prominent.pct()}")
        // The same posts, read by the random reader, who has no taste: the control.
        val persona = r.persona as ScotlandReader
        val control = runs[RANDOM].from(WARM_UP).flatMap { it.items }.filter { persona.topic(it.post) == "business" }
        val controlProminent = control.count { it.prominent }.toDouble() / control.size
        check(prominent <= controlProminent - 0.1, "business posts reach the front page or Top stories at least 10 points less often than for a reader with no taste", "${prominent.pct()} vs ${controlProminent.pct()}")
        val businessLeads = after.count { day -> day.items.any { it.kind == ItemKind.LEAD && it.topic == "business" } }
        check(businessLeads == 0, "no business post leads the paper", "$businessLeads leads")

        holidayOrdering(r, loved = setOf(BBC_SCOTLAND))
    }

    // -- Hobbyist -------------------------------------------------------------------------

    @Test
    fun hobbyist() = checks(runs[HOBBYIST]) { r ->
        val persona = r.persona as Hobbyist
        val after = r.from(WARM_UP)
        val affinity = r.meanAffinity(after)
        for (feed in persona.loved) {
            // Measured: Yarn Harlot 0.81, BIKESNOBNYC 0.75 (which dips to 0.60 after the holiday).
            check(affinity.getValue(feed) > 0.6, "$feed's mean affinity after the warm-up is above 0.6", affinity.getValue(feed).f())
            val others = affinity.filterKeys { it !in persona.loved }
            check(affinity.getValue(feed) > others.values.max() + 0.2, "$feed is at least 0.2 above every feed the reader does not love", others.fmt())
        }
        check(affinity.getValue(ATP) < 0.1, "the never-opened podcast sinks below 0.1", affinity.getValue(ATP).f())

        // A loved blog's new post reaches the front page, in its first edition or, when the
        // feed had two new posts that day, the next. Measured: every one.
        for (feed in persona.loved) {
            val posts = r.firstAppearances(feed, from = WARM_UP)
            val reached = posts.count { (post, day) -> r.days.subList(day, minOf(day + 2, r.days.size)).any { d -> d.items.any { it.post === post && it.onFront } } }
            check(reached >= posts.size * 0.95, "at least 95% of $feed's posts reach the front page", "$reached of ${posts.size}")
        }

        // Not asserted, for the report: how often the never-opened podcast still reaches the front
        // page, and how many times the loved feeds were printed to nobody during the holiday.
        val episodes = r.firstAppearances(ATP, from = WARM_UP)
        val episodesOnFront = episodes.count { (post, day) -> r.days[day].items.any { it.post === post && it.onFront } }
        check(true, "never-opened podcast episodes on the front page in their first edition", "$episodesOnFront of ${episodes.size}")
        val holiday = r.days.subList(persona.habits.holiday.first, persona.habits.holiday.last + 1).flatMap { it.items }
            .filter { it.post.feed.key in persona.loved && it.printed }
        check(true, "loved feeds' stories printed during the holiday", "${holiday.size} printings of ${holiday.map { it.post }.distinct().size} posts")

        holidayOrdering(r, loved = persona.loved)
    }

    // -- Tech reader ----------------------------------------------------------------------

    @Test
    fun techReader() = checks(runs[TECH]) { r ->
        val after = r.from(WARM_UP)
        val fireball = after.items(DARING_FIREBALL)
        val links = fireball.filter { it.topic == "link" }
        val articles = fireball.filter { it.topic == "article" }
        // Measured: link posts 0.968, articles 1.041.
        check(links.meanW() < 0.99, "Daring Fireball's link posts have a mean W under 0.99", links.meanW().f())
        check(articles.meanW() > 1.02, "its own articles have a mean W over 1.02", articles.meanW().f())
        val linksDown = links.count { it.w < 1.0 }.toDouble() / links.size
        check(linksDown >= 0.8, "at least 80% of its link posts have W < 1", linksDown.pct())

        // On a day with an article and link posts, W never makes the article Daring Fireball's
        // best story less often than age alone would (without W is the same score divided by
        // W). It barely makes it more often either: see aLinkBlogsOwnArticlesOutrankItsLinkPosts.
        val (withW, withoutW, contested) = articleFirst(after)
        check(contested >= 20 && withW >= withoutW, "W does not put Daring Fireball's link posts above its articles more often than age alone", "$withW with W, $withoutW without, of $contested days")

        // The long-form and code-heavy blogs reach the front page whenever they post.
        for (feed in listOf(DANLUU, JVNS)) {
            val posts = r.firstAppearances(feed, from = WARM_UP)
            val reached = posts.count { (post, day) -> r.days[day].items.any { it.post === post && it.onFront } }
            check(reached >= posts.size * 0.9, "at least 90% of $feed's new posts are on the front page", "$reached of ${posts.size}")
        }
        val affinity = r.meanAffinity(after)
        check(affinity.getValue(DANLUU) > affinity.getValue(DARING_FIREBALL), "Dan Luu ends above Daring Fireball, whose link posts are skipped", affinity.fmt())
        check(affinity.getValue(JVNS) > affinity.getValue(DARING_FIREBALL), "and so does Julia Evans", affinity.fmt())
    }

    // -- Drifter --------------------------------------------------------------------------

    @Test
    fun drifter() = checks(runs[DRIFTER]) { r ->
        val shift = (r.persona as Drifter).shiftDay
        val before = r.days.subList(shift - 30, shift)
        val cookingBefore = before.map { it.affinity.getValue(SMITTEN) }.average()
        val runningBefore = before.map { it.affinity.getValue(IRUNFAR) }.average()
        // Measured: 0.66 against 0.03.
        check(cookingBefore > runningBefore + 0.3, "in the month before the shift cooking is at least 0.3 above running", "${cookingBefore.f()} vs ${runningBefore.f()}")

        // The day running overtakes cooking for good. Measured: 27 days after the shift.
        val overtake = (shift until r.days.size).firstOrNull { d -> r.days.subList(d, r.days.size).all { it.affinity.getValue(IRUNFAR) > it.affinity.getValue(SMITTEN) } }
        check(overtake != null && overtake - shift <= 45, "running overtakes cooking for good within 45 days of the shift", "${overtake?.minus(shift)} days")

        // By the last month running leads the paper and cooking does not.
        val last = r.from(r.days.size - 30)
        val runningLeads = last.count { day -> day.items.any { it.kind == ItemKind.LEAD && it.topic == "running" } }
        val cookingLeads = last.count { day -> day.items.any { it.kind == ItemKind.LEAD && it.topic == "cooking" } }
        check(runningLeads >= 8 && runningLeads > 2 * cookingLeads, "in the last 30 days running leads at least 8 editions and twice as many as cooking", "$runningLeads vs $cookingLeads")
        val runningFront = last.share { day -> day.items.any { it.onFront && it.topic == "running" } }
        check(runningFront >= 0.7, "running is on the front page of at least 70% of the last 30 editions", runningFront.pct())
    }

    // -- Lapsed reader --------------------------------------------------------------------

    @Test
    fun lapsedReader() = checks(runs[LAPSED]) { r ->
        // Hardly any reading: nothing should be learned beyond "this reader opens little".
        val all = r.days.flatMap { it.items }
        val near = all.count { it.w in 0.95..1.05 }.toDouble() / all.size
        check(near >= 0.99, "at least 99% of W within 0.95..1.05", near.pct())

        // The paper keeps coming in full, however little is read.
        val thin = r.days.filter { day -> day.items.count { it.onFront } < 5 }.map { it.day }
        check(thin.isEmpty(), "every edition has a full front page", "short on days $thin")

        // A visit after missed editions opens on While you were away.
        val visits = r.days.filter { it.visit != Visit.NONE }
        val afterGap = visits.filter { day -> day.day >= 3 && r.days.subList(day.day - 3, day.day).all { it.visit == Visit.NONE } }
        val withAway = afterGap.count { day -> day.items.any { it.kind == ItemKind.AWAY } }
        check(afterGap.isNotEmpty() && withAway == afterGap.size, "every visit after three or more missed days has a While you were away page", "$withAway of ${afterGap.size}")
    }

    // -- Baseline -------------------------------------------------------------------------

    /**
     * A reader who opens stories at random, at a rate whose expected engagement is the prior's
     * own neutral value. Nothing is to be learned, so nothing may be invented.
     */
    @Test
    fun randomReaderLeavesEverythingNearNeutral() = checks(runs[RANDOM]) { r ->
        val after = r.from(Simulation.DAYS - 60)
        val affinity = r.meanAffinity(after)
        // Measured: 0.32..0.47 for every feed over the last 60 days. Below 0.5 because missed
        // and front-page-only days (about 17% of them) count every printed story as ignored.
        check(affinity.values.all { it in 0.25..0.65 }, "every feed's mean affinity over the last 60 days is within 0.25..0.65", affinity.fmt())
        val spread = affinity.values.max() - affinity.values.min()
        check(spread <= 0.25, "the spread between feeds is at most 0.25", spread.f())

        val all = r.from(WARM_UP).flatMap { it.items }
        val near = all.count { it.w in 0.9..1.1 }.toDouble() / all.size
        val strong = all.count { it.w >= RankingConfig.Default.withinFeedReasonMinWeight || it.w <= 1 / RankingConfig.Default.withinFeedReasonMinWeight }.toDouble() / all.size
        // Measured: 98% within 0.9..1.1 and none strong.
        check(near >= 0.95, "at least 95% of W within 0.9..1.1", near.pct())
        check(strong <= 0.02, "at most 2% of W at reason strength (≥ 1.15 or ≤ 1/1.15)", strong.pct())
        val slope = r.days.mapNotNull { it.textSlope }.drop(WARM_UP)
        check(slope.average() < 0.01, "the text taste finds next to nothing (mean slope under 0.01)", slope.average().f())
    }

    // -- Every persona --------------------------------------------------------------------

    @Test
    fun everyRunIsSane() = checks(null) {
        for (r in runs.byPersona.values) {
            val name = r.persona.name
            val all = r.days.flatMap { it.items }
            val config = RankingConfig.Default
            val outside = all.filter { it.w < config.withinFeedMinWeight || it.w > config.withinFeedMaxWeight }
            check(outside.isEmpty(), "$name: W stays within ${config.withinFeedMinWeight}..${config.withinFeedMaxWeight}", outside.take(3).joinToString { "${it.post} ${it.w}" })
            val empty = r.days.filter { it.newPosts > 0 && (it.editionId == null || it.items.none { item -> item.printed }) }.map { it.day }
            check(empty.isEmpty(), "$name: an edition is printed every day there are new posts", "none on days $empty")
            val noLead = r.days.filter { day -> day.items.count { it.kind == ItemKind.LEAD } != 1 }.map { it.day }
            check(noLead.isEmpty(), "$name: every edition has exactly one lead", "days $noLead")
        }
    }

    @Test
    fun theSameSeedGivesTheSameRun() {
        val runs = runs
        val full = runs[SCOTLAND]
        val firstDifferent = (1..SimulationRuns.REPEAT_DAYS).firstOrNull { full.digest(it) != runs.repeat.digest(it) }
        if (firstDifferent != null) {
            val a = full.days[firstDifferent - 1]
            val b = runs.repeat.days[firstDifferent - 1]
            println("DIFF day ${a.day}: edition ${a.editionId}/${b.editionId} visit ${a.visit}/${b.visit} new ${a.newPosts}/${b.newPosts} items ${a.items.size}/${b.items.size}")
            a.items.zip(b.items).filter { (x, y) -> x.post.guid != y.post.guid || x.kind != y.kind || x.score != y.score || x.w != y.w || x.opened != y.opened }.take(10)
                .forEach { (x, y) -> println("DIFF  ${x.post.guid} ${x.kind} ${x.score} ${x.w} ${x.opened} | ${y.post.guid} ${y.kind} ${y.score} ${y.w} ${y.opened}") }
            a.affinity.filter { (k, v) -> b.affinity[k] != v }.forEach { (k, v) -> println("DIFF  affinity $k $v ${b.affinity[k]}") }
        }
        assertEquals(
            "The first ${SimulationRuns.REPEAT_DAYS} days run again give the same editions, scores, W and affinities",
            full.digest(SimulationRuns.REPEAT_DAYS),
            runs.repeat.digest(),
        )
        // The digest does notice a different world.
        assertNotEquals(full.digest(SimulationRuns.OTHER_SEED_DAYS), runs.otherSeed.digest())
    }

    // -- Known weaknesses -----------------------------------------------------------------

    /**
     * What "missed days must not crater affinity" should mean: a week away says nothing about
     * taste, so a feed the reader still loves should keep at least 80% of its affinity.
     *
     * It does not. Every edition built during the holiday is superseded unread, and each of its
     * printed stories counts as ignored, exactly as if the reader had seen and skipped it.
     * Measured on the first day back from a seven-day holiday: BBC Scotland 0.54 → 0.33 (61%)
     * for the Scotland reader, still 0.39 (72%) five weeks later; BIKESNOBNYC 0.88 → 0.60 (68%)
     * for the hobbyist (Yarn Harlot, which posted once that week, 0.87 → 0.78); smitten kitchen
     * 0.64 → 0.45 (70%) for the drifter. The order of feeds survives ([holidayOrdering] checks
     * that); the levels do not.
     */
    @Ignore("Known engine weakness: editions nobody opened count every printed story as ignored. See the KDoc.")
    @Test
    fun aHolidayDoesNotCraterLovedFeeds() = checks(null) {
        val cases = listOf(SCOTLAND to setOf(BBC_SCOTLAND), HOBBYIST to (runs[HOBBYIST].persona as Hobbyist).loved, DRIFTER to setOf(SMITTEN))
        for ((name, loved) in cases) {
            val r = runs[name]
            val holiday = Habits().holiday
            for (feed in loved) {
                val before = r.affinity(holiday.first, feed)
                val afterwards = r.affinity(holiday.last + 1, feed)
                check(afterwards >= 0.8 * before, "$name: $feed keeps at least 80% of its affinity through the holiday", "${before.f()} → ${afterwards.f()}")
            }
        }
    }

    /**
     * What "skips link posts" should give: on a day when Daring Fireball has one of its own
     * articles (opened 85% of the time) and link posts (4%), the article is its best story at
     * least four days in five.
     *
     * It is not. W does learn the taste in the right direction (link posts about 0.97, articles
     * about 1.04), but the effect is too small to beat a few hours' difference in age for a
     * daily feed: a link-post key carried by nine posts in ten has a lift close to the feed's
     * own rate, and "not a link post" is no key at all, so the rare articles are only lifted
     * through other keys or the text taste. Measured over days 30-179: the article first on
     * 17 of 33 such days with W, 15 of 33 by score without W.
     */
    @Ignore("Known engine weakness: W's effect on a daily feed's posts is smaller than a few hours of age. See the KDoc.")
    @Test
    fun aLinkBlogsOwnArticlesOutrankItsLinkPosts() = checks(null) {
        val (withW, _, contested) = articleFirst(runs[TECH].from(WARM_UP))
        check(withW >= contested * 0.8, "on at least 80% of such days an article outscores every link post", "$withW of $contested")
    }

    /**
     * What learning should do for a slow feed the reader reads every time: make it loved
     * (affinity at least `lovedAffinity`, 0.7), so that its unread posts are held over.
     *
     * It cannot, unless the reader Likes nearly everything. Affinity is
     * `(10 × prior + positive) / (10 + exposure)` with every event decayed by a 30-day
     * half-life, and the decayed exposure of a feed that posts once a month levels off near 2
     * (once a week, near 7): the prior's ten pseudo-exposures always outweigh the feed's own
     * record. The prior is the mean affinity of the feed's tags, so a favourite filed with
     * feeds the reader ignores is held down by them. Measured for the tech reader, who opens
     * 95% of Dan Luu's posts, reads 90% and Likes 40%: a mean of 0.31 over days 30-179 (Julia
     * Evans, weekly, 0.36). The drifter's smitten kitchen, opened 95% and read 90% of the time,
     * averaged 0.66 in the month before the shift.
     */
    @Ignore("Known engine weakness: the affinity prior outweighs a slow feed's whole record. See the KDoc.")
    @Test
    fun aRareFeedReadEveryTimeBecomesLoved() = checks(null) {
        val affinity = runs[TECH].meanAffinity(runs[TECH].from(WARM_UP))
        check(affinity.getValue(DANLUU) >= RankingConfig.Default.lovedAffinity, "Dan Luu, read nearly every time, is loved", affinity.getValue(DANLUU).f())
    }

    // -- Helpers --------------------------------------------------------------------------

    /** Days with both a Daring Fireball article and link post: on how many an article had the best score, with and without W. */
    private fun articleFirst(days: List<DayRecord>): Triple<Int, Int, Int> {
        val contested = days.map { it.itemsOf(DARING_FIREBALL) }.filter { d -> d.any { it.topic == "article" } && d.any { it.topic == "link" } }
        val withW = contested.count { d -> d.maxBy { it.score }.topic == "article" }
        val withoutW = contested.count { d -> d.maxBy { it.score / it.w }.topic == "article" }
        return Triple(withW, withoutW, contested.size)
    }

    /** Collects every failed check of a test, then fails once with all of them; writes the report either way. */
    private class Checks {
        val findings = ArrayList<String>()
        val failures = ArrayList<String>()

        fun check(ok: Boolean, what: String, measured: String) {
            findings += "${if (ok) "ok" else "FAILED"}: $what (measured $measured)"
            if (!ok) failures += "$what (measured $measured)"
        }
    }

    private fun checks(result: SimulationResult?, block: Checks.(SimulationResult) -> Unit) {
        val checks = Checks()
        checks.block(result ?: runs.byPersona.values.first())
        result?.let { SimulationReport.write(it, checks.findings) }
        checks.findings.forEach { println("${result?.persona?.name ?: "all"}: $it") }
        if (checks.failures.isNotEmpty()) fail(checks.failures.joinToString("\n", "${checks.failures.size} check(s) failed:\n"))
    }

    /**
     * Through the holiday and the month after it, the persona's loved feeds stay above every
     * feed it does not read: missing a week may lower the levels (see
     * [aHolidayDoesNotCraterLovedFeeds]) but must not reorder the reader's taste.
     */
    private fun Checks.holidayOrdering(r: SimulationResult, loved: Set<String>) {
        val holiday = Habits().holiday
        val span = r.days.subList(holiday.first, minOf(holiday.last + 31, r.days.size))
        val reordered = span.filter { day ->
            val lowestLoved = loved.minOf { day.affinity.getValue(it) }
            day.affinity.filterKeys { it !in loved }.values.any { it >= lowestLoved }
        }.map { it.day }
        check(reordered.isEmpty(), "loved feeds stay above every other feed through the holiday and the month after", "reordered on days $reordered")
        val drops = loved.joinToString { "$it ${r.affinity(holiday.first, it).f()} → ${r.affinity(holiday.last + 1, it).f()}" }
        check(true, "the holiday's effect on loved feeds (not asserted, see the ignored test)", drops)
    }

    private fun SimulationResult.from(day: Int) = days.subList(day, days.size)
    private fun List<DayRecord>.items(feed: String) = flatMap { it.itemsOf(feed) }
    private fun List<DayRecord>.share(predicate: (DayRecord) -> Boolean): Double {
        val built = filter { it.editionId != null }
        return built.count(predicate).toDouble() / built.size
    }
    private fun List<ItemRecord>.meanW() = if (isEmpty()) Double.NaN else map { it.w }.average()

    private fun SimulationResult.meanAffinity(days: List<DayRecord>): Map<String, Double> =
        SimulatedFeeds.all.associate { feed -> feed.key to days.map { it.affinity.getValue(feed.key) }.average() }

    /** Each post of [feed] that first appeared in an edition on or after [from], with that day. */
    private fun SimulationResult.firstAppearances(feed: String, from: Int): List<Pair<SimPost, Int>> {
        val seen = HashSet<SimPost>()
        val result = ArrayList<Pair<SimPost, Int>>()
        for (day in days) for (item in day.itemsOf(feed)) {
            if (seen.add(item.post) && day.day >= from) result += item.post to day.day
        }
        return result
    }

    private fun Double.f() = "%.3f".format(this)
    private fun Double.pct() = "%.1f%%".format(this * 100)
    private fun Map<String, Double>.fmt() = entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value.f()}" }

    private companion object {
        /** Days before anything is judged: enough editions for a month of evidence. */
        const val WARM_UP = 30
    }
}
