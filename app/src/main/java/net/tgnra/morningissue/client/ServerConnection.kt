package net.tgnra.morningissue.client

import net.tgnra.morningissue.api.client.ApiException
import net.tgnra.morningissue.api.client.MorningIssueApi
import net.tgnra.morningissue.api.client.OkHttpMorningIssueApi
import okhttp3.OkHttpClient

/**
 * The API of the server this install is logged in to. Every call that needs the account goes
 * through [call], which also notices when the account is gone: a `401` means the token was
 * revoked (logged out elsewhere, or the password reset), so it is dropped and the app asks the
 * reader to log in again; a `403 password_change_required` sends them to change it.
 *
 * @param newApi makes the client for a server address; tests hand in their own.
 */
class ServerConnection(
    private val auth: AuthStore,
    private val newApi: (serverUrl: String, token: String?) -> MorningIssueApi,
) {
    constructor(auth: AuthStore, client: () -> OkHttpClient) :
        this(auth, { url, token -> OkHttpMorningIssueApi(url, client(), token) })

    @Volatile
    private var cached: Triple<String, String?, MorningIssueApi>? = null

    /** A client for [serverUrl] with no account: for `GET /health` and logging in. */
    fun anonymous(serverUrl: String): MorningIssueApi = newApi(serverUrl, null)

    /** The logged-in client; throws [ApiException.Unauthenticated] when there is no account. */
    suspend fun api(): MorningIssueApi {
        val state = auth.current()
        val url = state.serverUrl ?: throw ApiException.Unauthenticated(message = "No server")
        val token = state.token ?: throw ApiException.Unauthenticated(message = "Not logged in")
        cached?.let { (cachedUrl, cachedToken, api) -> if (cachedUrl == url && cachedToken == token) return api }
        return newApi(url, token).also { cached = Triple(url, token, it) }
    }

    /** Runs [block] against the logged-in client, and records a revoked token or a required password change. */
    suspend fun <T> call(block: suspend (MorningIssueApi) -> T): T {
        val api = api()
        try {
            return block(api)
        } catch (e: ApiException.Unauthenticated) {
            auth.signedOut()
            cached = null
            throw e
        } catch (e: ApiException.PasswordChangeRequired) {
            auth.setMustChangePassword(true)
            throw e
        }
    }
}
