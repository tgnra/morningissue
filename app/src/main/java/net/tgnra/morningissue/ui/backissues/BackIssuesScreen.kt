package net.tgnra.morningissue.ui.backissues

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import net.tgnra.morningissue.ui.layout.PaperWidthClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.EditionKind
import net.tgnra.morningissue.model.EditionSummary
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.EmptyMessage
import net.tgnra.morningissue.ui.components.HairlineRule
import net.tgnra.morningissue.ui.components.HeavyRule
import net.tgnra.morningissue.ui.components.PagedColumn
import net.tgnra.morningissue.ui.components.PaperIconButton
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.ReadTick
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.boxOutlineColor
import net.tgnra.morningissue.ui.components.stepAnchor
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * Every stored edition on a month calendar, read ones ticked, with the month's editions listed
 * beneath by lead headline. Months change at once, without a slide, in every display mode.
 */
@Composable
fun BackIssuesScreen(
    onOpenEdition: (editionId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val margin = LocalPaperLayoutSpec.current.pageMargin
    val editions by net.tgnra.morningissue.ui.components.retainedState("editions") { container.editions.observeReleasedEditions() }

    PaperScreenFrame(title = stringResource(R.string.backissues_title), onBack = onBack) {
        val list = editions
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyMessage(stringResource(R.string.backissues_empty_title), stringResource(R.string.backissues_empty_detail))
            else -> {
                // One cell per day: the morning edition stands for the day if there are two.
                val byDate = remember(list) {
                    list.sortedBy { it.kind != EditionKind.MORNING }.groupBy { it.date }.mapValues { it.value.first() }
                }
                val newest = YearMonth.from(list.maxOf { it.date })
                val oldest = YearMonth.from(list.minOf { it.date })
                var monthText by rememberSaveable { mutableStateOf(newest.toString()) }
                val month = YearMonth.parse(monthText).coerceIn(oldest, newest)

                val calendar: @Composable () -> Unit = {
                    MonthHeader(
                        month = month,
                        canGoBack = month > oldest,
                        canGoForward = month < newest,
                        onChange = { monthText = it.toString() },
                    )
                    MonthCalendar(month, byDate, onOpenEdition)
                }
                val issues: @Composable () -> Unit = {
                    val inMonth = list.filter { YearMonth.from(it.date) == month }
                    SectionLabel(stringResource(R.string.backissues_this_month), color = MorningIssueTheme.colors.ink, heading = true)
                    HeavyRule(Modifier.padding(top = 6.dp))
                    if (inMonth.isEmpty()) {
                        Text(
                            stringResource(R.string.backissues_none),
                            style = MorningIssueTheme.typography.body,
                            color = MorningIssueTheme.colors.inkSecondary,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                    inMonth.forEachIndexed { index, edition ->
                        if (index > 0) HairlineRule()
                        EditionLine(edition, onClick = { onOpenEdition(edition.id) })
                    }
                }
                if (LocalPaperLayoutSpec.current.widthClass == PaperWidthClass.Expanded) {
                    // A tablet in landscape is wide and short: the month's editions go beside
                    // the calendar instead of under it, where they would be off the screen.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Row(Modifier.widthIn(max = 1180.dp).fillMaxSize()) {
                            PagedColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(start = margin, end = margin, bottom = 32.dp),
                                maxWidth = 520.dp,
                            ) { calendar() }
                            PagedColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(start = margin, end = margin, top = 24.dp, bottom = 32.dp),
                                maxWidth = 560.dp,
                            ) { issues() }
                        }
                    }
                } else {
                    PagedColumn(contentPadding = PaddingValues(start = margin, end = margin, bottom = 32.dp), maxWidth = 560.dp) {
                        calendar()
                        Spacer(Modifier.height(28.dp))
                        issues()
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(month: YearMonth, canGoBack: Boolean, canGoForward: Boolean, onChange: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PaperIconButton(
            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            stringResource(R.string.backissues_previous_month),
            onClick = { onChange(month.minusMonths(1)) },
            enabled = canGoBack,
        )
        Text(
            text = PaperFormat.monthTitle(month),
            style = MorningIssueTheme.typography.headline,
            color = MorningIssueTheme.colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        PaperIconButton(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            stringResource(R.string.backissues_next_month),
            onClick = { onChange(month.plusMonths(1)) },
            enabled = canGoForward,
        )
    }
}

@Composable
private fun MonthCalendar(month: YearMonth, editions: Map<LocalDate, EditionSummary>, onOpenEdition: (Long) -> Unit) {
    val colors = MorningIssueTheme.colors
    val locale = LocalLocale.current.platformLocale
    val firstDay = WeekFields.of(locale).firstDayOfWeek
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            PaperFormat.weekDays(firstDay).forEach { day ->
                Text(
                    text = day.getDisplayName(TextStyle.SHORT, locale).uppercase(locale),
                    style = MorningIssueTheme.typography.sectionLabel,
                    color = colors.inkSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).clearAndSetSemantics { },
                )
            }
        }
        HeavyRule()
        Spacer(Modifier.height(6.dp))
        PaperFormat.monthGrid(month, firstDay).forEach { week ->
            Row(Modifier.fillMaxWidth().stepAnchor()) {
                week.forEach { date ->
                    Box(Modifier.weight(1f).padding(2.dp)) {
                        if (date != null) DayCell(date, editions[date], onOpenEdition)
                    }
                }
            }
        }
    }
}

/** A day: boxed and bold when it has an edition, with a tick once that edition has been read. */
@Composable
private fun DayCell(date: LocalDate, edition: EditionSummary?, onOpenEdition: (Long) -> Unit) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val description = if (edition == null) {
        PaperFormat.longDate(date)
    } else {
        stringResource(R.string.backissues_day_edition, PaperFormat.longDate(date), edition.number) +
            if (edition.isRead) ". " + stringResource(R.string.read_tick) else ""
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .then(
                if (edition != null) {
                    Modifier
                        .background(colors.paperRaised)
                        .border(1.dp, boxOutlineColor)
                        .clickable(role = Role.Button) { onOpenEdition(edition.id) }
                } else {
                    Modifier
                },
            )
            .clearAndSetSemantics { contentDescription = description }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = date.dayOfMonth.toString(),
            style = if (edition != null) type.headlineSmall else type.body.copy(fontWeight = FontWeight.Normal),
            color = if (edition != null) colors.ink else colors.inkSecondary,
        )
        when {
            edition == null -> Spacer(Modifier.height(12.dp))
            edition.isRead -> ReadTick(size = 12.dp)
            // Unread: a small square where the tick will go.
            else -> Box(Modifier.height(12.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(5.dp).background(colors.accent)) }
        }
    }
}

@Composable
private fun EditionLine(edition: EditionSummary, onClick: () -> Unit) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Row(
        Modifier.fillMaxWidth().stepAnchor().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(104.dp)) {
            SectionLabel(PaperFormat.shortDate(edition.date), color = colors.ink)
            Text(stringResource(R.string.edition_number, edition.number), style = type.caption, color = colors.inkSecondary)
        }
        Text(
            text = edition.leadHeadline ?: stringResource(R.string.backissues_no_lead),
            style = type.headlineSmall,
            color = if (edition.isRead) colors.inkSecondary else colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (edition.isRead) ReadTick()
    }
}
