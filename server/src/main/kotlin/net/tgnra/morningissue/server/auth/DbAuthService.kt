package net.tgnra.morningissue.server.auth

import java.io.File
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.tgnra.morningissue.api.AccountResponse
import net.tgnra.morningissue.api.ApiError
import net.tgnra.morningissue.api.ApiLimits
import net.tgnra.morningissue.api.ChangePasswordRequest
import net.tgnra.morningissue.api.DeviceKind
import net.tgnra.morningissue.api.LoginRequest
import net.tgnra.morningissue.api.LoginResponse
import net.tgnra.morningissue.api.UserDto
import net.tgnra.morningissue.kobo.KoboProtocol
import net.tgnra.morningissue.server.db.DeviceEntity
import net.tgnra.morningissue.server.db.ServerDatabase
import net.tgnra.morningissue.server.service.ApiFailure
import net.tgnra.morningissue.server.service.AuthService
import net.tgnra.morningissue.server.service.Principal

/**
 * [AuthService] over the `users` and `device` tables (section 4.1). Passwords are PBKDF2 hashes
 * ([Passwords]); tokens are stored as their SHA-256 ([Tokens]).
 */
class DbAuthService(
    private val db: ServerDatabase,
    initialPasswordsDir: File,
    private val clock: Clock = Clock.systemUTC(),
    private val passwords: Passwords = Passwords(),
    private val rateLimiter: LoginRateLimiter = LoginRateLimiter(clock),
) : AuthService {
    private val accounts = db.accountDao()
    private val devices = db.deviceDao()
    private val bootstrap = UserBootstrap(db, passwords, initialPasswordsDir, clock)

    override suspend fun authenticate(token: String): Principal? {
        val device = devices.byTokenHash(Tokens.hash(token)) ?: return null
        if (device.revokedAt != null) return null
        if (device.disabled) throw ApiFailure(ApiError.ACCOUNT_DISABLED, "This account is disabled")
        return Principal(device.userId, device.id, device.kind, device.mustChangePassword)
    }

    override suspend fun login(request: LoginRequest): LoginResponse {
        val deviceId = request.device.id.trim()
        if (deviceId.isEmpty() || deviceId.length > KoboProtocol.MAX_DEVICE_ID_CHARS) throw ApiFailure.badRequest("device.id must be 1 to 64 characters")
        if (request.device.kind != DeviceKind.PHONE) throw ApiFailure.badRequest("Only a phone logs in; pair a Kobo from the phone")
        val name = request.device.name.trim().ifEmpty { "Phone" }.take(KoboProtocol.MAX_NAME_CHARS)
        val username = request.username.trim()
        if (!rateLimiter.allowed(username)) {
            val wait = rateLimiter.retryAfter(username)?.toMinutes()?.plus(1) ?: 15
            throw ApiFailure(ApiError.TOO_MANY_ATTEMPTS, "Too many failed attempts; try again in $wait minutes")
        }
        val user = accounts.userByName(username)
        val ok = withContext(Dispatchers.Default) {
            if (user == null) {
                passwords.burn(request.password)
                false
            } else {
                passwords.verify(request.password, user.passwordHash)
            }
        }
        if (user == null || !ok) {
            rateLimiter.failed(username)
            throw ApiFailure(ApiError.INVALID_CREDENTIALS, "Wrong user name or password")
        }
        rateLimiter.succeeded(username)
        if (user.disabled) throw ApiFailure(ApiError.ACCOUNT_DISABLED, "This account is disabled")

        val token = Tokens.newToken()
        storeDevice(user.id, deviceId, DeviceKind.PHONE, name, width = null, tokenHash = Tokens.hash(token), db, clock, seenNow = true)
        return LoginResponse(
            token = token,
            deviceId = deviceId,
            mustChangePassword = user.mustChangePassword,
            user = UserDto(user.username, user.dbId),
        )
    }

    override suspend fun changePassword(userId: Long, request: ChangePasswordRequest) {
        val user = accounts.user(userId) ?: throw ApiFailure(ApiError.UNAUTHENTICATED, "No such account")
        val ok = withContext(Dispatchers.Default) { passwords.verify(request.current, user.passwordHash) }
        if (!ok) throw ApiFailure(ApiError.INVALID_CREDENTIALS, "The current password is wrong")
        if (request.newPassword.length < ApiLimits.MIN_PASSWORD_LENGTH) {
            throw ApiFailure(ApiError.WEAK_PASSWORD, "Use at least ${ApiLimits.MIN_PASSWORD_LENGTH} characters")
        }
        if (request.newPassword == request.current) throw ApiFailure(ApiError.WEAK_PASSWORD, "Choose a password different from the current one")
        val hash = withContext(Dispatchers.Default) { passwords.hash(request.newPassword) }
        accounts.setPassword(userId, hash, mustChange = false)
        bootstrap.forgetInitialPassword(user.username)
    }

    override suspend fun logout(userId: Long, deviceId: String) {
        devices.revoke(userId, deviceId, clock.instant())
    }

    override suspend fun account(userId: Long): AccountResponse {
        val user = accounts.user(userId) ?: throw ApiFailure(ApiError.UNAUTHENTICATED, "No such account")
        return AccountResponse(
            username = user.username,
            dbId = user.dbId,
            mustChangePassword = user.mustChangePassword,
            hasData = accounts.hasData(userId),
            settingsCustomized = accounts.settingsCustomized(userId) ?: false,
        )
    }

    override suspend fun resetPassword(username: String): String = bootstrap.resetPassword(username)

    override suspend fun bootstrapUsers(usernames: List<String>) = bootstrap.bootstrap(usernames)
}

/**
 * Stores a device's new token: a device seen before under the same user and kind keeps its
 * `acked_seq` and creation time and is un-revoked; one seen under another user (or as the other
 * kind) starts over, because its events belonged to that account.
 */
internal suspend fun storeDevice(
    userId: Long,
    deviceId: String,
    kind: DeviceKind,
    name: String,
    width: Int?,
    tokenHash: String,
    db: ServerDatabase,
    clock: Clock,
    seenNow: Boolean,
): DeviceEntity {
    val dao = db.deviceDao()
    val now = clock.instant()
    val old = dao.byId(deviceId)?.takeIf { it.userId == userId && it.kind == kind }
    val row = DeviceEntity(
        id = deviceId,
        userId = userId,
        kind = kind,
        name = name,
        width = width,
        tokenHash = tokenHash,
        ackedSeq = old?.ackedSeq ?: 0,
        createdAt = old?.createdAt ?: now,
        lastSeenAt = if (seenNow) now else old?.lastSeenAt,
        revokedAt = null,
    )
    // REPLACE: the row for this device ID goes, and so would another holding the same token
    // hash (only possible for a Kobo secret paired under a second device ID).
    dao.upsert(row)
    return row
}
