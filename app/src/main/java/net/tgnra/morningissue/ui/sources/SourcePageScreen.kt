package net.tgnra.morningissue.ui.sources

import androidx.compose.runtime.Composable
import net.tgnra.morningissue.ui.components.PlaceholderAction
import net.tgnra.morningissue.ui.components.PlaceholderScreen

/** One source's recent posts in date order, like a columnist's page. Placeholder body. */
@Composable
fun SourcePageScreen(
    feedId: Long,
    onOpenArticle: (articleId: Long) -> Unit,
    onBack: () -> Unit,
) {
    PlaceholderScreen(
        title = "Source",
        detail = "Recent posts from feed $feedId will be here.",
        actions = listOf(
            PlaceholderAction("Open article 1") { onOpenArticle(1) },
            PlaceholderAction("Back", onBack),
        ),
    )
}
