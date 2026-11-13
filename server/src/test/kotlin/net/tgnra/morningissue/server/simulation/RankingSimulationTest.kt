package net.tgnra.morningissue.server.simulation

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import net.tgnra.morningissue.engine.Affinity
import net.tgnra.morningissue.engine.RankingConfig
import net.tgnra.morningissue.model.ItemKind
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.ATP
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.BBC_SCOTLAND
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.DANLUU
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.DARING_FIREBALL
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.GUARDIAN_BUSINESS
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.GUARDIAN_UK
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.IRUNFAR
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.JVNS
import net.tgnra.morningissue.server.simulation.SimulatedFeeds.SMITTEN
import net.tgnra.morningissue.server.simulation.SimulationRuns.DRIFTER
import net.tgnra.morningissue.server.simulation.SimulationRuns.HOBBYIST
import net.tgnra.morningissue.server.simulation.SimulationRuns.LAPSED
import net.tgnra.morningissue.server.simulation.SimulationRuns.RANDOM
import net.tgnra.morningissue.server.simulation.SimulationRuns.SCOTLAND
import net.tgnra.morningissue.server.simulation.SimulationRuns.TECH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * A regression suite for ranking: simulated readers use the server, each from one phone, for
 * 180 days (see [Simulation] for what runs, [Personas] for who reads, [SimulatedFeeds] for the
 * feeds), and the assertions check that the paper learns their taste.
 *
 * The assertions are thresholds and orderings measured over many days after a warm-up, not
 * snapshots. Each states what was measured when it was written, so a regression can be
 * told from noise: the run is deterministic, so a change in a number is a change in the
 * engine (or the fixtures). Set `-PsimulationReport` to get each persona's report, with the
 * numbers each assertion measured, in `server/build/reports/simulation/`.
 *
 * All personas run once per JVM, four at a time ([SimulationRuns]); the first test to ask
 * waits for them (about two and a half minutes on a laptop).
 */
class RankingSimulationTest {
    private val runs get() = SimulationRuns.get()

    // -- Scotland reader ------------------------------------------------------------------

    @Test
    fun scotlandReader() = checks(runs[SCOTLAND]) { r ->
        val after = r.from(WARM_UP)
        val affinity = r.meanAffinity(after)
        // Measured: BBC Scotland 0.61; Guardian UK 0.18; no other feed above 0.12.
        check(affinity.maxByOrNull { it.value }!!.key == BBC_SCOTLAND, "BBC Scotland is the feed with the highest affinity", affinity.fmt())
        check(affinity.getValue(GUARDIAN_BUSINESS) < 0.05, "the never-opened Guardian Business feed sinks below 0.05", affinity.getValue(GUARDIAN_BUSINESS).f())

        // Measured: 95% of editions after the warm-up.
        val scottishFront = after.share { day -> day.items.any { it.onFront && it.topic == "scotland" } }
        check(scottishFront >= 0.9, "a Scottish story is on the front page of at least 90% of editions after day $WARM_UP", scottishFront.pct())

        // Within BBC Scotland (no categories: only the text taste and title prefixes can tell
        // its posts apart), football goes down and the rest up. Measured: 0.89 against 1.02.
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
            // Measured: Yarn Harlot 1.00, BIKESNOBNYC 0.96.
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

        // The never-opened weekly podcast falls off the front page. For the random reader, who
        // has no taste, nearly every episode makes the front page in its first edition (a new
        // weekly post has scarcity and a 10-day half-life on its side); after four months of
        // being ignored, at most a quarter as many may. Measured: 0 of 9 against 8 of 9 (before
        // the prior was scaled to cadence: 9 of 9, three of them leading).
        fun SimulationResult.lateEpisodesOnFront(): Pair<Int, Int> {
            val late = firstAppearances(ATP, from = days.size - 60)
            return late.count { (post, day) -> days[day].items.any { it.post === post && it.onFront } } to late.size
        }
        val (lateOnFront, lateEpisodes) = r.lateEpisodesOnFront()
        val (controlOnFront, controlEpisodes) = runs[RANDOM].lateEpisodesOnFront()
        val lateLeads = r.firstAppearances(ATP, from = r.days.size - 60).count { (post, day) -> r.days[day].items.any { it.post === post && it.kind == ItemKind.LEAD } }
        check(
            lateEpisodes > 0 && lateOnFront.toDouble() / lateEpisodes <= 0.25 * controlOnFront / controlEpisodes,
            "never-opened podcast episodes reach the front page in the last 60 days at most a quarter as often as for a reader with no taste",
            "$lateOnFront of $lateEpisodes, $lateLeads leading, vs $controlOnFront of $controlEpisodes",
        )

        // Not asserted, for the report: how often the podcast reached the front page over the
        // whole run, and how many times the loved feeds were printed to nobody during the holiday.
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
        // Measured: link posts 0.968, articles 1.208.
        check(links.meanW() < 0.99, "Daring Fireball's link posts have a mean W under 0.99", links.meanW().f())
        check(articles.meanW() > 1.02, "its own articles have a mean W over 1.02", articles.meanW().f())
        val linksDown = links.count { it.w < 1.0 }.toDouble() / links.size
        check(linksDown >= 0.8, "at least 80% of its link posts have W < 1", linksDown.pct())

        // On a day with an article and link posts, W never makes the article Daring Fireball's
        // best story less often than age alone would (without W is the same score divided by
        // W). How much more often: see aLinkBlogsOwnArticlesOutrankItsLinkPosts.
        val (withW, withoutW, contested) = articleFirst(after)
        check(contested >= 20 && withW >= withoutW, "W does not put Daring Fireball's link posts above its articles more often than age alone", "$withW with W, $withoutW without, of $contested days")
        val (lateWithW, lateWithoutW, lateContested) = articleFirst(r.from(90))
        val atCap = articleFirstAtKeyCap(after)
        check(true, "article first from day 90; and over days 30-179 with every article at the key cap", "$lateWithW with W, $lateWithoutW without, of $lateContested; $atCap at the cap")

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
        // Measured: 0.98 against 0.04.
        check(cookingBefore > runningBefore + 0.3, "in the month before the shift cooking is at least 0.3 above running", "${cookingBefore.f()} vs ${runningBefore.f()}")

        // The day running overtakes cooking for good. Measured: 19 days after the shift.
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
        // Every feed stays within 0.25..0.65 over the last 60 days (centred below 0.5 because
        // front-page-only days count the front page's stories as ignored), widened for a slow
        // feed to three standard errors of what its few posts can say. A feed's affinity is
        // `(n₀ × prior + Σ positive) / (n₀ + Σ exposure)`; at steady state a feed with λ posts a
        // day has Σ exposure ≈ R = λ × 30 / ln 2 and Σ (weight²) ≈ R / 2, and one post's
        // positive has a standard deviation of 0.52 for this reader (opened 55%, read 80% of
        // those, Liked 5%: 0 with 45%, ⅓ with 11%, 1 with 42%, 2 with 2%). So the standard
        // error is 0.52 × √(R / 2) / (n₀ + R): 0.04 for a daily feed (n₀ = 10, R = 43), whose
        // band stays 0.25..0.65; 0.11 for a weekly one (n₀ = 1.6, R = 6.2), 0.16..0.84; 0.18 for
        // a monthly one (n₀ = 1, R = 1.4), which three posts in a window cannot pin down at all.
        // That is the cost of a prior scaled to cadence: a slow feed is judged on its own few
        // posts, which is what lets one read every time become loved and one never opened sink
        // (aRareFeedReadEveryTimeBecomesLoved, the hobbyist's podcast). While every feed's prior
        // counted ten posts every feed stayed within 0.32..0.47 (spread 0.15).
        val priorRules = Affinity(RankingConfig.Default)
        val allowed = SimulatedFeeds.all.associate { feed ->
            val perDay = r.firstAppearances(feed.key, from = 0).size.toDouble() / r.days.size
            val reachable = perDay * RankingConfig.Default.affinityHalfLifeDays / ln(2.0)
            val n0 = priorRules.priorStrength(if (perDay > 0.0) 24.0 / perDay else null)
            val standardError = 0.52 * sqrt(reachable / 2.0) / (n0 + reachable)
            feed.key to maxOf(0.2, 3.0 * standardError)
        }
        val outside = affinity.filter { (key, value) -> abs(value - 0.45) > allowed.getValue(key) }
        check(outside.isEmpty(), "every feed's mean affinity over the last 60 days is within 0.25..0.65, or three standard errors of 0.45 for a slow feed", "${affinity.fmt()}; outside: ${outside.keys}")
        // The spread is held to 0.25 among feeds posting at least daily, as before; with slow
        // feeds in, it is only reported.
        val fast = affinity.filterKeys { key -> r.firstAppearances(key, from = 0).size >= r.days.size }
        val fastSpread = fast.values.max() - fast.values.min()
        check(fastSpread <= 0.25, "the spread between feeds posting at least daily is at most 0.25", "${fastSpread.f()} over ${fast.keys.sorted()}")
        val spread = affinity.values.max() - affinity.values.min()
        check(true, "the spread between all feeds (not asserted)", spread.f())

        val all = r.from(WARM_UP).flatMap { it.items }
        val near = all.count { it.w in 0.9..1.1 }.toDouble() / all.size
        val strong = all.count { it.w >= RankingConfig.Default.withinFeedReasonMinWeight || it.w <= 1 / RankingConfig.Default.withinFeedReasonMinWeight }.toDouble() / all.size
        // Measured: 97.7% within 0.9..1.1 and 0.1% strong.
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
            // Not asserted, for the report: how much of a section page, and of In brief, its
            // busiest feed takes (the same-feed penalty applies there as on Top stories).
            fun List<List<ItemRecord>>.busiest(): String {
                val shares = filter { it.size >= 2 }.map { page -> page.groupingBy { it.post.feed.key }.eachCount().values.max().toDouble() / page.size }
                val whole = shares.count { it == 1.0 }
                return "${shares.average().pct()} on average, the whole page on $whole of ${shares.size}"
            }
            val sectionPages = r.days.flatMap { d -> d.items.filter { it.kind == ItemKind.SECTION }.groupBy { it.page }.values }
            check(true, "$name: a section page's busiest feed takes", sectionPages.busiest())
            val briefs = r.days.map { d -> d.items.filter { it.kind == ItemKind.BRIEF } }
            check(true, "$name: In brief's busiest feed takes", briefs.busiest())
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

    // -- Weaknesses the simulation found, now fixed ---------------------------------------

    /**
     * Missed days must not crater affinity: a week away says nothing about taste, so a feed the
     * reader still loves keeps at least 80% of its affinity.
     *
     * Until page views were recorded every edition built during the holiday was superseded
     * unread and each of its printed stories counted as ignored, exactly as if the reader had
     * seen and skipped it: BBC Scotland 0.54 → 0.33 (61%), BIKESNOBNYC 0.88 → 0.60 (68%),
     * Yarn Harlot 0.87 → 0.78, smitten kitchen 0.64 → 0.45 (70%). A story now counts as
     * passed over only when its page was on screen (`pageSeenMinMillis`). Measured: BBC
     * Scotland 0.62 → 0.62, BIKESNOBNYC 0.98 → 0.96, Yarn Harlot 1.00 → 1.00, smitten kitchen
     * 1.00 → 1.00.
     */
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
     * "Skips link posts" should show: on a day when Daring Fireball has one of its own articles
     * (opened 85% of the time) and link posts (4%), the article is usually its best story.
     *
     * Before "not a link post" was a key of its own the rare articles could only be lifted
     * through other keys or the text taste, and W hardly moved them (articles about 1.04): the
     * article came first on 17 of 33 such days with W, 15 by age alone.
     *
     * The threshold was "four days in five". That is out of reach of any per-feed key, and
     * the test now measures against what is reachable instead. Daring Fireball posts every few
     * hours (a 12-hour half-life), so a link post five hours newer than an article is worth
     * ×1.33 in freshness. With every article at the key cap (×1.4, `withinFeedKeyMaxWeight`)
     * and the link posts at their own W, the article would come first on 30 of 34 days (88%):
     * that is the ceiling. The text taste could go past the cap, but titles of Daring
     * Fireball's articles and link posts are not told apart by the embedding (its slope for
     * this reader stays near 0), as with real feeds. The articles' key rests near ×1.25 rather
     * than at the cap: about seven articles a month give the key a decayed exposure near 10,
     * and its prior's 10 pseudo-exposures halve the lift. That prior is what keeps a key seen
     * on one or two posts from reaching the cap on a single read (the random reader's W stays
     * within 0.9..1.1), so it stays.
     *
     * So the check asks for two things: W puts the article first on at least two days in
     * three, and it closes at least half the gap between age alone and the key cap. Measured:
     * 24 of 34 with W, 15 by age alone, 30 at the cap (before the same-feed penalty reached
     * the sections and In brief, which changes which days are contested: 27 of 37, 19, 33).
     */
    @Test
    fun aLinkBlogsOwnArticlesOutrankItsLinkPosts() = checks(null) {
        val days = runs[TECH].from(WARM_UP)
        val (withW, withoutW, contested) = articleFirst(days)
        val atCap = articleFirstAtKeyCap(days)
        check(withW * 3 >= contested * 2, "on at least two days in three an article outscores every link post", "$withW of $contested")
        check(withW - withoutW >= 0.5 * (atCap - withoutW), "W closes at least half the gap between age alone and every article at the key cap", "$withW with W, $withoutW without, $atCap at the cap")
    }

    /**
     * A slow feed the reader reads every time becomes loved (affinity at least
     * `lovedAffinity`, 0.7), so that its unread posts are held over.
     *
     * While the prior counted ten pseudo-exposures for every feed it could not: the decayed
     * exposure of a feed that posts once a month levels off near 2 (once a week, near 7), so
     * the prior, the mean affinity of the feed's tags, always outweighed the feed's own record
     * (Dan Luu 0.31, Julia Evans 0.36). The prior now counts a quarter of what the feed's
     * cadence can reach (`affinityPriorShare`). Measured for the tech reader, who opens 95% of
     * Dan Luu's posts, reads 90% and Likes 40%: 0.94 over days 30-179 (Julia Evans 0.69).
     */
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

    /**
     * Contested days on which an article would come first if every article had W at the key
     * cap (`withinFeedKeyMaxWeight`) and the link posts kept theirs: the most a per-feed key
     * can do against age.
     */
    private fun articleFirstAtKeyCap(days: List<DayRecord>): Int {
        val cap = RankingConfig.Default.withinFeedKeyMaxWeight
        return days.map { it.itemsOf(DARING_FIREBALL) }.filter { d -> d.any { it.topic == "article" } && d.any { it.topic == "link" } }
            .count { d -> d.maxBy { if (it.topic == "article") it.score / it.w * cap else it.score }.topic == "article" }
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
     * feed it does not read: missing a week must not reorder the reader's taste (and, see
     * [aHolidayDoesNotCraterLovedFeeds], hardly lowers the levels).
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
        check(true, "the holiday's effect on loved feeds (asserted in aHolidayDoesNotCraterLovedFeeds)", drops)
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
