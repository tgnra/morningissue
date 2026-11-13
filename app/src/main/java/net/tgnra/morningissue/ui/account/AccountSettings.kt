package net.tgnra.morningissue.ui.account

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.client.LogoutResult
import net.tgnra.morningissue.client.SyncOutcome
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.DialogButtons
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperDialog
import net.tgnra.morningissue.ui.format.PaperFormat
import net.tgnra.morningissue.ui.settings.Setting
import net.tgnra.morningissue.ui.settings.SettingsSection
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** What the account section's dialogs are showing. */
internal sealed interface AccountDialog {
    data object ChangePassword : AccountDialog

    data object ConfirmLogout : AccountDialog

    data class Unsent(val count: Int) : AccountDialog
}

/** The account section's state, shared by the section and its dialogs (drawn in the screen's overlay). */
class AccountSettingsState {
    internal var dialog by mutableStateOf<AccountDialog?>(null)
    internal var notice by mutableStateOf<Pair<String, Boolean>?>(null)
    internal var busy by mutableStateOf(false)
}

@Composable
fun rememberAccountSettingsState(): AccountSettingsState = remember { AccountSettingsState() }

/**
 * Settings' Account section: the server and the username, the last sync with Sync now, Change
 * password, and Log out (which uploads the outbox first, and asks before dropping what could
 * not be sent). Its dialogs are [AccountSettingsDialogs].
 */
@Composable
fun AccountSettingsSection(state: AccountSettingsState) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val auth by container.auth.state.collectAsStateWithLifecycle(initialValue = null)
    val syncing by container.serverSync.running.collectAsStateWithLifecycle()
    var notice by state::notice
    var dialog by state::dialog

    SettingsSection(stringResource(R.string.settings_account)) {
        Setting(stringResource(R.string.settings_account_server), auth?.serverUrl) {}
        Setting(stringResource(R.string.settings_account_user), auth?.username) {}
        val lastSync = auth?.lastSyncAt
        Setting(
            stringResource(R.string.settings_account_sync_now),
            lastSync?.let {
                stringResource(R.string.settings_account_last_sync, PaperFormat.syncTime(it, container.clock.instant(), container.clock.zone, use24Hour))
            } ?: stringResource(R.string.settings_account_never),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperButton(
                    stringResource(if (syncing) R.string.settings_account_syncing else R.string.settings_account_sync_now),
                    {
                        notice = null
                        scope.launch {
                            notice = when (container.serverSync.sync("settings")) {
                                SyncOutcome.Offline -> resources.getString(R.string.settings_account_sync_offline) to true
                                is SyncOutcome.Failed -> resources.getString(R.string.settings_account_sync_failed) to true
                                else -> null
                            }
                        }
                    },
                    enabled = !syncing,
                )
                notice?.let { (text, isError) -> AccountNotice(text, isError) }
            }
        }
        Setting(stringResource(R.string.settings_account_password), null) {
            PaperButton(stringResource(R.string.account_password_change), { dialog = AccountDialog.ChangePassword })
        }
        Setting(stringResource(R.string.account_log_out), stringResource(R.string.settings_account_log_out_detail)) {
            PaperButton(stringResource(R.string.account_log_out), { dialog = AccountDialog.ConfirmLogout }, enabled = !state.busy)
        }
    }
}

@Composable
fun AccountSettingsDialogs(state: AccountSettingsState) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val colors = MorningIssueTheme.colors
    val type = MorningIssueTheme.typography
    var dialog by state::dialog
    var notice by state::notice
    val busy = state.busy

    fun logout(force: Boolean) {
        if (state.busy) return
        state.busy = true
        scope.launch {
            try {
                when (val result = container.account.logout(force)) {
                    LogoutResult.LoggedOut -> dialog = null
                    is LogoutResult.UnsentEvents -> dialog = AccountDialog.Unsent(result.count)
                }
            } finally {
                state.busy = false
            }
        }
    }

    when (val shown = dialog) {
        AccountDialog.ChangePassword -> PaperDialog(title = stringResource(R.string.account_password_change), onDismiss = { dialog = null }) {
            Text(stringResource(R.string.account_password_detail, net.tgnra.morningissue.api.ApiLimits.MIN_PASSWORD_LENGTH), style = type.body, color = colors.inkSecondary)
            ChangePasswordForm(onChanged = {
                dialog = null
                notice = resources.getString(R.string.account_password_changed) to false
            })
            DialogButtons { PaperButton(stringResource(R.string.account_cancel), { dialog = null }) }
        }
        AccountDialog.ConfirmLogout -> PaperDialog(title = stringResource(R.string.account_log_out), onDismiss = { dialog = null }) {
            Text(stringResource(R.string.settings_account_log_out_detail), style = type.body, color = colors.ink)
            DialogButtons {
                PaperButton(stringResource(R.string.account_cancel), { dialog = null })
                PaperButton(stringResource(R.string.account_log_out), { logout(force = false) }, primary = true, enabled = !busy)
            }
        }
        is AccountDialog.Unsent -> PaperDialog(title = stringResource(R.string.account_log_out), onDismiss = { dialog = null }) {
            Text(pluralStringResource(R.plurals.settings_account_unsent, shown.count, shown.count), style = type.body, color = colors.ink, modifier = Modifier.padding(bottom = 4.dp))
            DialogButtons {
                PaperButton(stringResource(R.string.account_cancel), { dialog = null })
                PaperButton(stringResource(R.string.settings_account_log_out_anyway), { logout(force = true) }, primary = true, enabled = !busy)
            }
        }
        null -> Unit
    }
}
