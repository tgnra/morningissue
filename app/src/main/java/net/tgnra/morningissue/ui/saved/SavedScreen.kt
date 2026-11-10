package net.tgnra.morningissue.ui.saved

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.tgnra.morningissue.R
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.ArticleListItem
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.ListMaxWidth
import net.tgnra.morningissue.ui.components.PagedList
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.SegmentedChoice
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec

/** The Saved and Liked shelves, most recent first. */
@Composable
fun SavedScreen(
    onOpenArticle: (articleId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val margin = LocalPaperLayoutSpec.current.pageMargin
    var showLiked by rememberSaveable { mutableStateOf(false) }
    val saved by remember(container) { container.articles.observeSaved() }.collectAsStateWithLifecycle(initialValue = null)
    val liked by remember(container) { container.articles.observeLiked() }.collectAsStateWithLifecycle(initialValue = null)

    PaperScreenFrame(title = stringResource(R.string.saved_title), onBack = onBack) {
        Box(Modifier.fillMaxWidth().padding(horizontal = margin, vertical = 12.dp), contentAlignment = Alignment.TopCenter) {
            SegmentedChoice(
                options = listOf(false to stringResource(R.string.saved_tab_saved), true to stringResource(R.string.saved_tab_liked)),
                selected = showLiked,
                onSelect = { showLiked = it },
                modifier = Modifier.widthIn(max = ListMaxWidth),
            )
        }
        val list = if (showLiked) liked else saved
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyMessage(
                title = stringResource(if (showLiked) R.string.liked_empty_title else R.string.saved_empty_title),
                detail = stringResource(R.string.saved_empty_detail),
            )
            else -> PagedList(items = list, key = { it.articleId }) { article ->
                ArticleListItem(article, onClick = { onOpenArticle(article.articleId) })
            }
        }
    }
}
