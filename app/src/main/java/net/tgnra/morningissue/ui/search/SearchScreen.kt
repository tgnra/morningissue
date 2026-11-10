package net.tgnra.morningissue.ui.search

import androidx.compose.runtime.Composable
import net.tgnra.morningissue.ui.components.PlaceholderAction
import net.tgnra.morningissue.ui.components.PlaceholderScreen

/** Full-text search over every fetched post. Placeholder body. */
@Composable
fun SearchScreen(
    onOpenArticle: (articleId: Long) -> Unit,
    onBack: () -> Unit,
) {
    PlaceholderScreen(
        title = "Search",
        detail = "Search with tag, source, date and Liked or saved filters will be here.",
        actions = listOf(
            PlaceholderAction("Open article 1") { onOpenArticle(1) },
            PlaceholderAction("Back", onBack),
        ),
    )
}
