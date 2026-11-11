package net.tgnra.morningissue.ui.settings

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.data.db.SyncPeerEntity
import net.tgnra.morningissue.kobo.KoboSyncStatus
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.DialogButtons
import net.tgnra.morningissue.ui.components.HairlineRule
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.PagedColumn
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperDialog
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.stepAnchor
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/**
 * Sync with Kobo, from Settings: where a Kobo reaches the phone, what is happening, the
 * pairing request waiting for an answer, and the paired devices. The server and the beacon
 * run only while this screen is started (`KoboSync.run`), so the phone listens on the network
 * only while the reader is looking at this screen.
 */
@Composable
fun KoboSyncScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val sync = container.koboSync
    val scope = rememberCoroutineScope()
    val margin = LocalPaperLayoutSpec.current.pageMargin
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val resources = LocalResources.current
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(sync, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { sync.run() }
    }
    val status by sync.status.collectAsStateWithLifecycle()
    val pending by sync.pairing.pending.collectAsStateWithLifecycle()
    val peers by sync.peers.collectAsStateWithLifecycle(initialValue = emptyList())
    val now = container.clock.instant()
    val zone = container.clock.zone

    PaperScreenFrame(
        title = stringResource(R.string.kobo_title),
        onBack = onBack,
        overlay = {
            pending?.let { request ->
                PaperDialog(title = stringResource(R.string.kobo_pair_title, request.name), onDismiss = { sync.pairing.answer(false) }) {
                    Text(stringResource(R.string.kobo_pair_detail), style = type.body, color = colors.ink)
                    DialogButtons {
                        PaperButton(stringResource(R.string.kobo_deny), { sync.pairing.answer(false) })
                        PaperButton(stringResource(R.string.kobo_allow), { sync.pairing.answer(true) }, primary = true)
                    }
                }
            }
        },
    ) {
        PagedColumn(contentPadding = PaddingValues(start = margin, end = margin, bottom = 32.dp)) {
            SettingsSection(stringResource(R.string.kobo_phone)) {
                Setting(stringResource(R.string.kobo_address), stringResource(R.string.kobo_address_detail)) {
                    Text(
                        status.address?.let { stringResource(R.string.kobo_address_value, it.host, status.port) }
                            ?: stringResource(R.string.kobo_no_wifi),
                        style = type.headline,
                        color = colors.ink,
                    )
                }
                NoticeLine(
                    koboStatusLine(status, resources, now, zone, use24Hour),
                    Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    isError = status.state == KoboSyncStatus.State.PORT_IN_USE,
                )
            }
            SettingsSection(stringResource(R.string.kobo_paired)) {
                if (peers.isEmpty()) {
                    Text(stringResource(R.string.kobo_none_paired), style = type.body, color = colors.inkSecondary, modifier = Modifier.padding(top = 18.dp))
                }
                peers.forEachIndexed { index, peer ->
                    if (index > 0) HairlineRule()
                    PeerRow(peer, now, zone, use24Hour, onForget = { scope.launch { sync.forget(peer.deviceId) } })
                }
            }
        }
    }
}

@Composable
private fun PeerRow(peer: SyncPeerEntity, now: Instant, zone: ZoneId, use24Hour: Boolean, onForget: () -> Unit) {
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    val forget = stringResource(R.string.kobo_forget_description, peer.name)
    Row(
        Modifier.fillMaxWidth().stepAnchor().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(peer.name, style = type.headlineSmall, color = colors.ink)
            Text(
                peer.lastSyncAt?.let { stringResource(R.string.kobo_last_synced, PaperFormat.syncTime(it, now, zone, use24Hour)) }
                    ?: stringResource(R.string.kobo_never_synced),
                style = type.label,
                color = colors.inkSecondary,
            )
        }
        PaperButton(stringResource(R.string.kobo_forget), onForget, Modifier.semantics { contentDescription = forget })
    }
}

/** The status line: a port another app holds, no Wi-Fi, the last sync, or waiting. */
internal fun koboStatusLine(status: KoboSyncStatus, resources: Resources, now: Instant, zone: ZoneId, use24Hour: Boolean): String {
    val last = status.last
    return when {
        status.state == KoboSyncStatus.State.PORT_IN_USE -> resources.getString(R.string.kobo_status_port_in_use, status.port)
        status.state != KoboSyncStatus.State.LISTENING -> resources.getString(R.string.kobo_status_starting)
        status.address == null -> resources.getString(R.string.kobo_status_no_wifi)
        last != null -> {
            val at = PaperFormat.syncTime(last.at, now, zone, use24Hour)
            val changes = last.eventsMerged ?: 0
            if (changes > 0) {
                resources.getQuantityString(R.plurals.kobo_status_synced_changes, changes, last.deviceName, at, changes)
            } else {
                resources.getString(R.string.kobo_status_synced, last.deviceName, at)
            }
        }
        else -> resources.getString(R.string.kobo_status_waiting)
    }
}
