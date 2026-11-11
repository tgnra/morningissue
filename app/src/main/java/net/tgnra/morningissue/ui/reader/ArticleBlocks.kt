package net.tgnra.morningissue.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import net.tgnra.morningissue.model.ContentBlock
import net.tgnra.morningissue.model.InlineStyle
import net.tgnra.morningissue.model.RichText
import net.tgnra.morningissue.ui.components.PaperImage
import net.tgnra.morningissue.ui.components.stepAnchor
import net.tgnra.morningissue.ui.components.stepAnchorWithLines
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** Images narrower than this (when the source says) are tracking pixels or inline icons, not pictures. */
private const val MIN_IMAGE_WIDTH_PX = 80

/**
 * Turns the block model's text and spans into styled text. Spans that fall outside the text
 * (a sloppy converter, a truncated body) are clamped or dropped rather than crashing.
 */
fun RichText.toAnnotatedString(linkColor: Color, codeBackground: Color = Color.Transparent): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    for (span in spans) {
        val start = span.start.coerceIn(0, text.length)
        val end = span.end.coerceIn(0, text.length)
        if (start >= end) continue
        when (span.style) {
            InlineStyle.BOLD -> builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
            InlineStyle.ITALIC -> builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
            InlineStyle.CODE -> builder.addStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.86.em, background = codeBackground),
                start,
                end,
            )
            InlineStyle.STRIKETHROUGH -> builder.addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
            InlineStyle.SUPERSCRIPT -> builder.addStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.7.em), start, end)
            InlineStyle.SUBSCRIPT -> builder.addStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.7.em), start, end)
            InlineStyle.LINK -> {
                val style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                val href = span.href
                if (href.isNullOrBlank()) {
                    builder.addStyle(style, start, end)
                } else {
                    builder.addLink(LinkAnnotation.Url(href, TextLinkStyles(style)), start, end)
                }
            }
        }
    }
    return builder.toAnnotatedString()
}

/** How far a code block is shrunk in E-ink so that its lines fit the column without wrapping. */
object CodeFit {
    /** Below this the type is too small to read; longer lines wrap instead. */
    const val MIN_SCALE = 0.7f

    /**
     * The factor to set code at when its longest line is [longestLinePx] wide at full size and
     * the column is [availablePx] wide: 1 when it fits already, otherwise just small enough to
     * fit, and never below [MIN_SCALE].
     */
    fun scale(longestLinePx: Int, availablePx: Int): Float = when {
        longestLinePx <= 0 || availablePx <= 0 || longestLinePx <= availablePx -> 1f
        // A hair under the exact ratio, so rounding never tips the longest line over.
        else -> (availablePx.toFloat() / longestLinePx * 0.99f).coerceIn(MIN_SCALE, 1f)
    }
}

private fun TextStyle.scaled(factor: Float): TextStyle = copy(fontSize = fontSize * factor, lineHeight = lineHeight * factor)

private fun lineTops(layout: TextLayoutResult): IntArray = IntArray(layout.lineCount) { layout.getLineTop(it).toInt() }

/** A run of article text; reports its line tops so E-ink steps land between lines. */
@Composable
private fun BodyText(text: RichText, style: TextStyle, modifier: Modifier = Modifier, color: Color = MorningIssueTheme.colors.ink) {
    val colors = MorningIssueTheme.colors
    val codeBackground = if (colors.isEink) Color.Transparent else colors.hairline.copy(alpha = 0.5f)
    val annotated = remember(text, colors.accent, codeBackground) { text.toAnnotatedString(colors.accent, codeBackground) }
    val (anchored, reportLines) = modifier.stepAnchorWithLines()
    Text(annotated, style = style, color = color, modifier = anchored, onTextLayout = { reportLines(lineTops(it)) })
}

/**
 * The article body, rendered natively from the block model in the paper's typography.
 * [textScale] is the reader's text-size setting.
 */
@Composable
fun ArticleBody(
    blocks: List<ContentBlock>,
    textScale: Float,
    modifier: Modifier = Modifier,
    /** Called with a picture's address and description when it is tapped; null leaves pictures inert. */
    onImageClick: ((url: String, description: String?) -> Unit)? = null,
) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val body = type.reader.scaled(textScale)
    val gap = (body.lineHeight.value * 0.7f).dp

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        for (block in blocks) {
            when (block) {
                is ContentBlock.Paragraph -> BodyText(block.text, body)

                is ContentBlock.Heading -> {
                    val style = when {
                        block.level <= 2 -> type.readerHeading.scaled(textScale)
                        block.level == 3 -> type.readerHeading.scaled(textScale * 0.86f)
                        else -> body.copy(fontWeight = FontWeight.Bold)
                    }
                    BodyText(block.text, style, Modifier.padding(top = gap * 0.6f).semantics { heading() })
                }

                is ContentBlock.Image -> if ((block.width ?: MIN_IMAGE_WIDTH_PX) >= MIN_IMAGE_WIDTH_PX) {
                    Column(Modifier.fillMaxWidth().stepAnchor(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val width = block.width
                        val height = block.height
                        val open = onImageClick?.let { open -> { open(block.url, block.alt) } }
                        if (width != null && height != null && width > 0 && height > 0) {
                            PaperImage(
                                url = block.url,
                                contentDescription = block.alt,
                                modifier = Modifier.fillMaxWidth().aspectRatio((width.toFloat() / height).coerceIn(0.5f, 3f)),
                                onClick = open,
                            )
                        } else {
                            PaperImage(block.url, block.alt, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth, onClick = open)
                        }
                        block.caption?.takeIf { it.text.isNotBlank() }?.let {
                            BodyText(it, type.caption.scaled(textScale), color = colors.inkSecondary)
                        }
                    }
                }

                is ContentBlock.Quote -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind { drawRect(colors.rule, Offset.Zero, Size(3.dp.toPx(), size.height)) }
                        .padding(start = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(gap * 0.7f),
                ) {
                    block.paragraphs.forEach { BodyText(it, type.quote.scaled(textScale)) }
                    block.citation?.takeIf { it.text.isNotBlank() }?.let {
                        BodyText(RichText("— " + it.text), type.byline.scaled(textScale), color = colors.inkSecondary)
                    }
                }

                is ContentBlock.ItemList -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap * 0.45f)) {
                    block.items.forEachIndexed { index, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = if (block.ordered) "${index + 1}." else "•",
                                style = body,
                                color = colors.inkSecondary,
                                modifier = Modifier.widthIn(min = 22.dp),
                            )
                            BodyText(item, body, Modifier.weight(1f))
                        }
                    }
                }

                is ContentBlock.Preformatted -> {
                    val code = block.text.trimEnd()
                    val style = type.code.scaled(textScale)
                    if (colors.isEink) {
                        // E-ink never scrolls freely, so code cannot slide sideways. It is set
                        // smaller instead, as far as it takes for the longest line to fit (down
                        // to a floor that is still readable); only lines longer than that wrap.
                        BoxWithConstraints(Modifier.fillMaxWidth().stepAnchor().border(1.dp, colors.ink).padding(12.dp)) {
                            val measurer = rememberTextMeasurer()
                            val available = constraints.maxWidth
                            val fitted = remember(code, style, available) {
                                val longest = measurer.measure(code, style, softWrap = false).size.width
                                style.scaled(CodeFit.scale(longest, available))
                            }
                            Text(code, style = fitted, color = colors.ink, softWrap = true)
                        }
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .stepAnchor()
                                .background(colors.hairline.copy(alpha = 0.4f))
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            Text(code, style = style, color = colors.ink, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}
