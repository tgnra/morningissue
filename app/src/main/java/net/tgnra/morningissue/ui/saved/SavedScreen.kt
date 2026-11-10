package net.tgnra.morningissue.ui.saved

import androidx.compose.runtime.Composable
import net.tgnra.morningissue.ui.components.PlaceholderAction
import net.tgnra.morningissue.ui.components.PlaceholderScreen

/** The Saved and Liked shelves. Placeholder body. */
@Composable
fun SavedScreen(
    onOpenArticle: (articleId: Long) -> Unit,
    onBack: () -> Unit,
) {
    PlaceholderScreen(
        title = "Saved",
        detail = "Saved and Liked articles will be here.",
        actions = listOf(
            PlaceholderAction("Open article 1") { onOpenArticle(1) },
            PlaceholderAction("Back", onBack),
        ),
    )
}
