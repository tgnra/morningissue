package net.tgnra.morningissue.ui.reader

import android.content.Context
import net.tgnra.morningissue.engine.RankingConfig

/**
 * When an article counts as read (v1's Read signal): scrolled through at least 70% of the
 * body, or kept open for at least half its estimated reading time. Pure, so it can be tested.
 */
object ReadRule {
    /** How far through the body a scroll position is, 0..1. A body that fits on screen scrolls nowhere and is 0. */
    fun scrollFraction(scroll: Int, maxScroll: Int): Float =
        if (maxScroll <= 0) 0f else (scroll.toFloat() / maxScroll).coerceIn(0f, 1f)

    /** How long the article must stay open to count as read without scrolling. */
    fun dwellThresholdMillis(readMinutes: Int, config: RankingConfig = RankingConfig.Default): Long =
        (readMinutes.coerceAtLeast(1) * 60_000L * config.readDwellFactor).toLong()

    fun isRead(
        scrollFraction: Float,
        dwellMillis: Long,
        readMinutes: Int,
        config: RankingConfig = RankingConfig.Default,
    ): Boolean = scrollFraction >= config.readScrollFraction || dwellMillis >= dwellThresholdMillis(readMinutes, config)

    /**
     * Whether a new furthest position is worth storing: every [step] of progress, and the end.
     * Keeps a scroll from writing to the database on every frame.
     */
    fun shouldReport(lastReported: Float, fraction: Float, step: Float = 0.05f): Boolean =
        fraction > lastReported && (fraction - lastReported >= step || fraction >= 1f)
}

/**
 * The reader's text size: a small ladder of scale steps, remembered across articles. Kept in
 * the UI's own preferences file because it is purely presentational.
 */
object ReaderTextSize {
    val steps: List<Float> = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f)
    const val DEFAULT_STEP = 1

    private const val FILE = "reader"
    private const val KEY_STEP = "text_size_step"

    fun clamp(step: Int): Int = step.coerceIn(0, steps.lastIndex)

    fun load(context: Context): Int =
        clamp(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_STEP, DEFAULT_STEP))

    fun save(context: Context, step: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY_STEP, clamp(step)).apply()
    }
}
