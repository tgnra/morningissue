package net.tgnra.morningissue.ui.reader

import androidx.compose.runtime.Composable
import net.tgnra.morningissue.ui.components.PlaceholderAction
import net.tgnra.morningissue.ui.components.PlaceholderScreen

/** One article in the paper's typography, with Like and Save at the end. Placeholder body. */
@Composable
fun ReaderScreen(
    articleId: Long,
    onOpenSource: (feedId: Long) -> Unit,
    onBack: () -> Unit,
) {
    PlaceholderScreen(
        title = "Reader",
        detail = "Article $articleId will be set here.",
        actions = listOf(
            PlaceholderAction("Open source 1") { onOpenSource(1) },
            PlaceholderAction("Back", onBack),
        ),
    )
}
