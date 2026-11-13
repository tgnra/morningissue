package net.tgnra.morningissue.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 4.1 Auth and account

/** `GET /health`: unauthenticated; [api] is [API_VERSION]. */
@Serializable
data class HealthResponse(
    val ok: Boolean = true,
    /** The server's version, `x.y.z`. */
    val version: String,
    val api: Int = API_VERSION,
)

/** What kind of client a device token belongs to. A Kobo may call only the sync, bundle, saved-shelf and Kobo endpoints. */
enum class DeviceKind { PHONE, KOBO }

/** The device logging in. Logging in again with the same [id] replaces its token and keeps its `acked_seq`. */
@Serializable
data class LoginDevice(
    /** A UUID the client makes once per install. */
    val id: String,
    /** Shown in the device list, such as "Pixel 8". */
    val name: String,
    val kind: DeviceKind = DeviceKind.PHONE,
)

/** `POST /auth/login`. 401 `invalid_credentials`, 403 `account_disabled`, 429 after 5 failures per username in 15 minutes. */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val device: LoginDevice,
)

/** The account a token belongs to. [dbId] is the manifest's `dbId`; a change means the server's data was reset. */
@Serializable
data class UserDto(
    val username: String,
    val dbId: String,
)

/** `POST /auth/login`'s answer. [token] is the bearer token for every later request. */
@Serializable
data class LoginResponse(
    val token: String,
    val deviceId: String,
    /** True until the generated first password is changed; until then only `/auth/password`, `/auth/logout` and `/account` answer. */
    val mustChangePassword: Boolean,
    val user: UserDto,
)

/** `POST /auth/password`: 204; 401 `invalid_credentials`; 400 `weak_password` (under [ApiLimits.MIN_PASSWORD_LENGTH] characters). */
@Serializable
data class ChangePasswordRequest(
    val current: String,
    @SerialName("new") val newPassword: String,
)

/** `GET /account`. */
@Serializable
data class AccountResponse(
    val username: String,
    val dbId: String,
    val mustChangePassword: Boolean,
    /** At least one subscription or edition: an upload from the phone then merges instead of filling an empty account. */
    val hasData: Boolean,
    /** False until the settings were first changed or taken from an upload. */
    val settingsCustomized: Boolean,
)
