package net.tgnra.morningissue.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.SliderStep
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.ArticleListItem
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.HeavyRule
import net.tgnra.morningissue.ui.components.MenuItem
import net.tgnra.morningissue.ui.components.OverflowMenu
import net.tgnra.morningissue.ui.components.PagedList
import net.tgnra.morningissue.ui.components.PaperIconButton
import net.tgnra.morningissue.ui.components.PaperIcons
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.StepPips
import net.tgnra.morningissue.ui.components.retainedState
import net.tgnra.morningissue.ui.components.stepLabel
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.learning.Mutes
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * One source's recent posts in date order, newest first, like a columnist's page. Its header
 * says how the source stands (cadence, tags, slider step, any mute); the pencil opens the same
 * detail sheet as the Sources list.
 */
@Composable
fun SourcePageScreen(
    feedId: Long,
    onOpenArticle: (articleId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val uriHandler = LocalUriHandler.current
    val loaded by retainedState("feed-$feedId") { container.feeds.observeFeed(feedId).map { LoadedFeed(it) } }
    val articles by retainedState("feed-articles-$feedId") { container.articles.observeFeedArticles(feedId) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }

    val feed = loaded?.feed
    Box(Modifier.fillMaxSize()) {
        PaperScreenFrame(
            title = feed?.title ?: stringResource(R.string.sources_title),
            onBack = onBack,
            modifier = if (editing) Modifier.clearAndSetSemantics { } else Modifier,
            actions = {
                if (feed != null) {
                    PaperIconButton(Icons.Filled.Edit, stringResource(R.string.source_edit, feed.title), onClick = { editing = true })
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
                    RemoveFeedDialog(
                        feed = feed,
                        onDismiss = { confirmRemove = false },
                        onConfirm = {
                            confirmRemove = false
                            // Leave first: the page has nothing to show once the feed is gone.
                            onBack()
                            container.appScope.launch { container.feeds.removeFeed(feed.id) }
                        },
                    )
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
                        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            FeedCadence(feed)
                            if (feed.tags.isNotEmpty()) SectionLabel(feed.tags.joinToString("  ·  "))
                            val mutedUntil = Mutes.activeUntil(feed.muteUntil, container.clock.instant())
                            if (feed.sliderStep != SliderStep.AUTO || mutedUntil != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (feed.sliderStep != SliderStep.AUTO) {
                                        StepPips(feed.sliderStep)
                                        SectionLabel(stepLabel(feed.sliderStep), color = MorningIssueTheme.colors.ink)
                                    }
                                    if (mutedUntil != null) {
                                        Icon(PaperIcons.Muted, contentDescription = null, tint = MorningIssueTheme.colors.ink, modifier = Modifier.size(16.dp))
                                        SectionLabel(
                                            stringResource(R.string.source_muted_until, PaperFormat.shortDate(mutedUntil.atZone(ZoneId.systemDefault()).toLocalDate())),
                                            color = MorningIssueTheme.colors.ink,
                                        )
                                    }
                                }
                            }
                            SectionLabel(
                                pluralStringResource(R.plurals.source_post_count, posts.size, posts.size),
                                color = MorningIssueTheme.colors.ink,
                                modifier = Modifier.padding(top = 8.dp),
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
        if (editing) {
            FeedDetail(
                feedId = feedId,
                onClose = { editing = false },
                onOpenPosts = null,
                onRemoved = {
                    editing = false
                    onBack()
                },
            )
        }
    }
}
