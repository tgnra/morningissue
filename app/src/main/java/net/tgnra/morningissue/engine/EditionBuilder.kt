package net.tgnra.morningissue.engine

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import net.tgnra.morningissue.model.CadenceTier
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ItemKind

/** Page sizes for one edition length. The front page is always one lead plus the secondaries. */
data class EditionLimits(
    val frontPageSecondaries: Int,
    val topStoriesPages: Int,
    val topStoriesPerPage: Int,
    val sectionStories: Int,
    val briefLines: Int,
) {
    /** Most stories the mixed pages (front and Top stories) can hold. */
    val maxMixedStories: Int get() = 1 + frontPageSecondaries + topStoriesPages * topStoriesPerPage

    companion object {
        /**
         * Standard takes [config]'s sizes as they are (5 + 16 mixed stories, sections to come,
         * plus 15 briefs); Short and Long scale the per-page sizes of everything after the
         * front page, which gives 13 and 29 mixed stories with 8 and 23 briefs.
         */
        fun of(config: RankingConfig, length: EditionLength): EditionLimits {
            val scale = when (length) {
                EditionLength.SHORT -> config.shortLengthScale
                EditionLength.STANDARD -> 1.0
                EditionLength.LONG -> config.longLengthScale
            }
            fun scaled(n: Int) = (n * scale).roundToInt().coerceAtLeast(1)
            return EditionLimits(
                frontPageSecondaries = config.frontPageSecondaries,
                topStoriesPages = config.topStoriesPages,
                topStoriesPerPage = scaled(config.topStoriesPerPage),
                sectionStories = scaled(config.sectionStories),
                briefLines = scaled(config.briefLines),
            )
        }
    }
}

/**
 * Lays out an edition. Pure: the same candidates, feeds, length and release time always give
 * the same draft. [build] follows the numbered steps of "Edition assembly" in the v2 spec;
 * the steps that later milestones fill in are marked where they belong.
 */
class EditionBuilder(
    private val config: RankingConfig = RankingConfig.Default,
    private val scorer: Scorer = Scorer(config),
) {
    /** A candidate with its feed and score; [rank] is its position in the edition-wide ranking. */
    private class Ranked(val candidate: Candidate, val feed: FeedProfile, val score: Score) {
        var rank: Int = 0
    }

    private class Pool(val candidates: List<Pair<Candidate, FeedProfile>>, val muted: Int, val stale: Int)

    /** The pages laid out so far. Every post is placed at most once. */
    private class Layout {
        val items = ArrayList<DraftItem>()
        var pages = 0
            private set

        fun newPage(): Int = ++pages
    }

    /**
     * @param candidates posts first seen since the previous cutoff (plus, later, held-over ones)
     * @param feeds every subscribed feed; candidates of feeds not listed are ignored
     * @param releaseAt the edition's release time, the moment freshness is measured at
     */
    fun build(
        candidates: List<Candidate>,
        feeds: List<FeedProfile>,
        length: EditionLength,
        releaseAt: Instant,
    ): EditionDraft {
        val limits = EditionLimits.of(config, length)
        val layout = Layout()

        // 1. Build the candidate pool.
        val pool = buildPool(candidates, feeds, releaseAt)
        // 2. Score at publish time.
        val ranked = scoreAndRank(pool, releaseAt)
        // 3. Deduplicate syndicated stories.
        val unique = dedupe(ranked)

        // A post the reader has already finished scores 0: it is listed, never printed again.
        val (printable, finished) = unique.partition { it.score.value > 0.0 }

        // 4. Lay out the front page.
        var remaining = layOutFrontPage(printable, limits, layout)
        // 5. Fill Top stories, then sections.
        remaining = fillTopStories(remaining, limits, layout)
        remaining = fillSections(remaining, limits, layout)
        // 6. Fill In brief.
        remaining = fillInBrief(remaining, limits, layout)
        // 7. Pick From the archive.
        remaining = pickFromTheArchive(remaining, layout)
        // Everything left over goes in the back-page index, "Also in your feeds".
        indexLeftovers(remaining + finished, layout)
        // 8. Freeze and store: the caller's job; a draft is never laid out again.

        return EditionDraft(length, releaseAt, layout.items, mutedSkipped = pool.muted, staleSkipped = pool.stale)
    }

    /**
     * Step 1. Drops posts of muted (at release time) and unknown feeds, posts published more
     * than `maxAgeDays` before release (a newly added feed's back catalogue; they stay on the
     * source page and in search), and repeats of the same article.
     *
     * Later: the caller adds held-over unread posts from loved Weekly and Rare feeds, which
     * are exempt from the age limit; posts that have led `maxLeadRuns` times leave the pool
     * here and are only offered to From the archive.
     */
    private fun buildPool(candidates: List<Candidate>, feeds: List<FeedProfile>, releaseAt: Instant): Pool {
        val feedsById = HashMap<Long, FeedProfile>(feeds.size * 2)
        feeds.forEach { feedsById[it.id] = it }
        val oldest = releaseAt.minus(Duration.ofDays(config.maxAgeDays.toLong()))
        val seen = HashSet<Long>(candidates.size * 2)
        val kept = ArrayList<Pair<Candidate, FeedProfile>>(candidates.size)
        var muted = 0
        var stale = 0
        for (candidate in candidates) {
            val feed = feedsById[candidate.feedId] ?: continue
            if (!seen.add(candidate.articleId)) continue
            when {
                feed.muteUntil?.isAfter(releaseAt) == true -> muted++
                candidate.publishedAt.isBefore(oldest) -> stale++
                else -> kept += candidate to feed
            }
        }
        return Pool(kept, muted, stale)
    }

    /** Step 2. Best first; ties go to the newer post, then the lower article ID. */
    private fun scoreAndRank(pool: Pool, releaseAt: Instant): List<Ranked> {
        val ranked = pool.candidates.map { (candidate, feed) -> Ranked(candidate, feed, scorer.score(feed, candidate, releaseAt)) }
        val sorted = ranked.sortedWith(
            compareByDescending<Ranked> { it.score.value }
                .thenByDescending { it.candidate.publishedAt }
                .thenBy { it.candidate.articleId },
        )
        sorted.forEachIndexed { index, item -> item.rank = index }
        return sorted
    }

    /**
     * Step 3. Later: collapse posts sharing a canonical URL or a near-identical title, keeping
     * the copy from the higher-affinity feed. Until then every post is its own story.
     */
    private fun dedupe(ranked: List<Ranked>): List<Ranked> = ranked

    /**
     * Step 4. The best stories, at most one per feed; the highest score leads whether or not
     * it has an image. Returns what is left, still in rank order.
     */
    private fun layOutFrontPage(ranked: List<Ranked>, limits: EditionLimits, layout: Layout): List<Ranked> {
        if (ranked.isEmpty()) return ranked
        val size = 1 + limits.frontPageSecondaries
        val feedsUsed = HashSet<Long>()
        val picked = ArrayList<Ranked>(size)
        for (item in ranked) {
            if (picked.size == size) break
            if (feedsUsed.add(item.feed.id)) picked += item
        }
        val page = layout.newPage()
        picked.forEachIndexed { slot, item ->
            layout.place(item, page, slot, if (slot == 0) ItemKind.LEAD else ItemKind.FRONT)
        }
        return ranked.without(picked)
    }

    /**
     * Step 5a. The next best stories regardless of tag, on up to `topStoriesPages` pages. Each
     * slot goes to the best remaining story after its score is multiplied by
     * `sameFeedPagePenalty` for every story of the same feed already on that page, so one busy
     * feed cannot take a whole page while others have something to offer; with a single feed
     * the page still fills.
     */
    private fun fillTopStories(ranked: List<Ranked>, limits: EditionLimits, layout: Layout): List<Ranked> {
        if (ranked.isEmpty()) return ranked
        // Within a feed the best remaining story is always the head of its queue.
        val queues = LinkedHashMap<Long, ArrayDeque<Ranked>>()
        ranked.forEach { queues.getOrPut(it.feed.id) { ArrayDeque() }.addLast(it) }
        val picked = ArrayList<Ranked>()

        repeat(limits.topStoriesPages) {
            if (queues.isEmpty()) return@repeat
            val page = layout.newPage()
            val onPage = HashMap<Long, Int>()
            var slot = 0
            while (slot < limits.topStoriesPerPage && queues.isNotEmpty()) {
                var best: Ranked? = null
                var bestScore = -1.0
                for ((feedId, queue) in queues) {
                    val head = queue.first()
                    val adjusted = head.score.value * penalty(onPage[feedId] ?: 0)
                    if (adjusted > bestScore || (adjusted == bestScore && head.rank < best!!.rank)) {
                        best = head
                        bestScore = adjusted
                    }
                }
                val choice = best!!
                val queue = queues.getValue(choice.feed.id)
                queue.removeFirst()
                if (queue.isEmpty()) queues.remove(choice.feed.id)
                onPage.merge(choice.feed.id, 1, Int::plus)
                layout.place(choice, page, slot++, ItemKind.TOP)
                picked += choice
            }
        }
        return ranked.without(picked)
    }

    private fun penalty(sameFeedOnPage: Int): Double {
        var factor = 1.0
        repeat(sameFeedOnPage) { factor *= config.sameFeedPagePenalty }
        return factor
    }

    /**
     * Step 5b. Later: one `SECTION` page per tag ("General" for untagged feeds) with its top
     * `limits.sectionStories` remaining stories; a feed with several tags goes where it scores
     * best. No section pages yet.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun fillSections(ranked: List<Ranked>, limits: EditionLimits, layout: Layout): List<Ranked> = ranked

    /** Step 6. Remaining Firehose and Daily posts scoring at least `briefMinScore`, best first. */
    private fun fillInBrief(ranked: List<Ranked>, limits: EditionLimits, layout: Layout): List<Ranked> {
        val picked = ArrayList<Ranked>(limits.briefLines)
        for (item in ranked) {
            if (picked.size == limits.briefLines) break
            // Ranked by score, so nothing after the first post under the threshold can qualify.
            if (item.score.value < config.briefMinScore) break
            if (item.feed.tier == CadenceTier.FIREHOSE || item.feed.tier == CadenceTier.DAILY) picked += item
        }
        if (picked.isEmpty()) return ranked
        val page = layout.newPage()
        picked.forEachIndexed { slot, item -> layout.place(item, page, slot, ItemKind.BRIEF) }
        return ranked.without(picked)
    }

    /**
     * Step 7. Later: one `ARCHIVE` page with the best held-over post that has not run in the
     * last `archiveCooldownEditions` editions. Nothing is held over yet.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun pickFromTheArchive(ranked: List<Ranked>, layout: Layout): List<Ranked> = ranked

    /**
     * "Also in your feeds": every post that was not printed, grouped by source. Sources run
     * alphabetically; within a source the best post comes first. Index items sit on the back
     * page, numbered after the last printed page, with slots running across the groups.
     */
    private fun indexLeftovers(leftovers: List<Ranked>, layout: Layout) {
        if (leftovers.isEmpty()) return
        val page = layout.pages + 1
        val sorted = leftovers.sortedWith(
            compareBy<Ranked, String>(String.CASE_INSENSITIVE_ORDER) { it.feed.title }
                .thenBy { it.feed.id }
                .thenBy { it.rank },
        )
        sorted.forEachIndexed { slot, item -> layout.place(item, page, slot, ItemKind.INDEX) }
    }

    private fun Layout.place(item: Ranked, page: Int, slot: Int, kind: ItemKind) {
        items += DraftItem(
            articleId = item.candidate.articleId,
            feedId = item.feed.id,
            page = page,
            section = null,
            slot = slot,
            kind = kind,
            score = item.score.value,
            reason = scorer.reason(item.feed, item.candidate, item.score),
        )
    }

    /** [this] minus [picked], order kept. */
    private fun List<Ranked>.without(picked: List<Ranked>): List<Ranked> {
        if (picked.isEmpty()) return this
        val gone = HashSet<Int>(picked.size * 2)
        picked.forEach { gone += it.rank }
        return filter { it.rank !in gone }
    }
}
