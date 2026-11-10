package net.tgnra.morningissue.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.PlaceholderAction
import net.tgnra.morningissue.ui.components.PlaceholderScreen

/**
 * First launch: add feeds, fetch them, build the first edition. Must set
 * `SettingsRepository.setOnboardingDone(true)` before calling [onFinished]. Placeholder body.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
) {
    val settings = LocalAppContainer.current.settings
    val scope = rememberCoroutineScope()
    PlaceholderScreen(
        title = "Welcome",
        detail = "Adding feeds and fetching the first edition will happen here.",
        actions = listOf(
            PlaceholderAction("Continue to the newsstand") {
                scope.launch {
                    settings.setOnboardingDone(true)
                    onFinished()
                }
            },
        ),
    )
}
