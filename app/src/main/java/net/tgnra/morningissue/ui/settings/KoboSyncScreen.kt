package net.tgnra.morningissue.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.api.DeviceDto
import net.tgnra.morningissue.api.DeviceKind
import net.tgnra.morningissue.client.KoboDevices
import net.tgnra.morningissue.client.PairResult
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.DialogButtons
import net.tgnra.morningissue.ui.components.HairlineRule
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.PagedColumn
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperDialog
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.PaperTextField
import net.tgnra.morningissue.ui.components.stepAnchor
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * Kobo e-readers, from Settings: the server's address for typing on the Kobo, pairing (scan
 * the Kobo's code, or paste it, then `POST /devices/kobo`), and the account's devices from
 * `GET /devices` with Forget. A paired Kobo syncs with the server by itself; there is no
 * session here any more.
 */
@Composable
fun KoboSyncScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val margin = LocalPaperLayoutSpec.current.pageMargin
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val use24Hour = DateFormat.is24HourFormat(context)
    val now = container.clock.instant()
    val zone = container.clock.zone
    val auth by container.auth.state.collectAsStateWithLifecycle(initialValue = null)

    // Null until listed; empty when the account has none. Bumped to list again.
    var devices by remember { mutableStateOf<List<DeviceDto>?>(null) }
    var listFailed by remember { mutableStateOf(false) }
    var listed by remember { mutableIntStateOf(0) }
    LaunchedEffect(listed) {
        val answer = container.koboDevices.list()
        listFailed = answer == null
        if (answer != null) devices = answer
    }

    var pairing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    fun pair(code: String) {
        if (pairing) return
        pairing = true
        notice = null
        scope.launch {
            try {
                val result = container.koboDevices.pair(code)
                notice = pairLine(result, resources) to (result !is PairResult.Paired)
                if (result is PairResult.Paired) listed++
            } finally {
                pairing = false
            }
        }
    }
    fun forget(device: DeviceDto) {
        scope.launch {
            if (container.koboDevices.forget(device.id)) {
                listed++
            } else {
                notice = resources.getString(R.string.kobo_forget_failed, device.name) to true
            }
        }
    }

    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanNote by remember { mutableStateOf<String?>(null) }
    var cameraNote by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else cameraNote = resources.getString(R.string.kobo_camera_denied)
    }
    fun scan() {
        cameraNote = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanning = true
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    PaperScreenFrame(
        title = stringResource(R.string.kobo_title),
        onBack = onBack,
        overlay = {
            if (scanning) {
                PaperDialog(title = stringResource(R.string.kobo_scan), onDismiss = { scanning = false; scanNote = null }) {
                    Text(stringResource(R.string.kobo_scan_detail), style = type.body, color = colors.ink)
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, colors.ink)) {
                        KoboCodeScanner(
                            onCode = { text ->
                                if (KoboDevices.isPairingCode(text)) {
                                    scanning = false
                                    scanNote = null
                                    pair(text)
                                } else {
                                    scanNote = resources.getString(R.string.kobo_scan_not_code)
                                }
                            },
                            onUnavailable = {
                                scanning = false
                                cameraNote = resources.getString(R.string.kobo_camera_unavailable)
                            },
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        )
                    }
                    scanNote?.let { Text(it, style = type.label, color = colors.accent) }
                    DialogButtons {
                        PaperButton(stringResource(R.string.kobo_scan_cancel), { scanning = false; scanNote = null })
                    }
                }
            }
        },
    ) {
        PagedColumn(contentPadding = PaddingValues(start = margin, end = margin, bottom = 32.dp)) {
            SettingsSection(stringResource(R.string.kobo_server)) {
                Setting(stringResource(R.string.settings_account_server), stringResource(R.string.kobo_server_detail)) {
                    Text(auth?.serverUrl.orEmpty(), style = type.headline, color = colors.ink)
                }
            }

            SettingsSection(stringResource(R.string.kobo_devices)) {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaperButton(stringResource(R.string.kobo_scan), ::scan, primary = true, enabled = !pairing)
                }
                cameraNote?.let { Text(it, style = type.label, color = colors.inkSecondary, modifier = Modifier.padding(top = 8.dp)) }
                PasteCode(enabled = !pairing, onPair = ::pair)
                if (pairing) {
                    Text(stringResource(R.string.kobo_pairing), style = type.label, color = colors.inkSecondary, modifier = Modifier.padding(top = 12.dp))
                }
                notice?.let { (text, isError) ->
                    NoticeLine(text, Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }, isError = isError)
                }
                val shown = devices
                when {
                    shown == null && listFailed -> Text(
                        stringResource(R.string.kobo_list_unreachable),
                        style = type.body,
                        color = colors.inkSecondary,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    shown != null && shown.none { it.kind == DeviceKind.KOBO } -> Text(
                        stringResource(R.string.kobo_none),
                        style = type.body,
                        color = colors.inkSecondary,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                }
                shown.orEmpty().forEachIndexed { index, device ->
                    if (index > 0) HairlineRule()
                    DeviceRow(device, now, zone, use24Hour, onForget = { forget(device) })
                }
            }
        }
    }
}

/** What a pairing came to, in plain words. */
internal fun pairLine(result: PairResult, resources: Resources): String = when (result) {
    is PairResult.Paired -> resources.getString(R.string.kobo_paired, result.device.name)
    PairResult.NotACode -> resources.getString(R.string.kobo_not_a_code)
    PairResult.Refused -> resources.getString(R.string.kobo_refused)
    PairResult.Unreachable -> resources.getString(R.string.kobo_unreachable)
}

@Composable
private fun PasteCode(enabled: Boolean, onPair: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            PaperTextField(text, { text = it }, stringResource(R.string.kobo_paste_hint), isUrl = true, onImeAction = {
                if (enabled && text.isNotBlank()) {
                    onPair(text)
                    text = ""
                }
            })
        }
        PaperButton(stringResource(R.string.kobo_pair), {
            onPair(text)
            text = ""
        }, enabled = enabled && text.isNotBlank())
    }
}

@Composable
private fun DeviceRow(device: DeviceDto, now: Instant, zone: ZoneId, use24Hour: Boolean, onForget: () -> Unit) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val forget = stringResource(R.string.kobo_forget_description, device.name)
    Row(
        Modifier.fillMaxWidth().stepAnchor().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.name, style = type.headlineSmall, color = colors.ink)
            val detail = when {
                device.current -> stringResource(R.string.kobo_this_phone)
                else -> device.lastSeenAt?.let { stringResource(R.string.kobo_last_seen, PaperFormat.syncTime(it, now, zone, use24Hour)) }
                    ?: stringResource(R.string.kobo_never_seen)
            }
            Text(
                if (device.kind == DeviceKind.PHONE && !device.current) "${stringResource(R.string.kobo_phone)} · $detail" else detail,
                style = type.label,
                color = colors.inkSecondary,
            )
        }
        // This phone is forgotten by logging out, in Settings.
        if (!device.current) {
            PaperButton(stringResource(R.string.kobo_forget), onForget, Modifier.semantics { contentDescription = forget })
        }
    }
}
