package net.tgnra.morningissue.server.simulation

import java.io.File
import net.tgnra.morningissue.model.ItemKind

/**
 * A plain-text report per persona: how feed affinity, W and the front page moved over the
 * run, so that a failing assertion can be diagnosed without rerunning anything.
 *
 * Written only when asked: `./gradlew -PserverOnly :server:test --tests '*RankingSimulation*' -PsimulationReport`
 * (which sets the system property [PROPERTY]) or with the environment variable [ENV] set. The
 * value may name a directory; otherwise the reports go to `server/build/reports/simulation/`.
 */
object SimulationReport {
    const val PROPERTY = "morningissue.simulation.report"
    const val ENV = "MORNINGISSUE_SIMULATION_REPORT"

    /** Where to write, or null when no report was asked for. */
    val directory: File? by lazy {
        val value = System.getProperty(PROPERTY) ?: System.getenv(ENV) ?: return@lazy null
        File(value.takeUnless { it.isBlank() || it == "true" } ?: "build/reports/simulation")
    }

    /** Writes [result]'s report, plus the [findings] of its assertions, if reports are on. */
    fun write(result: SimulationResult, findings: List<String> = emptyList()) {
        val dir = directory ?: return
        dir.mkdirs()
        File(dir, "${result.persona.name}${if (result.seed == 1L) "" else "-seed${result.seed}"}.md").writeText(render(result, findings))
    }

    fun render(result: SimulationResult, findings: List<String> = emptyList()): String = buildString {
        val persona = result.persona
        val days = result.days
        appendLine("# ${persona.name} (seed ${result.seed})")
        appendLine()
        appendLine("${days.size} days in ${result.wallMillis / 1000.0} s (${result.phaseMillis.entries.joinToString { "${it.key} ${it.value / 1000.0} s" }}).")
        appendLine("Visits: " + Visit.entries.joinToString { v -> "${v.name.lowercase()} ${days.count { it.visit == v }}" } + ".")
        appendLine()
        appendLine("## Rules")
        persona.rules.forEach { appendLine("- $it") }
        if (findings.isNotEmpty()) {
            appendLine()
            appendLine("## Measured by the assertions")
            findings.forEach { appendLine("- $it") }
        }

        val feeds = SimulatedFeeds.all.map { it.key }
        val sample = (days.indices.filter { it % 7 == 0 } + days.lastIndex).distinct()
        appendLine()
        appendLine("## Feed affinity (as stored by each morning's build)")
        appendLine()
        appendLine(row(listOf("day", "visit") + feeds.map(::short) + listOf("text slope", "text trust")))
        appendLine(row(List(feeds.size + 4) { "---" }))
        for (d in sample) {
            val day = days[d]
            appendLine(row(listOf("$d", day.visit.name.lowercase().replace('_', ' ')) + feeds.map { f2(day.affinity[it]) } + listOf(f3(day.textSlope), f2(day.textTrust))))
        }

        val blocks = days.chunked(BLOCK_DAYS)
        appendLine()
        appendLine("## W by feed (mean over every item of every edition, printed or indexed; min..max)")
        appendLine()
        appendLine(row(listOf("days") + feeds.map(::short)))
        appendLine(row(List(feeds.size + 1) { "---" }))
        for (block in blocks) {
            val items = block.flatMap { it.items }
            appendLine(row(listOf(span(block)) + feeds.map { f -> wCell(items.filter { it.post.feed.key == f }.map { it.w }) }))
        }

        val topics = days.flatMap { it.items }.mapNotNull { it.topic }.distinct().sorted()
        if (topics.isNotEmpty()) {
            appendLine()
            appendLine("## The persona's topics")
            appendLine()
            appendLine("Per block: items in editions, mean W, share printed, share on the front page, share opened.")
            appendLine()
            appendLine(row(listOf("days") + topics))
            appendLine(row(List(topics.size + 1) { "---" }))
            for (block in blocks) {
                val items = block.flatMap { it.items }
                appendLine(row(listOf(span(block)) + topics.map { t -> topicCell(items.filter { it.topic == t }) }))
            }
        }

        appendLine()
        appendLine("## Front page: share of editions with a story from each feed")
        appendLine()
        appendLine(row(listOf("days") + feeds.map(::short) + listOf("leads (top feeds)")))
        appendLine(row(List(feeds.size + 2) { "---" }))
        for (block in blocks) {
            val built = block.filter { it.editionId != null }
            val leads = built.mapNotNull { day -> day.items.firstOrNull { it.kind == ItemKind.LEAD }?.post?.feed?.key }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).joinToString(" ") { "${short(it.key)}×${it.value}" }
            appendLine(row(listOf(span(block)) + feeds.map { f -> pct(built.count { day -> day.items.any { it.onFront && it.post.feed.key == f } }, built.size) } + listOf(leads)))
        }

        appendLine()
        appendLine("## Signals that decided W (strong ones: W ≥ 1.15 or ≤ 0.87)")
        appendLine()
        for (block in blocks) {
            val strong = block.flatMap { it.items }.filter { it.w >= 1.15 || it.w <= 0.87 }
            val counts = strong.groupingBy { "${short(it.post.feed.key)} ${it.signal?.name?.lowercase()}${it.signalLabel?.takeIf(String::isNotEmpty)?.let { l -> " \"$l\"" } ?: ""} ${if (it.w > 1) "up" else "down"}" }
                .eachCount().entries.sortedByDescending { it.value }.take(6)
            appendLine("- ${span(block)}: ${strong.size} strong of ${block.sumOf { it.items.size }}" + if (counts.isEmpty()) "" else "; " + counts.joinToString { "${it.key} ×${it.value}" })
        }

        appendLine()
        appendLine("## The last edition")
        appendLine()
        val last = days.last { it.editionId != null }
        appendLine("Day ${last.day}, printed stories in page order:")
        appendLine()
        for (item in last.items.filter { it.printed }) {
            appendLine("- ${item.kind.name.lowercase()} · ${short(item.post.feed.key)} · score ${f3(item.score)} · W ${f3(item.w)}" +
                (item.signal?.let { " (${it.name.lowercase()}${item.signalLabel?.takeIf(String::isNotEmpty)?.let { l -> " $l" } ?: ""})" } ?: "") +
                (item.topic?.let { " · [$it]" } ?: "") + " · ${item.post.title}")
        }
    }

    const val BLOCK_DAYS = 30

    private fun short(key: String) = when (key) {
        SimulatedFeeds.BBC_NEWS -> "bbc"
        SimulatedFeeds.BBC_SCOTLAND -> "bbc-scot"
        SimulatedFeeds.GUARDIAN_UK -> "grd-uk"
        SimulatedFeeds.GUARDIAN_BUSINESS -> "grd-biz"
        SimulatedFeeds.SMITTEN -> "smitten"
        SimulatedFeeds.YARN_HARLOT -> "yarn"
        SimulatedFeeds.BIKE_SNOB -> "snob"
        SimulatedFeeds.GAMES -> "games"
        SimulatedFeeds.DARING_FIREBALL -> "df"
        else -> key
    }

    private fun row(cells: List<String>) = cells.joinToString(" | ", "| ", " |")
    private fun span(block: List<DayRecord>) = "${block.first().day}-${block.last().day}"
    private fun f2(x: Double?) = x?.let { "%.2f".format(it) } ?: "-"
    private fun f3(x: Double?) = x?.let { "%.3f".format(it) } ?: "-"
    private fun pct(n: Int, of: Int) = if (of == 0) "-" else "${100 * n / of}%"
    private fun wCell(ws: List<Double>) = if (ws.isEmpty()) "-" else "${f2(ws.average())} (${f2(ws.min())}..${f2(ws.max())})"
    private fun topicCell(items: List<ItemRecord>) = if (items.isEmpty()) "-" else
        "n=${items.size} W ${f2(items.map { it.w }.average())} printed ${pct(items.count { it.printed }, items.size)} " +
            "front ${pct(items.count { it.onFront }, items.size)} opened ${pct(items.count { it.opened }, items.size)}"
}
