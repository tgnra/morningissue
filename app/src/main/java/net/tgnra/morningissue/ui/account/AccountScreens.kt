package net.tgnra.morningissue.ui.account

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.tgnra.morningissue.R
import net.tgnra.morningissue.api.ApiLimits
import net.tgnra.morningissue.client.LoginResult
import net.tgnra.morningissue.client.PasswordChangeResult
import net.tgnra.morningissue.client.ServerAddress
import net.tgnra.morningissue.client.ServerCheck
import net.tgnra.morningissue.ui.LocalAppContainer
import net.tgnra.morningissue.ui.components.DialogButtons
import net.tgnra.morningissue.ui.components.NoticeLine
import net.tgnra.morningissue.ui.components.PagedColumn
import net.tgnra.morningissue.ui.components.PaperButton
import net.tgnra.morningissue.ui.components.PaperDialog
import net.tgnra.morningissue.ui.components.PaperScreenFrame
import net.tgnra.morningissue.ui.components.PaperTextField
import net.tgnra.morningissue.ui.components.TextLink
import net.tgnra.morningissue.ui.layout.LocalPaperLayoutSpec
import net.tgnra.morningissue.ui.theme.MorningIssueTheme

/** A screen of the account flow: a title, a column of words and fields at a readable width. */
@Composable
internal fun AccountFrame(title: String, overlay: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val margin = LocalPaperLayoutSpec.current.pageMargin
    PaperScreenFrame(title = title, onBack = null, overlay = { overlay() }) {
        PagedColumn(contentPadding = PaddingValues(start = margin, end = margin, top = 24.dp, bottom = 32.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable
internal fun AccountText(text: String, secondary: Boolean = false) {
    val colors = MorningIssueTheme.colors
    Text(text, style = MorningIssueTheme.typography.body, color = if (secondary) colors.inkSecondary else colors.ink)
}

@Composable
internal fun AccountNotice(text: String?, isError: Boolean = true) {
    if (text != null) NoticeLine(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, isError = isError)
}

/**
 * Before there is an account: the server's address (checked with `GET /health`; a bare host
 * gets `https://`, and `http://` only after the reader confirms it is unencrypted), then the
 * username and password.
 */
@Composable
fun SignInScreen(storedServer: String?) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var server by rememberSaveable { mutableStateOf(storedServer) }
    var input by rememberSaveable { mutableStateOf(storedServer.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var unencrypted by remember { mutableStateOf<ServerAddress?>(null) }

    fun useServer(address: ServerAddress) {
        scope.launch {
            container.auth.setServer(address.url)
            server = address.url
            error = null
        }
    }

    val current = server
    if (current == null) {
        fun check() {
            if (busy) return
            busy = true
            error = null
            scope.launch {
                try {
                    when (val result = container.account.check(input)) {
                        is ServerCheck.Reachable -> if (result.address.encrypted) useServer(result.address) else unencrypted = result.address
                        else -> error = serverCheckLine(result, resources)
                    }
                } finally {
                    busy = false
                }
            }
        }
        AccountFrame(
            title = stringResource(R.string.account_server_title),
            overlay = {
                unencrypted?.let { address ->
                    PaperDialog(title = stringResource(R.string.account_insecure_title), onDismiss = { unencrypted = null }) {
                        AccountText(stringResource(R.string.account_insecure))
                        DialogButtons {
                            PaperButton(stringResource(R.string.account_cancel), { unencrypted = null })
                            PaperButton(stringResource(R.string.account_insecure_confirm), {
                                unencrypted = null
                                useServer(address)
                            }, primary = true)
                        }
                    }
                }
            },
        ) {
            AccountText(stringResource(R.string.account_server_detail))
            PaperTextField(input, { input = it; error = null }, stringResource(R.string.account_server_hint), isUrl = true, onImeAction = ::check, enabled = !busy)
            AccountNotice(error)
            PaperButton(
                stringResource(if (busy) R.string.account_checking else R.string.account_continue),
                ::check,
                primary = true,
                enabled = !busy && input.isNotBlank(),
            )
        }
    } else {
        var username by rememberSaveable { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        fun login() {
            if (busy || username.isBlank() || password.isEmpty()) return
            busy = true
            error = null
            scope.launch {
                try {
                    val result = container.account.login(current, username, password)
                    if (result !is LoginResult.LoggedIn) error = loginLine(result, resources)
                    // Logged in: the gate moves on by itself.
                } finally {
                    busy = false
                }
            }
        }
        AccountFrame(title = stringResource(R.string.account_login_title)) {
            AccountText(stringResource(R.string.account_login_server, current), secondary = true)
            PaperTextField(username, { username = it; error = null }, stringResource(R.string.account_username), imeAction = ImeAction.Next, enabled = !busy)
            PaperTextField(password, { password = it; error = null }, stringResource(R.string.account_password), isPassword = true, onImeAction = ::login, enabled = !busy)
            AccountNotice(error)
            PaperButton(
                stringResource(if (busy) R.string.account_logging_in else R.string.account_login),
                ::login,
                primary = true,
                enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
            )
            TextLink(stringResource(R.string.account_change_server), { if (!busy) server = null })
        }
    }
}

internal fun serverCheckLine(result: ServerCheck, resources: Resources): String? = when (result) {
    is ServerCheck.Reachable -> null
    ServerCheck.InvalidAddress -> resources.getString(R.string.account_server_invalid)
    ServerCheck.Unreachable -> resources.getString(R.string.account_server_unreachable)
    ServerCheck.NotAServer -> resources.getString(R.string.account_server_not_server)
}

internal fun loginLine(result: LoginResult, resources: Resources): String? = when (result) {
    is LoginResult.LoggedIn -> null
    LoginResult.InvalidCredentials -> resources.getString(R.string.account_login_invalid)
    LoginResult.Disabled -> resources.getString(R.string.account_login_disabled)
    LoginResult.TooManyAttempts -> resources.getString(R.string.account_login_too_many)
    LoginResult.Unreachable -> resources.getString(R.string.account_login_unreachable)
    is LoginResult.Failed -> resources.getString(R.string.account_login_failed, result.message.orEmpty())
}

internal fun passwordLine(result: PasswordChangeResult, resources: Resources): String = when (result) {
    PasswordChangeResult.Changed -> resources.getString(R.string.account_password_changed)
    PasswordChangeResult.WrongPassword -> resources.getString(R.string.account_password_wrong)
    PasswordChangeResult.TooShort -> resources.getString(R.string.account_password_too_short, ApiLimits.MIN_PASSWORD_LENGTH)
    PasswordChangeResult.Unreachable -> resources.getString(R.string.account_password_unreachable)
    PasswordChangeResult.Failed -> resources.getString(R.string.account_password_failed)
}

/**
 * The current password and the new one twice. [onChanged] runs after the server took it; the
 * account flow needs nothing more, as the gate moves on when the must-change flag goes.
 */
@Composable
fun ChangePasswordForm(onChanged: () -> Unit = {}) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun change() {
        if (busy) return
        error = when {
            new != again -> resources.getString(R.string.account_password_mismatch)
            new.length < ApiLimits.MIN_PASSWORD_LENGTH -> resources.getString(R.string.account_password_too_short, ApiLimits.MIN_PASSWORD_LENGTH)
            else -> null
        }
        if (error != null) return
        busy = true
        scope.launch {
            try {
                val result = container.account.changePassword(current, new)
                if (result == PasswordChangeResult.Changed) onChanged() else error = passwordLine(result, resources)
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PaperTextField(current, { current = it; error = null }, stringResource(R.string.account_password_current), isPassword = true, imeAction = ImeAction.Next, enabled = !busy)
        PaperTextField(new, { new = it; error = null }, stringResource(R.string.account_password_new), isPassword = true, imeAction = ImeAction.Next, enabled = !busy)
        PaperTextField(again, { again = it; error = null }, stringResource(R.string.account_password_again), isPassword = true, onImeAction = ::change, enabled = !busy)
        AccountNotice(error)
        PaperButton(
            stringResource(if (busy) R.string.account_password_changing else R.string.account_password_change),
            ::change,
            primary = true,
            enabled = !busy && current.isNotEmpty() && new.isNotEmpty() && again.isNotEmpty(),
        )
    }
}

/** The first login's generated password must be changed before anything else answers. */
@Composable
fun ChangePasswordScreen() {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    AccountFrame(title = stringResource(R.string.account_password_title)) {
        AccountText(stringResource(R.string.account_password_first, ApiLimits.MIN_PASSWORD_LENGTH))
        ChangePasswordForm()
        TextLink(stringResource(R.string.account_log_out), { scope.launch { container.account.logout(force = true) } })
    }
}
