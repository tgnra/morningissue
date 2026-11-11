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
    /** Show every image in greyscale, dithered, whatever the theme. E-ink mode does so regardless. */
    val greyscaleImages: Boolean = false,
    val eveningEditionEnabled: Boolean = false,
    /** Local time at which the Evening edition is released, when it is switched on. */
    val eveningEditionTime: LocalTime = DEFAULT_EVENING_TIME,
    /** Budget for stored article text and cached images, in megabytes. */
    val storageBudgetMb: Int = DEFAULT_STORAGE_BUDGET_MB,
    val onboardingDone: Boolean = false,
    /** When the reader last turned down the newsstand's offer of an Extra edition, if ever. */
    val extraOfferDismissedAt: Instant? = null,
) {
    companion object {
        val DEFAULT_RESET_TIME: LocalTime = LocalTime.of(6, 30)
        val DEFAULT_EVENING_TIME: LocalTime = LocalTime.of(18, 0)
        const val DEFAULT_STORAGE_BUDGET_MB = 500
        const val MIN_STORAGE_BUDGET_MB = 50
        const val MAX_STORAGE_BUDGET_MB = 4000
    }
}
