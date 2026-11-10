package net.tgnra.morningissue.ui.sources

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.ArticleListItem
import net.tgnra.morningissue.ui.components.DialogButtons
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.HeavyRule
import net.tgnra.morningissue.ui.components.MenuItem
import net.tgnra.morningissue.ui.components.OverflowMenu
import net.tgnra.morningissue.ui.components.PagedList
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperDialog
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

private class LoadedFeed(val feed: Feed?)

/** One source's recent posts in date order, newest first, like a columnist's page. */
@Composable
fun SourcePageScreen(
    feedId: Long,
    onOpenArticle: (articleId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val loaded by remember(container, feedId) { container.feeds.observeFeed(feedId).map { LoadedFeed(it) } }
        .collectAsStateWithLifecycle(initialValue = null)
    val articles by remember(container, feedId) { container.articles.observeFeedArticles(feedId) }
        .collectAsStateWithLifecycle(initialValue = null)
    var confirmRemove by remember { mutableStateOf(false) }

    val feed = loaded?.feed
    PaperScreenFrame(
        title = feed?.title ?: stringResource(R.string.sources_title),
        onBack = onBack,
        actions = {
            if (feed != null) {
                OverflowMenu(
                    items = listOfNotNull(
                        feed.siteUrl?.let { site -> MenuItem(stringResource(R.string.source_open_site)) { runCatching { uriHandler.openUri(site) } } },
                        MenuItem(stringResource(R.string.action_remove)) { confirmRemove = true },
                    ),
                    contentDescription = stringResource(R.string.action_more),
                )
            }
        },
        overlay = {
            if (confirmRemove && feed != null) {
                PaperDialog(title = stringResource(R.string.remove_feed_title, feed.title), onDismiss = { confirmRemove = false }) {
                    Text(
                        stringResource(R.string.remove_feed_detail),
                        style = MorningIssueTheme.typography.body,
                        color = MorningIssueTheme.colors.ink,
                    )
                    DialogButtons {
                        PaperButton(stringResource(R.string.action_cancel), { confirmRemove = false })
                        PaperButton(
                            text = stringResource(R.string.action_remove),
                            onClick = {
                                confirmRemove = false
                                // Leave first: the page has nothing to show once the feed is gone.
                                onBack()
                                container.appScope.launch { container.feeds.removeFeed(feed.id) }
                            },
                            primary = true,
                        )
                    }
                }
            }
        },
    ) {
        val posts = articles
        when {
            loaded == null || posts == null -> Unit
            feed == null -> EmptyMessage(stringResource(R.string.source_missing_title), stringResource(R.string.source_missing_detail))
            else -> PagedList(
                items = posts,
                key = { it.articleId },
                header = {
                    Column(Modifier.padding(top = 16.dp)) {
                        FeedCadence(feed)
                        if (feed.tags.isNotEmpty()) {
                            SectionLabel(feed.tags.joinToString("  ·  "), modifier = Modifier.padding(top = 10.dp))
                        }
                        SectionLabel(
                            pluralStringResource(R.plurals.source_post_count, posts.size, posts.size),
                            color = MorningIssueTheme.colors.ink,
                            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                        )
                        HeavyRule()
                        if (posts.isEmpty()) {
                            EmptyMessage(stringResource(R.string.source_empty_title), stringResource(R.string.source_empty_detail))
                        }
                    }
                },
            ) { article ->
                ArticleListItem(article, onClick = { onOpenArticle(article.articleId) }, showSource = false)
            }
        }
    }
}
