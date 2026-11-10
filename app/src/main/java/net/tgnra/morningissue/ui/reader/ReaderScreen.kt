package net.tgnra.morningissue.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.ReaderArticle
import net.tgnra.morningissue.sync.FullTextResult
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.HairlineRule
import net.tgnra.morningissue.ui.components.LocalPagedScroller
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.PagedFooter
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperIconButton
import net.tgnra.morningissue.ui.components.PaperIcons
import net.tgnra.morningissue.ui.components.PaperImage
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.ThinProgressBar
import net.tgnra.morningissue.ui.components.pagedKeys
import net.tgnra.morningissue.ui.components.pagedVerticalScroll
import net.tgnra.morningissue.ui.components.rememberPagedScroller
import net.tgnra.morningissue.ui.components.stepAnchor
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

private class Loaded(val article: ReaderArticle?)

/** What the full-text fetch for this article is doing. */
private enum class FullText { Fetching, Done, Unavailable }

/**
 * An article in the paper's typography, rendered natively from the block model. Scroll depth
 * and time spent here are the Read signal, so this screen is part of the ranking, not just
 * presentation.
 */
@Composable
fun ReaderScreen(
    articleId: Long,
    onOpenSource: (feedId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val loaded by remember(container, articleId) { container.articles.observeReaderArticle(articleId).map { Loaded(it) } }
        .collectAsStateWithLifecycle(initialValue = null)

    var fullText by remember(articleId) { mutableStateOf(FullText.Fetching) }
    LaunchedEffect(articleId) {
        container.articles.markOpened(articleId)
        // Cheap when complete text is already stored; otherwise the body arrives through the flow.
        fullText = when (container.fullTextFetcher.ensureFullText(articleId)) {
            is FullTextResult.Unavailable -> FullText.Unavailable
            else -> FullText.Done
        }
    }

    val state = loaded
    val article = state?.article
    when {
        state == null -> Box(Modifier.fillMaxSize().background(MorningIssueTheme.colors.paper))
        article == null -> PaperScreenFrame(title = stringResource(R.string.app_name), onBack = onBack) {
            EmptyMessage(stringResource(R.string.reader_missing_title), stringResource(R.string.reader_missing_detail))
        }
        else -> Reader(article, fullText, onOpenSource, onBack)
    }
}

@Composable
private fun Reader(article: ReaderArticle, fullText: FullText, onOpenSource: (Long) -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val spec = LocalPaperLayoutSpec.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val articleId = article.articleId

    var sizeStep by remember { mutableIntStateOf(ReaderTextSize.load(context)) }
    var sizeControlsOpen by rememberSaveable { mutableStateOf(false) }
    val textScale = ReaderTextSize.steps[sizeStep]

    // One line of body text of overlap between E-ink steps.
    val scroller = rememberPagedScroller(overlap = (type.reader.lineHeight.value * textScale).dp)
    val scrollState = scroller.scrollState
    // Rounded to half a percent, so scrolling recomposes the bar a couple of hundred times at most, not per pixel.
    val fraction by remember(scroller) {
        derivedStateOf { (ReadRule.scrollFraction(scrollState.value, scroller.maxScroll) * 200f).roundToInt() / 200f }
    }
    val fitsOnScreen by remember(scroller) { derivedStateOf { scroller.maxScroll == 0 } }

    // --- The Read signal: furthest scroll position, and time spent with the article open ---
    var furthest by remember(articleId) { mutableFloatStateOf(0f) }
    LaunchedEffect(articleId) {
        var reported = 0f
        snapshotFlow { ReadRule.scrollFraction(scrollState.value, scroller.maxScroll) }.collect { now ->
            if (now > furthest) furthest = now
            if (ReadRule.shouldReport(reported, now)) {
                reported = now
                container.articles.setReadFraction(articleId, now)
            }
        }
    }
    DisposableEffect(articleId) {
        onDispose {
            // Leaving between two reporting steps must not lose the last stretch.
            val last = furthest
            if (last > 0f) container.appScope.launch { container.articles.setReadFraction(articleId, last) }
        }
    }
    var dwellMillis by rememberSaveable(articleId) { mutableLongStateOf(0L) }
    val dwellThreshold = ReadRule.dwellThresholdMillis(article.readMinutes)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(articleId, dwellThreshold, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (dwellMillis < dwellThreshold) {
                delay(DWELL_TICK_MILLIS)
                dwellMillis += DWELL_TICK_MILLIS
            }
            container.articles.markRead(articleId)
        }
    }

    val forward: () -> Unit = { scope.launch { scroller.stepForward() } }
    val backward: () -> Unit = { scope.launch { scroller.stepBackward() } }
    val openInBrowser: (() -> Unit)? = article.url?.let { url -> { runCatching { uriHandler.openUri(url) } } }

    Box(Modifier.fillMaxSize().background(colors.paper).pagedKeys(forward, backward)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                PaperIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
                SectionLabel(
                    text = stringResource(R.string.minutes_read, article.readMinutes),
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                PaperIconButton(PaperIcons.TextSize, stringResource(R.string.reader_text_size), { sizeControlsOpen = !sizeControlsOpen })
                if (openInBrowser != null) {
                    PaperIconButton(PaperIcons.OpenInBrowser, stringResource(R.string.reader_open_browser), openInBrowser)
                }
            }
            val progressLabel = stringResource(R.string.reader_progress)
            ThinProgressBar(
                // A body that fits the screen has nowhere to scroll: it is all in view.
                fraction = if (fitsOnScreen) 1f else fraction,
                modifier = Modifier.semantics {
                    contentDescription = progressLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                },
            )
            if (sizeControlsOpen) {
                TextSizeRow(sizeStep) { step ->
                    sizeStep = ReaderTextSize.clamp(step)
                    ReaderTextSize.save(context, sizeStep)
                }
            }

            CompositionLocalProvider(LocalPagedScroller provides scroller) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .pagedVerticalScroll(scroller)
                            .padding(horizontal = spec.pageMargin)
                            .padding(top = 20.dp, bottom = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(Modifier.measure(spec.readerMaxMeasure)) {
                            ArticleHeader(article, onOpenSource)
                            Spacer(Modifier.height(20.dp))
                            ArticleStatus(article, fullText)
                            ArticleBody(article.blocks, textScale)
                            Spacer(Modifier.height(36.dp))
                            ArticleFooter(
                                article = article,
                                onLike = { scope.launch { container.articles.setLiked(articleId, !article.isLiked) } },
                                onSave = { scope.launch { container.articles.setSaved(articleId, !article.isSaved) } },
                                onOpenInBrowser = openInBrowser,
                            )
                        }
                    }
                }
            }
            if (MorningIssueTheme.isEink) {
                PagedFooter(scroller.canStepBackward, scroller.canStepForward, backward, forward)
            }
        }
    }
}

private const val DWELL_TICK_MILLIS = 1000L

/** Caps the text column at the layout's measure (about 66 characters); full width on phones. */
private fun Modifier.measure(max: Dp): Modifier =
    if (max == Dp.Unspecified) fillMaxWidth() else widthIn(max = max).fillMaxWidth()

@Composable
private fun ArticleHeader(article: ReaderArticle, onOpenSource: (Long) -> Unit) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Box(
        Modifier
            .stepAnchor()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { onOpenSource(article.feedId) },
        contentAlignment = Alignment.CenterStart,
    ) {
        SectionLabel(article.feedTitle, color = colors.accent)
    }
    Text(article.title, style = type.leadHeadline, color = colors.ink, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(12.dp))
    Text(
        text = listOfNotNull(
            article.author?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.reader_by, it) },
            PaperFormat.articleDate(article.publishedAt),
            stringResource(R.string.minutes_read, article.readMinutes),
        ).joinToString("  ·  "),
        style = type.byline,
        color = colors.inkSecondary,
    )
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))

    // The feed's picture leads the article unless the body already opens with it.
    val opensWithImage = article.blocks.take(2).any { it is ContentBlock.Image }
    if (article.imageUrl != null && !opensWithImage) {
        Spacer(Modifier.height(20.dp))
        PaperImage(article.imageUrl, contentDescription = null, modifier = Modifier.fillMaxWidth().aspectRatio(3f / 2f))
    }
}

/** Says what the reader is looking at when it is not the full article. */
@Composable
private fun ArticleStatus(article: ReaderArticle, fullText: FullText) {
    val hasStoredBody = article.contentSource != null
    val message = when {
        hasStoredBody -> null
        fullText == FullText.Fetching -> stringResource(R.string.reader_fetching)
        article.blocks.isEmpty() -> stringResource(R.string.reader_no_text)
        else -> stringResource(R.string.reader_summary_only)
    }
    if (message != null) {
        NoticeLine(message)
        Spacer(Modifier.height(20.dp))
    }
}

/** Like and Save at the end of the article, where the reader has earned an opinion. */
@Composable
private fun ArticleFooter(article: ReaderArticle, onLike: () -> Unit, onSave: () -> Unit, onOpenInBrowser: (() -> Unit)?) {
    val colors = MorningIssueTheme.colors
    Column(Modifier.fillMaxWidth().stepAnchor(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(56.dp).height(3.dp).background(colors.rule))
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperButton(
                text = stringResource(if (article.isLiked) R.string.reader_liked else R.string.reader_like),
                onClick = onLike,
                modifier = Modifier.weight(1f),
                icon = if (article.isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                primary = article.isLiked,
            )
            PaperButton(
                text = stringResource(if (article.isSaved) R.string.reader_saved else R.string.reader_save),
                onClick = onSave,
                modifier = Modifier.weight(1f),
                icon = if (article.isSaved) PaperIcons.Bookmark else PaperIcons.BookmarkBorder,
                primary = article.isSaved,
            )
        }
        if (onOpenInBrowser != null) {
            Spacer(Modifier.height(12.dp))
            PaperButton(
                text = stringResource(R.string.reader_open_browser),
                onClick = onOpenInBrowser,
                modifier = Modifier.fillMaxWidth(),
                icon = PaperIcons.OpenInBrowser,
            )
        }
    }
}

/** The text-size ladder: smaller, a sample of each step, larger. */
@Composable
private fun TextSizeRow(step: Int, onStep: (Int) -> Unit) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Column(Modifier.fillMaxWidth().background(colors.paperRaised)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                Modifier.size(48.dp).clickable(
                    enabled = step > 0,
                    onClickLabel = stringResource(R.string.reader_text_smaller),
                    role = Role.Button,
                ) { onStep(step - 1) },
                contentAlignment = Alignment.Center,
            ) {
                Text("A", style = type.reader.copy(fontSize = type.reader.fontSize * 0.8f), color = if (step > 0) colors.ink else colors.hairline)
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ReaderTextSize.steps.indices.forEach { index ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(if (index == step) 6.dp else 2.dp)
                            .background(if (index <= step) colors.ink else colors.hairline),
                    )
                }
            }
            Box(
                Modifier.size(48.dp).clickable(
                    enabled = step < ReaderTextSize.steps.lastIndex,
                    onClickLabel = stringResource(R.string.reader_text_larger),
                    role = Role.Button,
                ) { onStep(step + 1) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "A",
                    style = type.reader.copy(fontSize = type.reader.fontSize * 1.35f, fontStyle = FontStyle.Normal),
                    color = if (step < ReaderTextSize.steps.lastIndex) colors.ink else colors.hairline,
                )
            }
        }
        HairlineRule()
    }
}
