package net.tgnra.morningissue.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.ArticleSummary
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** The width lists and forms keep to on tablets. */
val ListMaxWidth: Dp = 680.dp

/**
 * A screen's scrolling column, centred at a readable width. Scrolls freely in standard mode;
 * in E-ink it moves a screen at a time by swipe, by volume or page key, and by the Previous
 * and Next buttons that appear under it when there is more than one screenful.
 */
@Composable
fun PagedColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    maxWidth: Dp = ListMaxWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroller = rememberPagedScroller()
    val scope = rememberCoroutineScope()
    val forward: () -> Unit = { scope.launch { scroller.stepForward() } }
    val backward: () -> Unit = { scope.launch { scroller.stepBackward() } }
    Column(modifier.fillMaxSize().pagedKeys(forward, backward)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            CompositionLocalProvider(LocalPagedScroller provides scroller) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .pagedVerticalScroll(scroller)
                        .padding(contentPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = maxWidth).fillMaxWidth(), content = content)
                }
            }
        }
        if (MorningIssueTheme.isEink && (scroller.canStepForward || scroller.canStepBackward)) {
            PagedFooter(scroller.canStepBackward, scroller.canStepForward, backward, forward)
        }
    }
}

/**
 * A long list divided by hairlines. Lazy in standard mode; in E-ink it is a [PagedColumn], so
 * it steps by whole screens snapped to rows.
 */
@Composable
fun <T> PagedList(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    item: @Composable (T) -> Unit,
) {
    val margin = LocalPaperLayoutSpec.current.pageMargin
    if (MorningIssueTheme.isEink) {
        PagedColumn(modifier, contentPadding = PaddingValues(start = margin, end = margin, bottom = 24.dp)) {
            header?.invoke()
            items.forEachIndexed { index, value ->
                if (index > 0) HairlineRule()
                Box(Modifier.stepAnchor()) { item(value) }
            }
        }
    } else {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = ListMaxWidth + margin * 2).fillMaxSize(),
                contentPadding = PaddingValues(start = margin, end = margin, bottom = 24.dp),
            ) {
                if (header != null) item(key = "header") { header() }
                items(items, key = key) { value ->
                    Column {
                        item(value)
                        HairlineRule()
                    }
                }
            }
        }
    }
}

/**
 * An article as a line in a list (a source page, Saved, search results): headline, an excerpt
 * or search snippet, and "Source · date · minutes", with a small photo where thumbnails are shown.
 */
@Composable
fun ArticleListItem(
    article: ArticleSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showSource: Boolean = true,
) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(article.title, style = type.headlineSmall, color = if (article.isRead) colors.inkSecondary else colors.ink)
            (article.snippet ?: article.summary)?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = type.body, color = colors.inkSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (article.isRead) {
                    ReadTick()
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = listOfNotNull(
                        article.feedTitle.takeIf { showSource },
                        PaperFormat.articleDate(article.publishedAt),
                        stringResource(R.string.minutes_read, article.readMinutes),
                    ).joinToString(" · "),
                    style = type.byline,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (article.imageUrl != null && ImageTreatment.showThumbnails) {
            PaperImage(article.imageUrl, contentDescription = null, modifier = Modifier.width(76.dp).aspectRatio(1f))
        }
    }
}
