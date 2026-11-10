package net.tgnra.morningissue.ui.newsstand

import androidx.compose.runtime.Composable
import net.tgnra.morningissue.ui.components.PlaceholderAction
import net.tgnra.morningissue.ui.components.PlaceholderScreen

/**
 * Home: today's edition facing out, recent editions racked behind it, and the button bar
 * (Search, Saved, Sources, Settings). Placeholder body.
 */
@Composable
fun NewsstandScreen(
    onOpenEdition: (editionId: Long) -> Unit,
    onOpenBackIssues: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSaved: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    PlaceholderScreen(
        title = "Newsstand",
        detail = "Today's paper and the last six editions will be racked here.",
        actions = listOf(
            PlaceholderAction("Open edition 1") { onOpenEdition(1) },
            PlaceholderAction("Older issues", onOpenBackIssues),
            PlaceholderAction("Search", onOpenSearch),
            PlaceholderAction("Saved", onOpenSaved),
            PlaceholderAction("Sources", onOpenSources),
            PlaceholderAction("Settings", onOpenSettings),
        ),
    )
}
