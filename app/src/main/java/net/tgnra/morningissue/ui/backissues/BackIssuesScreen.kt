package net.tgnra.morningissue.ui.backissues

import androidx.compose.runtime.Composable
import net.tgnra.morningissue.ui.components.PlaceholderAction
import net.tgnra.morningissue.ui.components.PlaceholderScreen

/** Every stored edition on a calendar. Placeholder body. */
@Composable
fun BackIssuesScreen(
    onOpenEdition: (editionId: Long) -> Unit,
    onBack: () -> Unit,
) {
    PlaceholderScreen(
        title = "Back issues",
        detail = "The calendar of past editions will be here.",
        actions = listOf(
            PlaceholderAction("Open edition 1") { onOpenEdition(1) },
            PlaceholderAction("Back", onBack),
        ),
    )
}
