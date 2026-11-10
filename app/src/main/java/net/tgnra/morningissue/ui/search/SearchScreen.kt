package net.tgnra.morningissue.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.ArticleSummary
import net.tgnra.morningissue.model.SearchQuery
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.ArticleListItem
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.ListMaxWidth
import net.tgnra.morningissue.ui.components.PagedList
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.PaperTextField
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec

private const val SEARCH_DEBOUNCE_MILLIS = 300L

/**
 * Full-text search over every fetched post, newest first. The basic version: one field, no
 * filters yet (tag, source, date, Liked and saved arrive with phase 3).
 */
@Composable
fun SearchScreen(
    onOpenArticle: (articleId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val margin = LocalPaperLayoutSpec.current.pageMargin
    var text by rememberSaveable { mutableStateOf("") }
    // Null until a search for the current text has come back.
    var results by remember { mutableStateOf<List<ArticleSummary>?>(null) }

    LaunchedEffect(text) {
        val query = text.trim()
        if (query.isEmpty()) {
            results = null
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MILLIS)
        results = container.articles.search(SearchQuery(text = query))
    }

    PaperScreenFrame(title = stringResource(R.string.search_title), onBack = onBack) {
        Box(Modifier.fillMaxWidth().padding(horizontal = margin, vertical = 12.dp), contentAlignment = Alignment.TopCenter) {
            PaperTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = stringResource(R.string.search_hint),
                modifier = Modifier.widthIn(max = ListMaxWidth),
                imeAction = ImeAction.Search,
                leadingIcon = Icons.Filled.Search,
            )
        }
        val found = results
        when {
            text.isBlank() -> EmptyMessage(stringResource(R.string.search_prompt_title), stringResource(R.string.search_prompt_detail))
            found == null -> Unit
            found.isEmpty() -> EmptyMessage(stringResource(R.string.search_empty_title), stringResource(R.string.search_empty_detail))
            else -> PagedList(
                items = found,
                key = { it.articleId },
                header = { SectionLabel(pluralStringResource(R.plurals.search_result_count, found.size, found.size), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) },
            ) { article ->
                ArticleListItem(article, onClick = { onOpenArticle(article.articleId) })
            }
        }
    }
}
