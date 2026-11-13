package net.tgnra.morningissue.server.service

import net.tgnra.morningissue.api.AccountResponse
import net.tgnra.morningissue.api.ChangePasswordRequest
import net.tgnra.morningissue.api.LoginRequest
import net.tgnra.morningissue.api.LoginResponse

/** Accounts, passwords and tokens (4.1). Implemented by W-C in `auth/`. Failures are [ApiFailure]s. */
interface AuthService {
    /** The device a bearer [token] belongs to, or null for an unknown or revoked one. */
    suspend fun authenticate(token: String): Principal?

    /** `POST /auth/login`: a new token for the device, replacing its old one and keeping its `acked_seq`. */
    suspend fun login(request: LoginRequest): LoginResponse

    /** `POST /auth/password`: clears `must_change_password` and deletes the initial password file. */
    suspend fun changePassword(userId: Long, request: ChangePasswordRequest)

    /** `POST /auth/logout`: revokes [deviceId]'s token. */
    suspend fun logout(userId: Long, deviceId: String)

    /** `GET /account`. */
    suspend fun account(userId: Long): AccountResponse

    /** The CLI's `reset-password <user>`: a new generated password (returned), phone tokens revoked. */
    suspend fun resetPassword(username: String): String

    /** Creates configured users that have no row, disables those no longer configured, re-enables returning ones. */
    suspend fun bootstrapUsers(usernames: List<String>)
}
