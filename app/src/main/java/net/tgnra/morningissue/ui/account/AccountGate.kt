package net.tgnra.morningissue.ui.account

import android.content.ActivityNotFoundException
import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import net.tgnra.morningissue.R
import net.tgnra.morningissue.client.MigrationState
import net.tgnra.morningissue.data.archive.ArchiveFormat
import net.tgnra.morningissue.data.archive.ArchiveStatus
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.TextLink
import net.tgnra.morningissue.ui.components.ThinProgressBar

/**
 * Shows [content] (the app) only once there is an account, its password is the reader's own,
 * the library of an earlier version has been uploaded (or set aside), and the cache has been
 * filled once (`docs/server-design.md`, 5.3). Before that, the step that is due.
 */
@Composable
fun AccountGate(content: @Composable () -> Unit) {
    val container = LocalAppContainer.current
    val stored by container.auth.state.collectAsStateWithLifecycle(initialValue = null)
    val auth = stored ?: return
    // The first sync after logging in, so that the stand (and the onboarding decision) reads
    // the account's library. Set once it has run, whatever it came to: offline is fine.
    var firstSyncDone by rememberSaveable { mutableStateOf(false) }
    when {
        !auth.isLoggedIn -> SignInScreen(auth.serverUrl)
        auth.mustChangePassword -> ChangePasswordScreen()
        !auth.migrationSettled -> MigrationScreen()
        auth.lastSyncAt == null && !firstSyncDone -> {
            LaunchedEffect(Unit) {
                container.serverSync.sync("first")
                firstSyncDone = true
            }
            AccountFrame(title = stringResource(R.string.migration_title)) {
                AccountText(stringResource(R.string.settings_account_syncing))
            }
        }
        else -> {
            LaunchedEffect(Unit) {
                container.syncScheduler.ensurePeriodicSync()
                container.syncScheduler.syncIfStale(auth.lastSyncAt, container.clock.instant())
            }
            content()
        }
    }
}

/**
 * The first start after updating from the version that kept everything on the phone: the
 * library goes to the account. An empty account takes it at once; one with a library of its
 * own asks first. "Save a copy" writes the same archive to a file through the system picker.
 */
@Composable
fun MigrationScreen() {
    val container = LocalAppContainer.current
    val resources = LocalResources.current
    val migration = container.migration
    val state by migration.state.collectAsStateWithLifecycle()
    val archive by container.archive.status.collectAsStateWithLifecycle()
    var pickerMissing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // With nothing to upload this settles the migration, and the gate moves on.
        if (migration.needed()) migration.start()
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ArchiveFormat.MIME_TYPE)) { uri ->
        if (uri != null) container.archive.export(uri, freeSpace = false)
    }
    val saveCopy: () -> Unit = {
        pickerMissing = false
        try {
            saveLauncher.launch("morning-issue-${LocalDate.now()}.${ArchiveFormat.FILE_EXTENSION}")
        } catch (_: ActivityNotFoundException) {
            pickerMissing = true
        }
    }
    val saving = archive is ArchiveStatus.Running

    AccountFrame(title = stringResource(R.string.migration_title)) {
        AccountText(stringResource(R.string.migration_detail))
        when (val current = state) {
            MigrationState.Checking -> AccountText(stringResource(R.string.migration_checking), secondary = true)
            MigrationState.Choice -> {
                AccountText(stringResource(R.string.migration_choice))
                PaperButton(stringResource(R.string.migration_merge), migration::merge, primary = true, enabled = !saving)
                PaperButton(stringResource(R.string.migration_skip), migration::skip, enabled = !saving)
                AccountText(stringResource(R.string.migration_skip_detail), secondary = true)
                TextLink(stringResource(R.string.migration_save_copy), { if (!saving) saveCopy() })
            }
            is MigrationState.Failed -> {
                AccountNotice(
                    if (current.offline) {
                        stringResource(R.string.migration_failed_offline)
                    } else {
                        stringResource(R.string.migration_failed, current.message.orEmpty())
                    },
                )
                PaperButton(stringResource(R.string.migration_retry), migration::merge, primary = true, enabled = !saving)
                TextLink(stringResource(R.string.migration_save_copy), { if (!saving) saveCopy() })
            }
            is MigrationState.Done -> AccountText(stringResource(R.string.settings_account_syncing), secondary = true)
            else -> {
                AccountText(migrationProgressLine(current, resources).orEmpty())
                ThinProgressBar(migrationProgress(current), height = 4.dp)
                AccountText(stringResource(R.string.migration_wait), secondary = true)
            }
        }
        AccountText(stringResource(R.string.migration_kobos), secondary = true)
        when {
            pickerMissing -> AccountNotice(stringResource(R.string.opml_no_picker))
            archive is ArchiveStatus.Exported -> AccountNotice(stringResource(R.string.migration_saved_copy), isError = false)
            archive is ArchiveStatus.Failed -> AccountNotice(stringResource(R.string.migration_save_failed))
            archive is ArchiveStatus.Running -> AccountText(stringResource(R.string.archive_exporting), secondary = true)
        }
    }
}

internal fun migrationProgressLine(state: MigrationState, resources: Resources): String? = when (state) {
    is MigrationState.Exporting ->
        if (state.total > 0) resources.getString(R.string.migration_exporting, state.done, state.total)
        else resources.getString(R.string.migration_exporting_start)
    MigrationState.Uploading -> resources.getString(R.string.migration_uploading)
    is MigrationState.Importing ->
        if (state.total > 0) resources.getString(R.string.migration_importing, state.done, state.total)
        else resources.getString(R.string.migration_importing_start)
    else -> null
}

/** One bar for the whole upload: packing is the first third, the server's import the rest. */
internal fun migrationProgress(state: MigrationState): Float = when (state) {
    is MigrationState.Exporting -> if (state.total > 0) 0.3f * state.done / state.total else 0f
    MigrationState.Uploading -> 0.35f
    is MigrationState.Importing -> 0.4f + if (state.total > 0) 0.6f * state.done / state.total else 0f
    is MigrationState.Done -> 1f
    else -> 0f
}
