package net.tgnra.morningissue.ui.paper

import androidx.compose.runtime.Composable
import net.tgnra.morningissue.ui.components.PlaceholderAction
import net.tgnra.morningissue.ui.components.PlaceholderScreen

/** One edition as a pager of pages, front page to back page. Placeholder body. */
@Composable
fun PaperScreen(
    editionId: Long,
    onOpenArticle: (articleId: Long) -> Unit,
    onOpenSource: (feedId: Long) -> Unit,
    onOpenBackIssues: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSubscriptionsDesk: () -> Unit,
    onBack: () -> Unit,
) {
    PlaceholderScreen(
        title = "Paper",
        detail = "Edition $editionId will be laid out here.",
        actions = listOf(
            PlaceholderAction("Open article 1") { onOpenArticle(1) },
            PlaceholderAction("Open source 1") { onOpenSource(1) },
            PlaceholderAction("Back issues", onOpenBackIssues),
            PlaceholderAction("Search", onOpenSearch),
            PlaceholderAction("Subscriptions desk", onOpenSubscriptionsDesk),
            PlaceholderAction("Back to the stand", onBack),
        ),
    )
}
