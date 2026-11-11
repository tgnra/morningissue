package net.tgnra.morningissue.model

import java.time.Instant
import java.time.LocalTime

/** User settings. Defaults here are the app's defaults. */
data class AppSettings(
    /** Local time at which a new morning edition is released. */
    val resetTime: LocalTime = DEFAULT_RESET_TIME,
    val editionLength: EditionLength = EditionLength.STANDARD,
    val themeMode: ThemeMode = ThemeMode.System,
    val displayMode: DisplayMode = DisplayMode.Auto,
    val eveningEditionEnabled: Boolean = false,
    /** Local time at which the Evening edition is released, when it is switched on. */
    val eveningEditionTime: LocalTime = DEFAULT_EVENING_TIME,
    /** Budget for stored article text and cached images, in megabytes. */
    val storageBudgetMb: Int = DEFAULT_STORAGE_BUDGET_MB,
    val onboardingDone: Boolean = false,
    /** When the reader last turned down the newsstand's offer of an Extra edition, if ever. */
    val extraOfferDismissedAt: Instant? = null,
    /**
     * Release times (morning resets and Evening edition times) for which a run reached the
     * feeds and found nothing new to print, newest first; at most [NOTHING_NEW_KEPT]. Without
     * this an edition that was not printed because there was nothing new could not be told
     * from one that failed to build: see `edition.DueEditions`.
     */
    val nothingNewFor: List<Instant> = emptyList(),
) {
    companion object {
        val DEFAULT_RESET_TIME: LocalTime = LocalTime.of(6, 30)
        val DEFAULT_EVENING_TIME: LocalTime = LocalTime.of(18, 0)

        /**
         * How many [nothingNewFor] entries are kept. The early build records the coming
         * release while the two current ones (morning and evening) are still being shown,
         * so three can matter at once; four leaves a margin.
         */
        const val NOTHING_NEW_KEPT = 4
        const val DEFAULT_STORAGE_BUDGET_MB = 500
        const val MIN_STORAGE_BUDGET_MB = 50

        /** 16 GB. In bytes this is far past `Int.MAX_VALUE`: multiply as a `Long`. */
        const val MAX_STORAGE_BUDGET_MB = 16000
    }
}
