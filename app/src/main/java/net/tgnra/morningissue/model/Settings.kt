package net.tgnra.morningissue.model

import java.time.LocalTime

/** User settings. Defaults here are the app's defaults. */
data class AppSettings(
    /** Local time at which a new morning edition is released. */
    val resetTime: LocalTime = DEFAULT_RESET_TIME,
    val editionLength: EditionLength = EditionLength.STANDARD,
    val themeMode: ThemeMode = ThemeMode.System,
    val displayMode: DisplayMode = DisplayMode.Auto,
    val eveningEditionEnabled: Boolean = false,
    /** Budget for stored article text and cached images, in megabytes. */
    val storageBudgetMb: Int = DEFAULT_STORAGE_BUDGET_MB,
    val onboardingDone: Boolean = false,
) {
    companion object {
        val DEFAULT_RESET_TIME: LocalTime = LocalTime.of(6, 30)
        const val DEFAULT_STORAGE_BUDGET_MB = 500
        const val MIN_STORAGE_BUDGET_MB = 50
        const val MAX_STORAGE_BUDGET_MB = 4000
    }
}
