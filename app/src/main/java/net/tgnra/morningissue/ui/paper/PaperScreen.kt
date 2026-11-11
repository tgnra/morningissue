package net.tgnra.morningissue.ui.paper

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.Edition
import net.tgnra.morningissue.model.EditionPage
import net.tgnra.morningissue.model.Feed
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.editionNumberLabel
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.HairlineRule
import net.tgnra.morningissue.ui.components.LocalPagedScroller
import net.tgnra.morningissue.ui.components.PagedFooter
import net.tgnra.morningissue.ui.components.PagedScroller
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperIconButton
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.ThinProgressBar
import net.tgnra.morningissue.ui.components.pagedKeys
import net.tgnra.morningissue.ui.components.pagedVerticalScroll
import net.tgnra.morningissue.ui.components.paperShadow
import net.tgnra.morningissue.ui.components.rememberPagedScroller
import net.tgnra.morningissue.ui.components.retainedState
import net.tgnra.morningissue.ui.components.turnToPage
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.learning.Mutes
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** Distinguishes "still loading" (no value yet) from "loaded, and there is no such edition". */
private class Loaded(val edition: Edition?)

/**
 * One edition as a pager of pages, front page to back page. The page is full screen: a folio
 * line with the progress bar is the only furniture, and it opens the contents list.
 */
@Composable
fun PaperScreen(
    editionId: Long,
    onOpenArticle: (articleId: Long) -> Unit,
    onOpenSource: (feedId: Long) -> Unit,
    onOpenBackIssues: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSubscriptionsDesk: () -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val loaded by retainedState("edition-$editionId") { container.editions.observeEdition(editionId).map { Loaded(it) } }
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    // The story whose menu is open. An ID, so the menu follows the story's state as it changes.
    var menuArticleId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(editionId) { container.editions.markEditionOpened(editionId) }

    val state = loaded
    val edition = state?.edition
    when {
        state == null -> LoadingPaper(onBack)
        edition == null -> PaperScreenFrame(title = stringResource(R.string.app_name), onBack = onBack) {
            EmptyMessage(stringResource(R.string.paper_missing_title), stringResource(R.string.paper_missing_detail))
        }
        else -> {
            // Once a month the edition carries the Subscriptions desk, as a notice on its back page.
            val deskNotice by produceState<Int?>(null, edition.id) {
                value = container.feedHealth.deskNotice(edition.id, edition.date)
            }
            val links = BackPageLinks(
                fallbackNextEditionAt = settings?.let {
                    PaperFormat.nextReset(container.clock.instant(), it.resetTime, ZoneId.systemDefault())
                },
                onOpenBackIssues = onOpenBackIssues,
                onOpenSearch = onOpenSearch,
                onBackToStand = onBack,
                deskNoticeCount = deskNotice,
                onOpenSubscriptionsDesk = onOpenSubscriptionsDesk,
            )
            val storyActions = remember(container, onOpenArticle, onOpenSource) {
                StoryActions(
                    onOpenArticle = onOpenArticle,
                    onOpenSource = onOpenSource,
                    onOpenMenu = { menuArticleId = it.articleId },
                    onToggleSaved = { scope.launch { container.articles.setSaved(it.articleId, !it.isSaved) } },
                    onToggleLiked = { scope.launch { container.articles.setLiked(it.articleId, !it.isLiked) } },
                )
            }
            val menu = menuArticleId?.let { id ->
                edition.pages.firstNotNullOfOrNull { page -> page.stories.firstOrNull { it.articleId == id }?.let { page to it } }
            }
            Box(Modifier.fillMaxSize()) {
                // While the menu is up, the page under it is not there for screen readers.
                Box(if (menu != null) Modifier.fillMaxSize().clearAndSetSemantics { } else Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalStoryActions provides storyActions) {
                        Paper(edition, links, onFinished = { container.appScope.launch { container.editions.markEditionFinished(editionId) } })
                    }
                }
                if (menu != null) {
                    val (page, story) = menu
                    val feed by retainedState("menu-feed-${story.feedId}") { container.feeds.observeFeed(story.feedId).map { MenuFeed(it) } }
                    val feedId = story.feedId
                    StoryMenu(
                        story = story,
                        sectionName = (page as? EditionPage.Section)?.name,
                        feed = feed?.feed,
                        now = container.clock.instant(),
                        actions = StoryMenuActions(
                            onSavedChange = { scope.launch { container.articles.setSaved(story.articleId, it) } },
                            onLikedChange = { scope.launch { container.articles.setLiked(story.articleId, it) } },
                            onStepChange = { scope.launch { container.feeds.setSliderStep(feedId, it) } },
                            onMute = { days -> scope.launch { container.feeds.setMutedUntil(feedId, Mutes.until(container.clock.instant(), days)) } },
                            onUnmute = { scope.launch { container.feeds.setMutedUntil(feedId, null) } },
                            onOpenSource = {
                                menuArticleId = null
                                onOpenSource(feedId)
                            },
                            onDismiss = { menuArticleId = null },
                        ),
                    )
                }
            }
        }
    }
}

private class MenuFeed(val feed: Feed?)

@Composable
private fun Paper(edition: Edition, links: BackPageLinks, onFinished: () -> Unit) {
    val colors = MorningIssueTheme.colors
    val spec = LocalPaperLayoutSpec.current
    val turnStyle = rememberPageTurnStyle()
    val animate = turnStyle != PageTurnStyle.None
    val turnAnimation = if (turnStyle == PageTurnStyle.Turn) PageTurnAnimation else null
    val scope = rememberCoroutineScope()
    val pages = edition.pages
    val pagerState = rememberPagerState { pages.size }
    var contentsOpen by rememberSaveable { mutableStateOf(false) }
    // The scroller of each composed page, so keys and the E-ink footer can step the visible one.
    val scrollers = remember { mutableStateMapOf<Int, PagedScroller>() }

    LaunchedEffect(pagerState.settledPage, pages.size) {
        if (pagerState.settledPage == pages.lastIndex) onFinished()
    }

    val turnTo: (Int) -> Unit = { page -> scope.launch { pagerState.turnToPage(page, animate, turnAnimation) } }
    // "Onward": the rest of this page first, then the next page.
    val forward: () -> Unit = {
        scope.launch {
            val page = pagerState.currentPage
            if (scrollers[page]?.stepForward() != true) pagerState.turnToPage(page + 1, animate, turnAnimation)
        }
    }
    val backward: () -> Unit = {
        scope.launch {
            val page = pagerState.currentPage
            if (scrollers[page]?.stepBackward() != true) pagerState.turnToPage(page - 1, animate, turnAnimation)
        }
    }

    Box(Modifier.fillMaxSize().background(colors.paper).pagedKeys(forward, backward)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            FolioLine(
                edition = edition,
                page = pagerState.currentPage,
                pageCount = pages.size,
                onBack = links.onBackToStand,
                onOpenContents = { contentsOpen = !contentsOpen },
            )
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // Clipped: a sheet lifting towards the reader looms taller than the page.
                Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        // E-ink turns whole pages on a swipe, key or button, never by dragging.
                        userScrollEnabled = animate,
                        // The pages either side are kept ready, so a turn has its other sheet from the first frame.
                        beyondViewportPageCount = if (turnStyle == PageTurnStyle.Turn) 1 else 0,
                        key = { pages[it].number },
                    ) { index ->
                        PageSheet(
                            index = index,
                            pagerState = pagerState,
                            turnStyle = turnStyle,
                            scrollers = scrollers,
                            onHorizontalSwipe = { onward -> turnTo(pagerState.currentPage + if (onward) 1 else -1) },
                        ) {
                            EditionPageContent(edition, pages[index], links)
                        }
                    }
                    if (contentsOpen && !spec.readerSidePanel) {
                        ContentsOverlay(pages, pagerState.currentPage, onDismiss = { contentsOpen = false }) {
                            contentsOpen = false
                            turnTo(it)
                        }
                    }
                }
                if (contentsOpen && spec.readerSidePanel) {
                    // Tablet landscape: the contents list stays open beside the page.
                    Box(Modifier.fillMaxHeight().width(1.dp).background(colors.rule))
                    ContentsList(pages, pagerState.currentPage, Modifier.width(320.dp).fillMaxHeight(), onSelect = turnTo)
                }
            }
            if (!animate) {
                PagedFooter(
                    canGoBack = pagerState.currentPage > 0 || scrollers[pagerState.currentPage]?.canStepBackward == true,
                    canGoForward = pagerState.currentPage < pages.lastIndex || scrollers[pagerState.currentPage]?.canStepForward == true,
                    onBackward = backward,
                    onForward = forward,
                )
            }
        }
    }
    BackHandler(enabled = contentsOpen && !spec.readerSidePanel) { contentsOpen = false }
}

/**
 * The paper before its edition has been read from the database: the folio line with the way
 * back and the front page's masthead, drawn at once in the places they will keep, so opening
 * an edition never shows a blank screen.
 */
@Composable
private fun LoadingPaper(onBack: () -> Unit) {
    val spec = LocalPaperLayoutSpec.current
    Box(Modifier.fillMaxSize().background(MorningIssueTheme.colors.paper)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            FolioLine(edition = null, page = 0, pageCount = 0, onBack = onBack, onOpenContents = {})
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = 1180.dp)
                        .fillMaxWidth()
                        .padding(start = spec.pageMargin, end = spec.pageMargin, top = 8.dp),
                ) {
                    Masthead(edition = null, onBackToStand = onBack)
                }
            }
        }
    }
}

/** One page: its own vertical scroll (stepped in E-ink) inside the page margins. */
@Composable
private fun PageSheet(
    index: Int,
    pagerState: PagerState,
    turnStyle: PageTurnStyle,
    scrollers: MutableMap<Int, PagedScroller>,
    onHorizontalSwipe: (onward: Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val spec = LocalPaperLayoutSpec.current
    val scroller = rememberPagedScroller()
    DisposableEffect(index, scroller) {
        scrollers[index] = scroller
        onDispose { if (scrollers[index] === scroller) scrollers.remove(index) }
    }
    CompositionLocalProvider(LocalPagedScroller provides scroller) {
        Box(Modifier.fillMaxSize().pageTurn(pagerState, index, turnStyle), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = 1180.dp)
                    .fillMaxWidth()
                    .pagedVerticalScroll(scroller, onHorizontalSwipe)
                    .padding(start = spec.pageMargin, end = spec.pageMargin, top = 8.dp, bottom = 40.dp),
            ) {
                content()
            }
            PageTurnShading(pagerState, index, turnStyle)
        }
    }
}

/**
 * The line across the top of every page: back to the stand, the edition's date and number,
 * and "Page 3 of 7" over a thin progress bar. The page count opens the contents list.
 * With no [edition] yet, only the way back and the empty bar are drawn.
 */
@Composable
private fun FolioLine(edition: Edition?, page: Int, pageCount: Int, onBack: () -> Unit, onOpenContents: () -> Unit) {
    val colors = MorningIssueTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            PaperIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.paper_back_to_stand), onBack)
            SectionLabel(
                text = if (edition == null) "" else PaperFormat.shortDate(edition.date) + "  ·  " + editionNumberLabel(edition.kind, edition.number),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            // No page count until there are pages (the edition is still loading).
            if (pageCount > 0) Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = stringResource(R.string.paper_open_contents), role = Role.Button, onClick = onOpenContents)
                    .padding(start = 12.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionLabel(stringResource(R.string.page_x_of_y, page + 1, pageCount), color = colors.ink)
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = colors.ink, modifier = Modifier.size(20.dp))
            }
        }
        ThinProgressBar(if (pageCount > 0) (page + 1f) / pageCount else 0f)
    }
}

/** The contents list dropped over the page from under the folio line (phones, small tablets). */
@Composable
private fun ContentsOverlay(pages: List<EditionPage>, current: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    val colors = MorningIssueTheme.colors
    val eink = colors.isEink
    Box(
        Modifier
            .fillMaxSize()
            .background(if (eink) Color.Transparent else Color.Black.copy(alpha = if (colors.isDark) 0.7f else 0.35f))
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.TopEnd,
    ) {
        ContentsList(
            pages = pages,
            current = current,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .paperShadow(radius = 16.dp, offsetY = 6.dp)
                .background(colors.paperRaised)
                .border(if (eink) 2.dp else 1.dp, if (eink) colors.ink else colors.hairline)
                .pointerInput(Unit) { detectTapGestures { } },
            onSelect = onSelect,
        )
    }
}

@Composable
private fun ContentsList(pages: List<EditionPage>, current: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Column(modifier.background(colors.paperRaised).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionLabel(
            stringResource(R.string.paper_contents),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).semantics { heading() },
        )
        pages.forEachIndexed { index, page ->
            HairlineRule(Modifier.padding(horizontal = 20.dp))
            val isCurrent = index == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable(role = Role.Button) { onSelect(index) }
                    .semantics { selected = isCurrent }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    style = type.headlineSmall,
                    color = if (isCurrent) colors.accent else colors.inkSecondary,
                    modifier = Modifier.width(36.dp),
                )
                Column(Modifier.weight(1f)) {
                    // A tag's page is named after the tag; the kicker says what kind of page that is.
                    if (page is EditionPage.Section) SectionLabel(stringResource(R.string.contents_section))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = pageTitle(page),
                            style = type.headlineSmall,
                            color = if (isCurrent) colors.accent else colors.ink,
                            modifier = Modifier.weight(1f),
                        )
                        if (page !is EditionPage.Back && page.stories.isNotEmpty()) {
                            Text(
                                // In brief prints headlines only: they are lines, not stories.
                                text = pluralStringResource(
                                    if (page is EditionPage.InBrief) R.plurals.brief_count else R.plurals.story_count,
                                    page.stories.size,
                                    page.stories.size,
                                ),
                                style = type.caption,
                                color = colors.inkSecondary,
                                modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                    // Under the title and the count, so the headline has the row's full width.
                    val first = (page as? EditionPage.Front)?.lead?.title ?: page.stories.firstOrNull()?.title?.takeIf { page !is EditionPage.Back }
                    if (first != null) {
                        Text(first, style = type.byline, color = colors.inkSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.padding(start = 10.dp).size(8.dp).background(if (isCurrent) colors.accent else Color.Transparent))
            }
        }
    }
}
