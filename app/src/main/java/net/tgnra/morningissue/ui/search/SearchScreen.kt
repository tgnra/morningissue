package net.tgnra.morningissue.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.ArticleSummary
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.ArticleListItem
import net.tgnra.morningissue.ui.components.DialogButtons
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.FilterChip
import net.tgnra.morningissue.ui.components.HairlineRule
import net.tgnra.morningissue.ui.components.HeavyRule
import net.tgnra.morningissue.ui.components.ListMaxWidth
import net.tgnra.morningissue.ui.components.PagedColumn
import net.tgnra.morningissue.ui.components.PagedList
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperDialog
import net.tgnra.morningissue.ui.components.PaperIcons
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.PaperTextField
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.SegmentedChoice
import net.tgnra.morningissue.ui.components.TagToken
import net.tgnra.morningissue.ui.components.TextLink
import net.tgnra.morningissue.ui.components.retainedState
import net.tgnra.morningissue.ui.components.stepAnchor
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.layout.PaperWidthClass
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

private const val SEARCH_DEBOUNCE_MILLIS = 300L

/** The width of the filter column beside the results on a tablet in landscape. */
private val FilterColumnWidth = 340.dp

/**
 * Full-text search over every fetched post, newest first, narrowed by [SearchFilters]: a tag,
 * a source, how far back to look, and Saved or Liked only.
 *
 * On phones and small tablets a line under the field shows what is active (each filter can be
 * removed there) and opens the filter controls in place of the results. On a tablet in
 * landscape the controls stay open in a column beside the results. Nothing here animates or
 * scrolls sideways, so it works unchanged in E-ink.
 */
@Composable
fun SearchScreen(
    onOpenArticle: (articleId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val spec = LocalPaperLayoutSpec.current
    val margin = spec.pageMargin
    val sideBySide = spec.widthClass == PaperWidthClass.Expanded
    var text by rememberSaveable { mutableStateOf("") }
    var stored by rememberSaveable(stateSaver = SearchFilters.Saver) { mutableStateOf(SearchFilters.None) }
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var pickingSource by rememberSaveable { mutableStateOf(false) }
    // Null until a search for the current text and filters has come back.
    var results by remember { mutableStateOf<List<ArticleSummary>?>(null) }

    val feeds by retainedState("search-feeds") { container.feeds.observeFeeds() }
    val tags by retainedState("search-tags") { container.feeds.observeTags() }
    val tagNames = tags?.map { it.name }
    // A filter on a tag or source that has since been removed is dropped, once both lists are in.
    val filters = if (feeds != null && tagNames != null) stored.validFor(tagNames, feeds.orEmpty().map { it.id }) else stored
    val setFilters: (SearchFilters) -> Unit = { stored = it }

    LaunchedEffect(text, filters) {
        val query = text.trim()
        if (query.isEmpty()) {
            results = null
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MILLIS)
        results = container.articles.search(filters.toQuery(query, container.clock.instant()))
    }

    val sourceName = filters.feedId?.let { id -> feeds?.firstOrNull { it.id == id }?.title }
    val controls: @Composable () -> Unit = {
        FilterControls(
            filters = filters,
            tags = tagNames.orEmpty(),
            sourceName = sourceName,
            onChange = setFilters,
            onPickSource = { pickingSource = true },
        )
    }
    val found: @Composable () -> Unit = {
        SearchResults(
            text = text,
            results = results,
            filtered = filters.isActive,
            onClearFilters = { setFilters(SearchFilters.None) },
            onOpenArticle = onOpenArticle,
        )
    }

    PaperScreenFrame(
        title = stringResource(R.string.search_title),
        onBack = onBack,
        overlay = {
            if (pickingSource) {
                SourcePicker(
                    feeds = feeds.orEmpty(),
                    selected = filters.feedId,
                    onDismiss = { pickingSource = false },
                    onSelect = {
                        pickingSource = false
                        setFilters(filters.copy(feedId = it))
                    },
                )
            }
        },
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = margin).padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = if (sideBySide) ListMaxWidth + FilterColumnWidth + margin * 2 else ListMaxWidth).fillMaxWidth()) {
                PaperTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = stringResource(R.string.search_hint),
                    imeAction = ImeAction.Search,
                    leadingIcon = Icons.Filled.Search,
                )
                if (!sideBySide) {
                    FilterLine(
                        filters = filters,
                        sourceName = sourceName,
                        open = filtersOpen,
                        onToggle = { filtersOpen = !filtersOpen },
                        onChange = setFilters,
                    )
                }
            }
        }
        when {
            sideBySide -> Box(Modifier.fillMaxSize().padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
                Row(Modifier.widthIn(max = ListMaxWidth + FilterColumnWidth + margin * 4).fillMaxSize()) {
                    PagedColumn(
                        modifier = Modifier.width(FilterColumnWidth + margin).fillMaxHeight(),
                        contentPadding = PaddingValues(start = margin, end = 20.dp, bottom = 24.dp),
                    ) {
                        controls()
                    }
                    Box(Modifier.fillMaxHeight().width(1.dp).background(MorningIssueTheme.colors.hairline))
                    Box(Modifier.weight(1f).fillMaxHeight()) { found() }
                }
            }
            filtersOpen -> PagedColumn(contentPadding = PaddingValues(start = margin, end = margin, top = 4.dp, bottom = 24.dp)) {
                controls()
                DialogButtons {
                    PaperButton(stringResource(R.string.search_show_results), onClick = { filtersOpen = false }, primary = true)
                }
            }
            else -> found()
        }
    }
}

@Composable
private fun periodLabel(period: SearchPeriod): String = stringResource(
    when (period) {
        SearchPeriod.AnyTime -> R.string.search_period_any
        SearchPeriod.PastWeek -> R.string.search_period_week
        SearchPeriod.PastMonth -> R.string.search_period_month
        SearchPeriod.PastYear -> R.string.search_period_year
    },
)

/**
 * The line under the field: a button that opens the filter controls and says how many are set,
 * then each active filter as a token that removes it, and a link that clears them all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterLine(
    filters: SearchFilters,
    sourceName: String?,
    open: Boolean,
    onToggle: () -> Unit,
    onChange: (SearchFilters) -> Unit,
) {
    val remove = stringResource(R.string.search_filter_remove)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            text = if (filters.isActive) {
                stringResource(R.string.search_filters_count, filters.activeCount)
            } else {
                stringResource(R.string.search_filters)
            },
            selected = open,
            onClick = onToggle,
        )
        if (filters.period != SearchPeriod.AnyTime) {
            TagToken(periodLabel(filters.period), Icons.Filled.Close, filled = true, actionLabel = remove, onClick = { onChange(filters.copy(period = SearchPeriod.AnyTime)) })
        }
        if (filters.savedOnly) {
            TagToken(stringResource(R.string.search_only_saved), Icons.Filled.Close, filled = true, actionLabel = remove, onClick = { onChange(filters.copy(savedOnly = false)) })
        }
        if (filters.likedOnly) {
            TagToken(stringResource(R.string.search_only_liked), Icons.Filled.Close, filled = true, actionLabel = remove, onClick = { onChange(filters.copy(likedOnly = false)) })
        }
        filters.tag?.let {
            TagToken(it, Icons.Filled.Close, filled = true, actionLabel = remove, onClick = { onChange(filters.copy(tag = null)) })
        }
        if (filters.feedId != null) {
            TagToken(
                text = sourceName ?: stringResource(R.string.search_source),
                icon = Icons.Filled.Close,
                filled = true,
                actionLabel = remove,
                onClick = { onChange(filters.copy(feedId = null)) },
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
        if (filters.activeCount > 1) TextLink(stringResource(R.string.search_clear_filters), onClick = { onChange(SearchFilters.None) })
    }
}

/** One group of filter controls under a small-caps heading and a rule. */
@Composable
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().stepAnchor().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            SectionLabel(title, color = MorningIssueTheme.colors.ink, heading = true)
            HeavyRule(Modifier.padding(top = 6.dp), thickness = 1.dp)
        }
        content()
    }
}

/** Every filter control: when, Saved or Liked only, tag, source, and clearing them all. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterControls(
    filters: SearchFilters,
    tags: List<String>,
    sourceName: String?,
    onChange: (SearchFilters) -> Unit,
    onPickSource: () -> Unit,
) {
    FilterGroup(stringResource(R.string.search_when)) {
        SegmentedChoice(
            options = SearchPeriod.entries.map { it to periodLabel(it) },
            selected = filters.period,
            onSelect = { onChange(filters.copy(period = it)) },
        )
    }
    FilterGroup(stringResource(R.string.search_only)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                text = stringResource(R.string.search_only_saved),
                selected = filters.savedOnly,
                onClick = { onChange(filters.copy(savedOnly = !filters.savedOnly)) },
                icon = PaperIcons.Bookmark,
            )
            FilterChip(
                text = stringResource(R.string.search_only_liked),
                selected = filters.likedOnly,
                onClick = { onChange(filters.copy(likedOnly = !filters.likedOnly)) },
                icon = Icons.Filled.Favorite,
            )
        }
    }
    if (tags.isNotEmpty()) {
        val label = stringResource(R.string.search_tag)
        FilterGroup(label) {
            FlowRow(
                modifier = Modifier.selectableGroup().semantics { contentDescription = label },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(stringResource(R.string.search_tag_any), selected = filters.tag == null, onClick = { onChange(filters.copy(tag = null)) })
                tags.forEach { tag ->
                    FilterChip(tag, selected = tag.equals(filters.tag, ignoreCase = true), onClick = { onChange(filters.copy(tag = tag)) })
                }
            }
        }
    }
    FilterGroup(stringResource(R.string.search_source)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PaperButton(
                text = sourceName ?: stringResource(R.string.search_source_any),
                onClick = onPickSource,
                modifier = Modifier.weight(1f, fill = false),
                icon = PaperIcons.Feed,
                primary = filters.feedId != null,
            )
            if (filters.feedId != null) {
                TextLink(stringResource(R.string.search_source_any), onClick = { onChange(filters.copy(feedId = null)) })
            }
        }
    }
    if (filters.isActive) {
        Box(Modifier.padding(top = 20.dp)) {
            PaperButton(stringResource(R.string.search_clear_filters), onClick = { onChange(SearchFilters.None) }, icon = Icons.Filled.Close)
        }
    }
}

/** The source filter's choices as a list in a dialog: any source, then every feed by name. */
@Composable
private fun SourcePicker(feeds: List<Feed>, selected: Long?, onDismiss: () -> Unit, onSelect: (Long?) -> Unit) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography

    @Composable
    fun choice(name: String, id: Long?) {
        val isSelected = id == selected
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(id) })
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(18.dp)) {
                if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
            }
            Text(name, style = type.headlineSmall, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }

    PaperDialog(title = stringResource(R.string.search_source), onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            choice(stringResource(R.string.search_source_any), null)
            feeds.forEach { feed ->
                HairlineRule()
                choice(feed.title, feed.id)
            }
        }
        DialogButtons { PaperButton(stringResource(R.string.action_cancel), onDismiss) }
    }
}

/** What the search has to show: a prompt before anything is typed, nothing found, or the list. */
@Composable
private fun SearchResults(
    text: String,
    results: List<ArticleSummary>?,
    filtered: Boolean,
    onClearFilters: () -> Unit,
    onOpenArticle: (Long) -> Unit,
) {
    when {
        text.isBlank() -> EmptyMessage(
            title = stringResource(R.string.search_prompt_title),
            detail = stringResource(if (filtered) R.string.search_prompt_filtered else R.string.search_prompt_detail),
        )
        results == null -> Unit
        results.isEmpty() -> EmptyMessage(
            title = stringResource(R.string.search_empty_title),
            detail = stringResource(if (filtered) R.string.search_empty_filtered else R.string.search_empty_detail),
        ) {
            if (filtered) PaperButton(stringResource(R.string.search_clear_filters), onClick = onClearFilters)
        }
        else -> PagedList(
            items = results,
            key = { it.articleId },
            header = {
                val count = pluralStringResource(R.plurals.search_result_count, results.size, results.size)
                SectionLabel(
                    text = if (filtered) stringResource(R.string.search_result_filtered, count) else count,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            },
        ) { article ->
            ArticleListItem(article, onClick = { onOpenArticle(article.articleId) })
        }
    }
}
