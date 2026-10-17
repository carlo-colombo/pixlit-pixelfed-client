package ovh.litapp.pixlit.data.session

import android.util.Log
import ovh.litapp.pixlit.data.api.PixelfedApiFactory
import ovh.litapp.pixlit.data.auth.OAuthParsers
import ovh.litapp.pixlit.data.auth.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns OAuth app registration and authorization-code exchange (DATA-06 step 3 / F-12), including the
 * [TokenManager] writes. This is the single owner of auth state that an `AuthInterceptor` /
 * session-lifecycle change needs.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val tokenManager: TokenManager,
    private val apiFactory: PixelfedApiFactory
) {

    suspend fun registerApp(instanceUrl: String, redirectUri: String): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val cachedInstance = tokenManager.instanceUrl
            val cachedClientId = tokenManager.clientId
            val cachedClientSecret = tokenManager.clientSecret

            if (cachedInstance.equals(instanceUrl, ignoreCase = true) &&
                !cachedClientId.isNullOrBlank() &&
                !cachedClientSecret.isNullOrBlank()) {
                return@withContext Result.success(Pair(cachedClientId, cachedClientSecret))
            }

            val api = apiFactory.get(instanceUrl)
            val response = api.registerApp(
                clientName = "Pixlit Android Client",
                redirectUris = redirectUri,
                scopes = "read write follow",
                website = "https://pixelfed.org"
            )
            if (response.isSuccessful && response.body() != null) {
                val rawBodyString = response.body()!!.string()
                val (clientId, clientSecret) = OAuthParsers.parseRegistrationResponseBody(rawBodyString)
                if (!clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()) {
                    tokenManager.clientId = clientId
                    tokenManager.clientSecret = clientSecret
                    tokenManager.instanceUrl = instanceUrl
                    return@withContext Result.success(Pair(clientId, clientSecret))
                } else {
                    val preview = if (rawBodyString.length > 200) rawBodyString.take(200) + "..." else rawBodyString
                    return@withContext Result.failure(Exception("Registration response missing client_id or client_secret ($preview)"))
                }
            } else {
                val rawErrBody = response.errorBody()?.string()?.trim()
                val parsedMsg = if (!rawErrBody.isNullOrEmpty()) {
                    OAuthParsers.parseErrorResponseBody(rawErrBody)
                } else {
                    response.message().ifBlank { "HTTP ${response.code()}" }
                }

                val fullError = "Registration error (HTTP ${response.code()}): $parsedMsg"
                return@withContext Result.failure(Exception(fullError))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Network/Registration failed", e)
            val causeMessage = e.localizedMessage ?: e.message ?: e.toString()
            return@withContext Result.failure(Exception("Registration failed: $causeMessage"))
        }
    }

    suspend fun exchangeCodeForToken(code: String, redirectUri: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val instanceUrl = tokenManager.instanceUrl
            val clientId = tokenManager.clientId
            val clientSecret = tokenManager.clientSecret

            if (instanceUrl.isNullOrBlank() || clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
                val missing = mutableListOf<String>()
                if (instanceUrl.isNullOrBlank()) missing.add("instanceUrl")
                if (clientId.isNullOrBlank()) missing.add("clientId")
                if (clientSecret.isNullOrBlank()) missing.add("clientSecret")
                return@withContext Result.failure(Exception("Missing OAuth credentials required for token exchange: ${missing.joinToString()}"))
            }

            val api = apiFactory.get(instanceUrl)
            val response = api.fetchAccessToken(
                clientId = clientId,
                clientSecret = clientSecret,
                redirectUri = redirectUri,
                code = code,
                scope = "read write follow"
            )

            if (response.isSuccessful && response.body() != null) {
                val rawBodyString = response.body()!!.string()
                val accessToken = OAuthParsers.parseTokenResponseBody(rawBodyString)
                if (!accessToken.isNullOrBlank()) {
                    tokenManager.accessToken = accessToken
                    return@withContext Result.success(accessToken)
                } else {
                    val preview = if (rawBodyString.length > 200) rawBodyString.take(200) + "..." else rawBodyString
                    return@withContext Result.failure(Exception("Access token missing in OAuth response ($preview)"))
                }
            } else {
                val rawErrBody = response.errorBody()?.string()?.trim()
                val parsedMsg = if (!rawErrBody.isNullOrEmpty()) {
                    OAuthParsers.parseErrorResponseBody(rawErrBody)
                } else {
                    response.message().ifBlank { "HTTP ${response.code()}" }
                }

                val fullError = "OAuth Token error (HTTP ${response.code()}): $parsedMsg"
                return@withContext Result.failure(Exception(fullError))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "OAuth token exchange failed", e)
            val causeMessage = e.localizedMessage ?: e.message ?: e.toString()
            return@withContext Result.failure(Exception("Token exchange failed: $causeMessage"))
        }
    }

    companion object {
        private const val TAG = "SessionRepository"
    }
}
