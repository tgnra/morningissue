package net.tgnra.morningissue.ui.settings

import androidx.compose.runtime.Composable
import net.tgnra.morningissue.ui.components.PlaceholderAction
import net.tgnra.morningissue.ui.components.PlaceholderScreen

/** Reset time, edition length, theme, display mode, storage budget. Placeholder body. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    PlaceholderScreen(
        title = "Settings",
        detail = "Reset time, edition length, theme and display mode will be set here.",
        actions = listOf(PlaceholderAction("Back", onBack)),
    )
}
