package ovh.litapp.pixlit.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lifecycle of the stored OAuth session.
 *
 * [Expired] is signalled by [AuthInterceptor] when the server answers 401 to a request that
 * carried a token, and is the only state that forces the UI back to the login screen.
 */
sealed interface SessionState {
    data object Authenticated : SessionState
    data object Expired : SessionState
    data object LoggedOut : SessionState
}

/** Snapshot of the stored OAuth credentials, mirroring the encrypted preference file. */
data class StoredCredentials(
    val instanceUrl: String? = null,
    val accessToken: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null
)

/**
 * Owns the stored OAuth session (SEC-02, DATA-09).
 *
 * The credential file stays the Keystore-backed [EncryptedSharedPreferences] landed by SEC-02 — the
 * bearer token must not be written as plaintext — but every write now uses `apply()` instead of the
 * blocking `commit()`, so logout and the registration/token setters never touch the disk on the
 * caller's thread. The values are mirrored into [credentials] (and [sessionState]) so login/logout
 * is observable without polling.
 *
 * The synchronous getters and the constructor's initial read are kept because the public surface is
 * read synchronously from several seams — [AuthInterceptor] runs on an OkHttp thread, and
 * `MainViewModel` reads `isLoggedIn()` at construction. Priming that first read asynchronously would
 * flash the login screen on every cold start for a logged-in user, so it is intentionally left as
 * the one synchronous read; `apply()`'s background flush is what removes the write cost.
 */
@Singleton
class TokenManager @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = createPreferences(context)

    private val _credentials = MutableStateFlow(
        StoredCredentials(
            instanceUrl = prefs.getString(KEY_INSTANCE_URL, null),
            accessToken = prefs.getString(KEY_ACCESS_TOKEN, null),
            clientId = prefs.getString(KEY_CLIENT_ID, null),
            clientSecret = prefs.getString(KEY_CLIENT_SECRET, null)
        )
    )
    val credentials: StateFlow<StoredCredentials> = _credentials.asStateFlow()

    private val _sessionState = MutableStateFlow(
        if (hasStoredCredentials()) SessionState.Authenticated else SessionState.LoggedOut
    )
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    var instanceUrl: String?
        get() = _credentials.value.instanceUrl
        set(value) {
            _credentials.value = _credentials.value.copy(instanceUrl = value)
            prefs.edit().putString(KEY_INSTANCE_URL, value).apply()
        }

    var accessToken: String?
        get() = _credentials.value.accessToken
        set(value) {
            _credentials.value = _credentials.value.copy(accessToken = value)
            prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()
            refreshSessionState()
        }

    var clientId: String?
        get() = _credentials.value.clientId
        set(value) {
            _credentials.value = _credentials.value.copy(clientId = value)
            prefs.edit().putString(KEY_CLIENT_ID, value).apply()
        }

    var clientSecret: String?
        get() = _credentials.value.clientSecret
        set(value) {
            _credentials.value = _credentials.value.copy(clientSecret = value)
            prefs.edit().putString(KEY_CLIENT_SECRET, value).apply()
        }

    fun isLoggedIn(): Boolean {
        return _sessionState.value != SessionState.Expired && hasStoredCredentials()
    }

    /** Called by [AuthInterceptor] when the server rejects the stored token with HTTP 401. */
    fun onUnauthorized() {
        if (_sessionState.value != SessionState.Expired) {
            _sessionState.value = SessionState.Expired
        }
    }

    fun clear() {
        _credentials.value = StoredCredentials()
        prefs.edit().clear().apply()
        _sessionState.value = SessionState.LoggedOut
    }

    private fun refreshSessionState() {
        // Matches the previous setter: storing a fresh token re-authenticates even after a 401.
        _sessionState.value = if (hasStoredCredentials()) {
            SessionState.Authenticated
        } else {
            SessionState.LoggedOut
        }
    }

    private fun hasStoredCredentials(): Boolean =
        !_credentials.value.accessToken.isNullOrBlank() &&
            !_credentials.value.instanceUrl.isNullOrBlank()

    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_FILE = "pixlit_prefs"
        private const val FALLBACK_PREFS_FILE = "pixlit_prefs_fallback"
        private const val KEY_INSTANCE_URL = "instance_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"

        /**
         * The bearer token and client secret must not sit in a plaintext preference file. The
         * keystore-backed store is the default; when it is unavailable (a device without a usable
         * keystore, or the JVM test environment) we fall back to app-private prefs on a *separate*
         * file so the two stores are never mixed. Both files are excluded from backup/transfer.
         */
        private fun createPreferences(context: Context): SharedPreferences =
            runCatching {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrElse { error ->
                Log.e(TAG, "EncryptedSharedPreferences unavailable; using private fallback prefs", error)
                context.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE)
            }
    }
}
