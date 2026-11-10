package net.tgnra.morningissue.ui.sources

import androidx.compose.runtime.Composable
import net.tgnra.morningissue.ui.components.PlaceholderAction
import net.tgnra.morningissue.ui.components.PlaceholderScreen

/** The list of feeds, add-feed, and OPML import and export. Placeholder body. */
@Composable
fun SourcesScreen(
    onOpenSource: (feedId: Long) -> Unit,
    onOpenSubscriptionsDesk: () -> Unit,
    onBack: () -> Unit,
) {
    PlaceholderScreen(
        title = "Sources",
        detail = "Your feeds will be listed here.",
        actions = listOf(
            PlaceholderAction("Open source 1") { onOpenSource(1) },
            PlaceholderAction("Subscriptions desk", onOpenSubscriptionsDesk),
            PlaceholderAction("Back", onBack),
        ),
    )
}
