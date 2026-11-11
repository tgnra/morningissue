package net.tgnra.morningissue.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalTime
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.model.DisplayMode
import net.tgnra.morningissue.model.EditionLength
import net.tgnra.morningissue.model.ThemeMode
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.DialogButtons
import net.tgnra.morningissue.ui.components.HeavyRule
import net.tgnra.morningissue.ui.components.PagedColumn
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperDialog
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.SectionLabel
import net.tgnra.morningissue.ui.components.SegmentedChoice
import net.tgnra.morningissue.ui.components.stepAnchor
import net.tgnra.morningissue.ui.debug.DebugSettingsSection
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * Reset time, edition length, theme and display mode. Every change is written straight to the
 * settings store, and the app re-themes from that, so there is nothing to confirm.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val margin = LocalPaperLayoutSpec.current.pageMargin
    val stored by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var pickingEveningTime by rememberSaveable { mutableStateOf(false) }

    PaperScreenFrame(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        overlay = {
            val settings = stored
            if (pickingTime && settings != null) {
                ResetTimeDialog(
                    title = stringResource(R.string.settings_reset_time),
                    initial = settings.resetTime,
                    use24Hour = use24Hour,
                    onDismiss = { pickingTime = false },
                    onConfirm = { time ->
                        pickingTime = false
                        scope.launch { container.settings.setResetTime(time) }
                    },
                )
            }
            if (pickingEveningTime && settings != null) {
                ResetTimeDialog(
                    title = stringResource(R.string.settings_evening_time),
                    initial = settings.eveningEditionTime,
                    use24Hour = use24Hour,
                    onDismiss = { pickingEveningTime = false },
                    onConfirm = { time ->
                        pickingEveningTime = false
                        scope.launch { container.settings.setEveningEditionTime(time) }
                    },
                )
            }
        },
    ) {
        val settings = stored ?: return@PaperScreenFrame
        PagedColumn(contentPadding = PaddingValues(start = margin, end = margin, bottom = 32.dp)) {
            SettingsSection(stringResource(R.string.settings_edition)) {
                Setting(stringResource(R.string.settings_reset_time), stringResource(R.string.settings_reset_detail)) {
                    PaperButton(PaperFormat.time(settings.resetTime, use24Hour), onClick = { pickingTime = true })
                }
                Setting(stringResource(R.string.settings_length), stringResource(R.string.settings_length_detail)) {
                    SegmentedChoice(
                        options = listOf(
                            EditionLength.SHORT to stringResource(R.string.length_short),
                            EditionLength.STANDARD to stringResource(R.string.length_standard),
                            EditionLength.LONG to stringResource(R.string.length_long),
                        ),
                        selected = settings.editionLength,
                        onSelect = { scope.launch { container.settings.setEditionLength(it) } },
                    )
                }
                Setting(stringResource(R.string.settings_evening), stringResource(R.string.settings_evening_detail)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SegmentedChoice(
                            options = listOf(
                                false to stringResource(R.string.settings_evening_off),
                                true to stringResource(R.string.settings_evening_on),
                            ),
                            selected = settings.eveningEditionEnabled,
                            onSelect = { scope.launch { container.settings.setEveningEditionEnabled(it) } },
                        )
                        if (settings.eveningEditionEnabled) {
                            PaperButton(
                                stringResource(R.string.settings_evening_at, PaperFormat.time(settings.eveningEditionTime, use24Hour)),
                                onClick = { pickingEveningTime = true },
                            )
                        }
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_appearance)) {
                Setting(stringResource(R.string.settings_theme), stringResource(R.string.settings_theme_detail)) {
                    SegmentedChoice(
                        options = listOf(
                            ThemeMode.System to stringResource(R.string.theme_system),
                            ThemeMode.Light to stringResource(R.string.theme_light),
                            ThemeMode.Dark to stringResource(R.string.theme_dark),
                        ),
                        selected = settings.themeMode,
                        onSelect = { scope.launch { container.settings.setThemeMode(it) } },
                    )
                }
                Setting(stringResource(R.string.settings_display), stringResource(R.string.settings_display_detail)) {
                    SegmentedChoice(
                        options = listOf(
                            DisplayMode.Auto to stringResource(R.string.display_auto),
                            DisplayMode.Standard to stringResource(R.string.display_standard),
                            DisplayMode.Eink to stringResource(R.string.display_eink),
                        ),
                        selected = settings.displayMode,
                        onSelect = { scope.launch { container.settings.setDisplayMode(it) } },
                    )
                }
            }
            // Empty in release builds.
            DebugSettingsSection()
        }
    }
}

/** A group of settings under a small-caps heading and a heavy rule. */
@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().stepAnchor().padding(top = 28.dp)) {
        SectionLabel(title, color = MorningIssueTheme.colors.ink, heading = true)
        HeavyRule(Modifier.padding(top = 6.dp))
        content()
    }
}

/** One setting: its name, a line of explanation, then its control beneath. */
@Composable
fun Setting(title: String, detail: String?, control: @Composable () -> Unit) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = type.headlineSmall, color = colors.ink)
        if (detail != null) Text(detail, style = type.body, color = colors.inkSecondary)
        Box(Modifier.fillMaxWidth().padding(top = 8.dp)) { control() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResetTimeDialog(title: String, initial: LocalTime, use24Hour: Boolean, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val colors = MorningIssueTheme.colors
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = use24Hour)
    val pickerColors = TimePickerDefaults.colors(
        clockDialColor = colors.paper,
        selectorColor = colors.ink,
        clockDialSelectedContentColor = colors.paper,
        clockDialUnselectedContentColor = colors.ink,
        containerColor = colors.paperRaised,
        periodSelectorBorderColor = colors.ink,
        periodSelectorSelectedContainerColor = colors.ink,
        periodSelectorSelectedContentColor = colors.paper,
        periodSelectorUnselectedContainerColor = colors.paperRaised,
        periodSelectorUnselectedContentColor = colors.ink,
        timeSelectorSelectedContainerColor = colors.ink,
        timeSelectorSelectedContentColor = colors.paper,
        timeSelectorUnselectedContainerColor = colors.paper,
        timeSelectorUnselectedContentColor = colors.ink,
    )
    PaperDialog(title = title, onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            // The clock dial animates its hand; E-ink gets the typed fields instead.
            if (MorningIssueTheme.isEink) TimeInput(state, colors = pickerColors) else TimePicker(state, colors = pickerColors)
        }
        DialogButtons {
            PaperButton(stringResource(R.string.action_cancel), onDismiss)
            PaperButton(stringResource(R.string.settings_set_time), { onConfirm(LocalTime.of(state.hour, state.minute)) }, primary = true)
        }
    }
}
