package net.tgnra.morningissue.client

import kotlin.coroutines.cancellation.CancellationException
import net.tgnra.morningissue.api.ApiLimits
import net.tgnra.morningissue.api.ChangePasswordRequest
import net.tgnra.morningissue.api.HealthResponse
import net.tgnra.morningissue.api.LoginDevice
import net.tgnra.morningissue.api.LoginRequest
import net.tgnra.morningissue.api.client.ApiException

/** A server address as typed, made into a URL: [encrypted] is false for `http://`, which the reader must confirm. */
data class ServerAddress(val url: String, val encrypted: Boolean) {
    companion object {
        /** A bare host gets `https://`; a trailing slash and `/api/v1` are dropped. Null for an empty or unusable address. */
        fun parse(input: String): ServerAddress? {
            var text = input.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            if (!text.contains("://")) text = "https://$text"
            val scheme = text.substringBefore("://").lowercase()
            if (scheme != "https" && scheme != "http") return null
            var rest = text.substringAfter("://").trimEnd('/')
            rest = rest.removeSuffix("/api/v1").trimEnd('/')
            if (rest.isEmpty() || rest.startsWith("/")) return null
            return ServerAddress("$scheme://$rest", encrypted = scheme == "https")
        }
    }
}

/** How a check of the server address went. */
sealed interface ServerCheck {
    data class Reachable(val address: ServerAddress, val version: String) : ServerCheck

    /** The address answered, but not as a Morning Issue server (or a newer API). */
    data object NotAServer : ServerCheck

    data object Unreachable : ServerCheck

    data object InvalidAddress : ServerCheck
}

sealed interface LoginResult {
    /** Logged in; [mustChangePassword] sends the reader to change the generated password first. */
    data class LoggedIn(val mustChangePassword: Boolean) : LoginResult

    data object InvalidCredentials : LoginResult

    data object Disabled : LoginResult

    data object TooManyAttempts : LoginResult

    data object Unreachable : LoginResult

    data class Failed(val message: String?) : LoginResult
}

enum class PasswordChangeResult { Changed, WrongPassword, TooShort, Unreachable, Failed }

sealed interface LogoutResult {
    data object LoggedOut : LogoutResult

    /** Events the server has not got yet would be lost; log out again with `force` to drop them. */
    data class UnsentEvents(val count: Int) : LogoutResult
}

/**
 * The account on this phone: the server check, login, the password change and logout
 * (`docs/server-design.md`, 5.3). Nothing here throws but cancellation.
 *
 * @param resetLocalCache empties the cache (database and stored images).
 */
class AccountController(
    private val auth: AuthStore,
    private val connection: ServerConnection,
    private val outbox: Outbox,
    private val uploader: OutboxUploader,
    private val deviceName: () -> String,
    private val resetLocalCache: suspend () -> Unit,
    private val onLoggedOut: () -> Unit = {},
) {
    /** `GET /health` at the address typed. */
    suspend fun check(input: String): ServerCheck {
        val address = ServerAddress.parse(input) ?: return ServerCheck.InvalidAddress
        return try {
            val health: HealthResponse = connection.anonymous(address.url).health()
            if (health.ok) ServerCheck.Reachable(address, health.version) else ServerCheck.NotAServer
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException.Offline) {
            ServerCheck.Unreachable
        } catch (_: ApiException) {
            ServerCheck.NotAServer
        }
    }

    /** `POST /auth/login`. Logging in to another account than the cache's empties the cache first. */
    suspend fun login(serverUrl: String, username: String, password: String): LoginResult {
        val api = connection.anonymous(serverUrl)
        val deviceId = auth.deviceId()
        return try {
            val answer = api.login(LoginRequest(username.trim(), password, LoginDevice(deviceId, deviceName())))
            val before = auth.current()
            val otherAccount = before.username != answer.user.username || before.serverUrl != serverUrl
            // A library still to be uploaded is not a cache: it is kept until the migration screen.
            if (otherAccount && before.migrationSettled) resetLocalCache()
            auth.loggedIn(serverUrl, answer.token, answer.user.username, answer.user.dbId, answer.mustChangePassword)
            LoginResult.LoggedIn(answer.mustChangePassword)
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException.Offline) {
            LoginResult.Unreachable
        } catch (_: ApiException.Unauthenticated) {
            LoginResult.InvalidCredentials
        } catch (e: ApiException.Http) {
            when {
                e.status == 429 -> LoginResult.TooManyAttempts
                e.code == "account_disabled" -> LoginResult.Disabled
                e.code == "invalid_credentials" || e.status == 401 -> LoginResult.InvalidCredentials
                else -> LoginResult.Failed(e.message)
            }
        } catch (e: ApiException) {
            LoginResult.Failed(e.message)
        }
    }

    /** `POST /auth/password`; clears the must-change flag. */
    suspend fun changePassword(current: String, new: String): PasswordChangeResult {
        if (new.length < ApiLimits.MIN_PASSWORD_LENGTH) return PasswordChangeResult.TooShort
        return try {
            // Not through `call`: a 401 here is a wrong current password, not a revoked token.
            connection.api().changePassword(ChangePasswordRequest(current, new))
            auth.setMustChangePassword(false)
            PasswordChangeResult.Changed
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException.Offline) {
            PasswordChangeResult.Unreachable
        } catch (_: ApiException.Unauthenticated) {
            PasswordChangeResult.WrongPassword
        } catch (e: ApiException.Http) {
            when (e.code) {
                "invalid_credentials" -> PasswordChangeResult.WrongPassword
                "weak_password" -> PasswordChangeResult.TooShort
                else -> PasswordChangeResult.Failed
            }
        } catch (_: ApiException) {
            PasswordChangeResult.Failed
        }
    }

    /**
     * Uploads the outbox, then logs out and empties the cache. With events still unsent (the
     * server cannot be reached) it refuses unless [force]d.
     */
    suspend fun logout(force: Boolean = false): LogoutResult {
        attempt { connection.call { api -> uploader.flush(api) } }
        val unsent = outbox.count()
        if (unsent > 0 && !force) return LogoutResult.UnsentEvents(unsent)
        attempt { connection.call { it.logout() } }
        auth.signedOut()
        resetLocalCache()
        onLoggedOut()
        return LogoutResult.LoggedOut
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException) {
            // Logged out anyway.
        }
    }
}
