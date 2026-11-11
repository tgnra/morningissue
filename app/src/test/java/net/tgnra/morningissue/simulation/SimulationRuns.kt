package net.tgnra.morningissue.simulation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * Runs every persona once per test JVM, side by side, and keeps the results for the
 * assertions. Each run has its own in-memory database; at most [PARALLEL] run at a time.
 */
object SimulationRuns {
    /** Four runs at once: about half a laptop, and well inside the test JVM's default 512 MB heap. */
    const val PARALLEL = 4

    const val SCOTLAND = "scotland-reader"
    const val HOBBYIST = "hobbyist"
    const val TECH = "tech-reader"
    const val DRIFTER = "drifter"
    const val LAPSED = "lapsed-reader"
    const val RANDOM = "random-reader"

    /** The determinism check: the Scotland reader's first [REPEAT_DAYS] days run again, and seed 2 for a few days. */
    const val REPEAT_DAYS = 60
    const val OTHER_SEED_DAYS = 20

    class Runs(
        val byPersona: Map<String, SimulationResult>,
        val repeat: SimulationResult,
        val otherSeed: SimulationResult,
        val wallMillis: Long,
    ) {
        operator fun get(name: String): SimulationResult = byPersona.getValue(name)
    }

    @Volatile
    private var runs: Runs? = null

    @Synchronized
    fun get(context: Context): Runs = runs ?: run(context).also { runs = it }

    private fun run(context: Context): Runs = runBlocking {
        val started = System.nanoTime()
        val pool = Dispatchers.Default.limitedParallelism(PARALLEL)
        // The slowest first, so the short runs fill in at the end.
        val personas = listOf(RandomReader(), ScotlandReader(), Drifter(), Hobbyist(), TechReader(), LapsedReader())
        val full = personas.map { async(pool) { Simulation(it).run(context) } }
        val repeat = async(pool) { Simulation(ScotlandReader(), days = REPEAT_DAYS).run(context) }
        val otherSeed = async(pool) { Simulation(ScotlandReader(), seed = 2, days = OTHER_SEED_DAYS).run(context) }
        val results = full.awaitAll().associateBy { it.persona.name }
        Runs(results, repeat.await(), otherSeed.await(), (System.nanoTime() - started) / 1_000_000).also {
            println("Ranking simulation: ${results.size} personas × ${Simulation.DAYS} days and the determinism runs in ${it.wallMillis / 1000.0} s")
            for (result in results.values) println("  ${result.persona.name}: ${result.wallMillis / 1000.0} s ${result.phaseMillis}")
        }
    }
}
