package net.tgnra.morningissue.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import android.text.format.DateFormat
import android.text.format.Formatter
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
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
import net.tgnra.morningissue.R
import net.tgnra.morningissue.data.db.SyncPeerEntity
import net.tgnra.morningissue.relay.PairingParse
import net.tgnra.morningissue.relay.PairingProblem
import net.tgnra.morningissue.relay.RelayAddress
import net.tgnra.morningissue.relay.RelayDeviceStatus
import net.tgnra.morningissue.relay.RelayGroupStatus
import net.tgnra.morningissue.relay.RelayPairingUri
import net.tgnra.morningissue.relay.RelayProblem
import net.tgnra.morningissue.relay.RelayProgress
import net.tgnra.morningissue.relay.RelayResult
import net.tgnra.morningissue.relay.RelayTask
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
 * Sync with Kobo, from Settings: the relay's address for typing on the Kobo (built into the app,
 * not changed here), the paired Kobos (scanned from their codes, or pasted), what the relay
 * says about each, and Publish and Done. The work runs on the app's scope, so it carries on if
 * the screen is left.
 */
@Composable
fun KoboSyncScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val relay = container.relaySync
    val context = LocalContext.current
    val resources = LocalResources.current
    val margin = LocalPaperLayoutSpec.current.pageMargin
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val use24Hour = DateFormat.is24HourFormat(context)
    val now = container.clock.instant()
    val zone = container.clock.zone

    val config by relay.config.collectAsStateWithLifecycle(initialValue = null)
    val peers by relay.peers.collectAsStateWithLifecycle(initialValue = emptyList())
    val activity by relay.activity.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<RelayGroupStatus?>(null) }
    var lostKeys by remember { mutableStateOf(emptySet<String>()) }

    // What the relay says about each Kobo, asked again whenever a task ends or the devices change.
    LaunchedEffect(config?.address, peers, activity.running) {
        if (activity.running != null) return@LaunchedEffect
        lostKeys = relay.devicesWithLostKeys()
        status = if (config?.isConfigured == true && peers.isNotEmpty()) relay.status() else null
    }

    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanNote by remember { mutableStateOf<String?>(null) }
    var cameraNote by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else cameraNote = resources.getString(R.string.relay_camera_denied)
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
                PaperDialog(title = stringResource(R.string.relay_scan), onDismiss = { scanning = false; scanNote = null }) {
                    Text(stringResource(R.string.relay_scan_detail), style = type.body, color = colors.ink)
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).border(1.dp, colors.ink)) {
                        RelayCodeScanner(
                            onCode = { text ->
                                if (RelayPairingUri.parse(text) is PairingParse.Ok) {
                                    scanning = false
                                    scanNote = null
                                    relay.startPair(text)
                                } else {
                                    scanNote = resources.getString(R.string.relay_scan_not_code)
                                }
                            },
                            onUnavailable = {
                                scanning = false
                                cameraNote = resources.getString(R.string.relay_camera_unavailable)
                            },
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        )
                    }
                    scanNote?.let { Text(it, style = type.label, color = colors.accent) }
                    DialogButtons {
                        PaperButton(stringResource(R.string.relay_scan_cancel), { scanning = false; scanNote = null })
                    }
                }
            }
        },
    ) {
        PagedColumn(contentPadding = PaddingValues(start = margin, end = margin, bottom = 32.dp)) {
            SettingsSection(stringResource(R.string.relay_server)) {
                Setting(stringResource(R.string.relay_for_kobo), stringResource(R.string.relay_for_kobo_detail)) {
                    val address = config?.address.orEmpty()
                    Text(
                        when (val parsed = relay.parseAddress(address)) {
                            is RelayAddress.Parse.Ok -> parsed.url
                            RelayAddress.Parse.Empty -> stringResource(R.string.relay_problem_no_address)
                            else -> address
                        },
                        style = type.headline,
                        color = colors.ink,
                    )
                }
            }

            SettingsSection(stringResource(R.string.relay_devices)) {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaperButton(stringResource(R.string.relay_scan), ::scan, primary = true, enabled = activity.running == null)
                }
                cameraNote?.let { Text(it, style = type.label, color = colors.inkSecondary, modifier = Modifier.padding(top = 8.dp)) }
                PasteCode(enabled = activity.running == null, onPair = relay::startPair)
                if (peers.isEmpty()) {
                    Text(stringResource(R.string.relay_none), style = type.body, color = colors.inkSecondary, modifier = Modifier.padding(top = 18.dp))
                } else if (status?.problem != null) {
                    Text(stringResource(R.string.relay_status_unavailable), style = type.label, color = colors.inkSecondary, modifier = Modifier.padding(top = 18.dp))
                }
                peers.forEachIndexed { index, peer ->
                    if (index > 0) HairlineRule()
                    PeerRow(
                        peer = peer,
                        status = status?.devices?.get(peer.deviceId),
                        hasPackage = status?.hasPackage == true,
                        keysLost = peer.deviceId in lostKeys,
                        now = now,
                        zone = zone,
                        use24Hour = use24Hour,
                        onForget = { relay.startForget(peer.deviceId) },
                        enabled = activity.running == null,
                    )
                }
            }

            SettingsSection(stringResource(R.string.relay_sync)) {
                val idle = activity.running == null && config?.isConfigured == true
                Setting(stringResource(R.string.relay_publish), stringResource(R.string.relay_publish_detail)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PaperButton(stringResource(R.string.relay_publish), relay::startPublish, primary = true, enabled = idle && peers.isNotEmpty())
                        Text(
                            config?.lastPublishedAt?.let { stringResource(R.string.relay_last_published, PaperFormat.syncTime(it, now, zone, use24Hour)) }
                                ?: stringResource(R.string.relay_never_published),
                            style = type.label,
                            color = colors.inkSecondary,
                        )
                    }
                }
                Setting(stringResource(R.string.relay_done), stringResource(R.string.relay_done_detail)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PaperButton(stringResource(R.string.relay_done), relay::startDone, enabled = idle && peers.isNotEmpty())
                        Text(
                            config?.lastDoneAt?.let { stringResource(R.string.relay_last_done, PaperFormat.syncTime(it, now, zone, use24Hour)) }
                                ?: stringResource(R.string.relay_never_done),
                            style = type.label,
                            color = colors.inkSecondary,
                        )
                    }
                }
                val line = activity.progress?.let { progressLine(it, activity.running, resources, context) }
                    ?: activity.running?.let { resources.getString(if (it == RelayTask.PAIR) R.string.relay_progress_pairing else R.string.relay_progress_connecting) }
                    ?: activity.last?.let { resultLine(it, resources, context) }
                line?.let {
                    NoticeLine(
                        it,
                        Modifier.padding(top = 18.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        isError = activity.running == null && (activity.last is RelayResult.Failed || activity.last is RelayResult.PairingRefused),
                    )
                }
            }
        }
    }
}

@Composable
private fun PasteCode(enabled: Boolean, onPair: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            PaperTextField(text, { text = it }, stringResource(R.string.relay_paste_hint), isUrl = true, onImeAction = {
                if (text.isNotBlank()) {
                    onPair(text)
                    text = ""
                }
            })
        }
        PaperButton(stringResource(R.string.relay_pair), {
            onPair(text)
            text = ""
        }, enabled = enabled && text.isNotBlank())
    }
}

@Composable
private fun PeerRow(
    peer: SyncPeerEntity,
    status: RelayDeviceStatus?,
    hasPackage: Boolean,
    keysLost: Boolean,
    now: Instant,
    zone: ZoneId,
    use24Hour: Boolean,
    onForget: () -> Unit,
    enabled: Boolean,
) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val forget = stringResource(R.string.relay_forget_description, peer.name)
    Row(
        Modifier.fillMaxWidth().stepAnchor().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(peer.name, style = type.headlineSmall, color = colors.ink)
            if (keysLost) {
                Text(stringResource(R.string.relay_keys_lost), style = type.label, color = colors.accent)
            } else if (status != null) {
                Text(
                    status.lastSeenAt?.let { stringResource(R.string.relay_last_seen, PaperFormat.syncTime(it, now, zone, use24Hour)) }
                        ?: stringResource(R.string.relay_never_seen),
                    style = type.label,
                    color = colors.inkSecondary,
                )
                if (hasPackage) {
                    Text(
                        stringResource(if (status.hasCurrentPackage) R.string.relay_has_package else R.string.relay_no_package),
                        style = type.label,
                        color = colors.inkSecondary,
                    )
                }
                if (status.pendingChanges > 0) {
                    Text(pluralStringResource(R.plurals.relay_pending, status.pendingChanges, status.pendingChanges), style = type.label, color = colors.inkSecondary)
                }
            }
        }
        PaperButton(stringResource(R.string.relay_forget), onForget, Modifier.semantics { contentDescription = forget }, enabled = enabled)
    }
}

private fun progressLine(progress: RelayProgress, task: RelayTask?, resources: Resources, context: android.content.Context): String = when (progress) {
    RelayProgress.Connecting -> resources.getString(if (task == RelayTask.PAIR) R.string.relay_progress_pairing else R.string.relay_progress_connecting)
    is RelayProgress.PreparingImages -> resources.getString(R.string.relay_progress_images, progress.done, progress.total)
    is RelayProgress.Uploading -> resources.getString(R.string.relay_progress_uploading, Formatter.formatShortFileSize(context, progress.bytes))
    RelayProgress.Publishing -> resources.getString(R.string.relay_progress_publishing)
    is RelayProgress.Collecting -> resources.getString(R.string.relay_progress_collecting, progress.done, progress.total)
}

/** The last task's result in plain words. */
internal fun resultLine(result: RelayResult, resources: Resources, context: android.content.Context): String = when (result) {
    is RelayResult.Published -> resources.getQuantityString(
        R.plurals.relay_result_published, result.devices,
        result.devices, Formatter.formatShortFileSize(context, result.bytes), result.editions, result.saved, result.images,
    )
    is RelayResult.NoDevices -> resources.getString(R.string.relay_result_no_devices)
    is RelayResult.Collected -> buildList {
        add(if (result.events > 0) resources.getQuantityString(R.plurals.relay_result_collected, result.events, result.events) else resources.getString(R.string.relay_result_nothing))
        if (result.dropped > 0) add(resources.getQuantityString(R.plurals.relay_result_dropped, result.dropped, result.dropped))
        if (result.left > 0) add(resources.getQuantityString(R.plurals.relay_result_left, result.left, result.left))
    }.joinToString(" ")
    is RelayResult.Paired -> when {
        result.toldRelay -> resources.getString(R.string.relay_result_paired, result.name)
        result.problem == null -> resources.getString(R.string.relay_result_paired_no_address, result.name)
        else -> resources.getString(R.string.relay_result_paired_not_told, result.name, problemLine(result.problem, null, resources))
    }
    is RelayResult.PairingRefused -> resources.getString(
        when (result.problem) {
            PairingProblem.NOT_A_CODE -> R.string.relay_code_not_a_code
            PairingProblem.UNKNOWN_VERSION -> R.string.relay_code_unknown_version
            PairingProblem.BAD_FIELDS -> R.string.relay_code_bad
        },
    )
    is RelayResult.Forgotten -> result.problem?.let { resources.getString(R.string.relay_result_forgotten_not_told, problemLine(it, null, resources)) }
        ?: resources.getString(R.string.relay_result_forgotten)
    is RelayResult.Failed -> resources.getString(
        if (result.task == RelayTask.DONE) R.string.relay_failed_done else R.string.relay_failed_publish,
        problemLine(result.problem, result.status, resources),
    )
}

internal fun problemLine(problem: RelayProblem, status: Int?, resources: Resources): String = when (problem) {
    RelayProblem.NO_ADDRESS -> resources.getString(R.string.relay_problem_no_address)
    RelayProblem.NOT_HTTPS -> resources.getString(R.string.relay_problem_not_https)
    RelayProblem.BAD_ADDRESS -> resources.getString(R.string.relay_problem_bad_address)
    RelayProblem.NETWORK -> resources.getString(R.string.relay_problem_network)
    RelayProblem.REGISTRATION_REFUSED -> resources.getString(R.string.relay_problem_registration)
    RelayProblem.UNAUTHORIZED -> resources.getString(R.string.relay_problem_unauthorized)
    RelayProblem.TOO_LARGE -> resources.getString(R.string.relay_problem_too_large)
    RelayProblem.TOO_MANY -> resources.getString(R.string.relay_problem_too_many)
    RelayProblem.RELAY_ERROR -> resources.getString(R.string.relay_problem_relay_error, status?.toString() ?: "?")
    RelayProblem.PHONE_ERROR -> resources.getString(R.string.relay_problem_phone_error)
}
