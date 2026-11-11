package net.tgnra.morningissue.ui.settings

import android.content.ActivityNotFoundException
import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.data.archive.ArchiveFormat
import net.tgnra.morningissue.data.archive.ArchiveFormatException
import net.tgnra.morningissue.data.archive.ArchiveStatus
import net.tgnra.morningissue.data.storage.StorageUsage
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.ThinProgressBar
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** The storage budget's steps and how sizes are written. */
object StorageBudget {
    /**
     * The budgets on offer, in megabytes. Discrete, so the control is a stepper, not a drag.
     *
     * The top steps are past what an `Int` holds in bytes (2,047 MB), so a budget is only ever
     * turned into bytes as a `Long` (`StorageRepository`); nothing here multiplies megabytes.
     */
    val STEPS = listOf(50, 100, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** The step a stored budget sits on: the nearest one. */
    fun stepIndex(megabytes: Int): Int = STEPS.indices.minBy { kotlin.math.abs(STEPS[it] - megabytes) }

    fun lower(megabytes: Int): Int? = STEPS.getOrNull(stepIndex(megabytes) - 1)

    fun higher(megabytes: Int): Int? = STEPS.getOrNull(stepIndex(megabytes) + 1)

    /** "250 MB", "2 GB". The steps of 1000 and up read as round gigabytes. */
    fun label(megabytes: Int): String = if (megabytes >= 1000) "${megabytes / 1000} GB" else "$megabytes MB"

    /** A measured size: "under 1 MB", "132 MB", "1.4 GB". */
    fun size(bytes: Long, underOne: String, locale: Locale = Locale.getDefault()): String {
        val megabytes = bytes / (1024.0 * 1024.0)
        return when {
            megabytes < 1.0 -> underOne
            megabytes < 1000.0 -> String.format(locale, "%.0f MB", megabytes)
            else -> String.format(locale, "%.1f GB", megabytes / 1000.0)
        }
    }

    /** How full the budget is, 0..1. */
    fun fraction(usage: StorageUsage): Float =
        if (usage.budgetBytes <= 0) 1f else (usage.usedBytes.toDouble() / usage.budgetBytes).coerceIn(0.0, 1.0).toFloat()
}

/** The result or progress line of an archive job; null when there is nothing to say. */
internal fun archiveMessage(status: ArchiveStatus, resources: Resources): String? = when (status) {
    ArchiveStatus.Idle -> null
    is ArchiveStatus.Running -> {
        val progress = status.progress
        when {
            progress == null -> resources.getString(if (status.importing) R.string.archive_importing else R.string.archive_exporting)
            status.importing -> resources.getString(R.string.archive_importing_progress, progress.postsDone, progress.postsTotal)
            else -> resources.getString(R.string.archive_exporting_progress, progress.postsDone, progress.postsTotal)
        }
    }
    is ArchiveStatus.Exported -> buildString {
        val report = status.report
        append(resources.getQuantityString(R.plurals.archive_exported_editions, report.editions, report.editions))
        append(' ')
        append(resources.getQuantityString(R.plurals.archive_exported_posts, report.posts, report.posts))
        status.freed?.let { freed ->
            append(' ')
            append(
                resources.getQuantityString(
                    R.plurals.archive_freed,
                    freed.articlesTrimmed,
                    freed.articlesTrimmed,
                    StorageBudget.size(freed.bytesFreed, resources.getString(R.string.storage_under_one)),
                ),
            )
        }
    }
    is ArchiveStatus.Imported -> buildString {
        val report = status.report
        append(resources.getQuantityString(R.plurals.archive_imported_posts, report.postsImported, report.postsImported))
        append(resources.getString(R.string.archive_imported_skipped, report.postsSkipped))
        if (report.textsRestored > 0) {
            append(' ')
            append(resources.getQuantityString(R.plurals.archive_imported_restored, report.textsRestored, report.textsRestored))
        }
        if (report.editionsImported > 0 || report.editionsSkipped > 0) {
            append(' ')
            append(resources.getQuantityString(R.plurals.archive_imported_editions, report.editionsImported, report.editionsImported))
            append(resources.getString(R.string.archive_imported_skipped, report.editionsSkipped))
        }
        if (report.feedsAdded > 0) {
            append(' ')
            append(resources.getQuantityString(R.plurals.archive_imported_feeds, report.feedsAdded, report.feedsAdded))
        }
    }
    is ArchiveStatus.Failed -> resources.getString(
        when {
            status.reason == ArchiveFormatException.Reason.NEWER_VERSION -> R.string.archive_failed_newer
            status.reason == ArchiveFormatException.Reason.NOT_AN_ARCHIVE -> R.string.archive_failed_not_archive
            status.importing -> R.string.archive_import_failed
            else -> R.string.archive_export_failed
        },
    )
}

/**
 * Settings' Storage and Archive sections: the budget for stored article text with what is in
 * use, and export and import of the whole library through the system file picker.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StorageSettingsSections(budgetMb: Int) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val status by container.archive.status.collectAsStateWithLifecycle()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    // Bumped to measure again after a trim.
    var measured by remember { mutableIntStateOf(0) }
    var trimming by remember { mutableStateOf(false) }
    var pickerMissing by remember { mutableStateOf(false) }
    val running = status is ArchiveStatus.Running

    LaunchedEffect(budgetMb, running, measured) { usage = container.storage.usage() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ArchiveFormat.MIME_TYPE)) { uri ->
        if (uri != null) container.archive.export(uri, freeSpace = false)
    }
    val exportAndFreeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ArchiveFormat.MIME_TYPE)) { uri ->
        if (uri != null) container.archive.export(uri, freeSpace = true)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) container.archive.import(uri)
    }
    fun pick(launch: () -> Unit) {
        pickerMissing = false
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            pickerMissing = true
        }
    }
    val fileName = "morning-issue-${LocalDate.now()}.${ArchiveFormat.FILE_EXTENSION}"

    SettingsSection(stringResource(R.string.settings_storage)) {
        Setting(stringResource(R.string.storage_budget), stringResource(R.string.storage_budget_detail)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BudgetStepper(
                    megabytes = budgetMb,
                    onChange = { scope.launch { container.settings.setStorageBudgetMb(it) } },
                )
                usage?.let { current ->
                    ThinProgressBar(StorageBudget.fraction(current), Modifier.fillMaxWidth(), height = 6.dp)
                    Text(
                        stringResource(
                            R.string.storage_usage,
                            StorageBudget.size(current.usedBytes, stringResource(R.string.storage_under_one)),
                            StorageBudget.label(budgetMb),
                        ),
                        style = type.label,
                        color = colors.ink,
                    )
                    if (current.overBudget) {
                        Text(stringResource(R.string.storage_over_budget), style = type.body, color = colors.inkSecondary)
                        PaperButton(
                            stringResource(if (trimming) R.string.storage_trimming else R.string.storage_trim_now),
                            onClick = {
                                trimming = true
                                scope.launch {
                                    try {
                                        container.storage.trimToBudget()
                                    } finally {
                                        trimming = false
                                        measured++
                                    }
                                }
                            },
                            enabled = !trimming && !running,
                        )
                    }
                }
            }
        }
    }

    SettingsSection(stringResource(R.string.settings_archive)) {
        Setting(stringResource(R.string.archive_export), stringResource(R.string.archive_export_detail)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperButton(stringResource(R.string.archive_export_action), { pick { exportLauncher.launch(fileName) } }, enabled = !running)
                PaperButton(
                    stringResource(R.string.archive_export_free_action),
                    { pick { exportAndFreeLauncher.launch(fileName) } },
                    enabled = !running,
                )
            }
        }
        Setting(stringResource(R.string.archive_import), stringResource(R.string.archive_import_detail)) {
            PaperButton(stringResource(R.string.archive_import_action), { pick { importLauncher.launch(ARCHIVE_IMPORT_TYPES) } }, enabled = !running)
        }
        val message = if (pickerMissing) stringResource(R.string.opml_no_picker) else archiveMessage(status, resources)
        if (message != null) {
            NoticeLine(
                message,
                Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                isError = pickerMissing || status is ArchiveStatus.Failed,
            )
        }
    }
}

/** Zip files are not always typed as such by file providers, so the picker allows any file. */
private val ARCHIVE_IMPORT_TYPES = arrayOf(ArchiveFormat.MIME_TYPE, "application/octet-stream", "*/*")

/** "Less", the budget, "More": the budget moved one step at a time, as the feed slider's stepper does. */
@Composable
private fun BudgetStepper(megabytes: Int, onChange: (Int) -> Unit) {
    val colors = MorningIssueTheme.colors
    val lower = StorageBudget.lower(megabytes)
    val higher = StorageBudget.higher(megabytes)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperButton(stringResource(R.string.storage_less), { lower?.let(onChange) }, enabled = lower != null)
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(1.5.dp, colors.ink)
                .semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                StorageBudget.label(megabytes),
                style = MorningIssueTheme.typography.label.copy(fontWeight = FontWeight.Bold),
                color = colors.ink,
            )
        }
        PaperButton(stringResource(R.string.storage_more), { higher?.let(onChange) }, enabled = higher != null)
    }
}
