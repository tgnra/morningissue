package net.tgnra.morningissue.ui.saved

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.ArticleSummary
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.ArticleListItem
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.ListMaxWidth
import net.tgnra.morningissue.ui.components.PagedList
import net.tgnra.morningissue.ui.components.PaperIconButton
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.SegmentedChoice
import net.tgnra.morningissue.ui.components.retainedState
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * The two shelves: Saved (to read later) and Liked (read and enjoyed), each most recent first.
 * A story opens in the reader; the cross at the end of its row takes it off the shelf, and the
 * line that then appears puts it back.
 */
@Composable
fun SavedScreen(
    onOpenArticle: (articleId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val margin = LocalPaperLayoutSpec.current.pageMargin
    var showLiked by rememberSaveable { mutableStateOf(false) }
    val saved by retainedState("saved") { container.articles.observeSaved() }
    val liked by retainedState("liked") { container.articles.observeLiked() }

    // The story last taken off a shelf, while it can still be put back.
    var undoId by rememberSaveable { mutableStateOf<Long?>(null) }
    var undoTitle by rememberSaveable { mutableStateOf("") }
    var undoFromLiked by rememberSaveable { mutableStateOf(false) }

    fun setOnShelf(articleId: Long, fromLiked: Boolean, on: Boolean) {
        scope.launch {
            if (fromLiked) container.articles.setLiked(articleId, on) else container.articles.setSaved(articleId, on)
        }
    }

    fun remove(article: ArticleSummary) {
        undoId = article.articleId
        undoTitle = article.title
        undoFromLiked = showLiked
        setOnShelf(article.articleId, showLiked, on = false)
    }

    PaperScreenFrame(title = stringResource(R.string.saved_title), onBack = onBack) {
        Box(Modifier.fillMaxWidth().padding(horizontal = margin, vertical = 12.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = ListMaxWidth)) {
                SegmentedChoice(
                    options = listOf(
                        false to shelfLabel(stringResource(R.string.saved_tab_saved), saved?.size),
                        true to shelfLabel(stringResource(R.string.saved_tab_liked), liked?.size),
                    ),
                    selected = showLiked,
                    onSelect = { showLiked = it },
                )
                val id = undoId
                if (id != null) {
                    UndoLine(
                        text = stringResource(if (undoFromLiked) R.string.liked_removed else R.string.saved_removed, undoTitle),
                        onUndo = {
                            setOnShelf(id, undoFromLiked, on = true)
                            undoId = null
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
        val list = if (showLiked) liked else saved
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyMessage(
                title = stringResource(if (showLiked) R.string.liked_empty_title else R.string.saved_empty_title),
                detail = stringResource(if (showLiked) R.string.liked_empty_detail else R.string.saved_empty_detail),
            )
            else -> PagedList(
                items = list,
                key = { it.articleId },
                header = {
                    SectionLabel(
                        stringResource(if (showLiked) R.string.liked_order else R.string.saved_order),
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                },
            ) { article ->
                ArticleListItem(
                    article = article,
                    onClick = { onOpenArticle(article.articleId) },
                    trailing = {
                        PaperIconButton(
                            icon = Icons.Filled.Close,
                            contentDescription = stringResource(if (showLiked) R.string.liked_remove else R.string.saved_remove),
                            onClick = { remove(article) },
                            tint = MorningIssueTheme.colors.inkSecondary,
                        )
                    },
                )
            }
        }
    }
}

/** "Saved · 4" once the shelf has been counted. */
@Composable
private fun shelfLabel(name: String, count: Int?): String =
    if (count == null || count == 0) name else stringResource(R.string.saved_tab_count, name, count)

/** What was just removed, with the way to put it back. */
@Composable
private fun UndoLine(text: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.5.dp, colors.ink),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = type.label,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Box(Modifier.fillMaxHeight().width(1.5.dp).background(colors.ink))
        Box(
            Modifier
                .fillMaxHeight()
                .defaultMinSize(minWidth = 80.dp, minHeight = 48.dp)
                .clickable(role = Role.Button, onClick = onUndo)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.action_undo), style = type.label.copy(fontWeight = FontWeight.Bold), color = colors.ink)
        }
    }
}
