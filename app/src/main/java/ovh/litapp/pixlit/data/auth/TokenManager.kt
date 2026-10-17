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

@Singleton
class TokenManager @Inject constructor(@ApplicationContext context: Context) {
    private val prefs: SharedPreferences = createPreferences(context)

    private val _sessionState = MutableStateFlow(
        if (hasStoredCredentials()) SessionState.Authenticated else SessionState.LoggedOut
    )
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_FILE = "pixlit_prefs"
        private const val FALLBACK_PREFS_FILE = "pixlit_prefs_fallback"
        private const val KEY_INSTANCE_URL = "instance_url"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"
        private const val KEY_TAG_SEARCH_CACHE = "tag_search_cache"

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

    var tagSearchCacheJson: String?
        get() = prefs.getString(KEY_TAG_SEARCH_CACHE, null)
        set(value) {
            prefs.edit().putString(KEY_TAG_SEARCH_CACHE, value).commit()
        }

    var instanceUrl: String?
        get() = prefs.getString(KEY_INSTANCE_URL, null)
        set(value) {
            prefs.edit().putString(KEY_INSTANCE_URL, value).commit()
        }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_ACCESS_TOKEN, value).commit()
            _sessionState.value = if (hasStoredCredentials()) {
                SessionState.Authenticated
            } else {
                SessionState.LoggedOut
            }
        }

    var clientId: String?
        get() = prefs.getString(KEY_CLIENT_ID, null)
        set(value) {
            prefs.edit().putString(KEY_CLIENT_ID, value).commit()
        }

    var clientSecret: String?
        get() = prefs.getString(KEY_CLIENT_SECRET, null)
        set(value) {
            prefs.edit().putString(KEY_CLIENT_SECRET, value).commit()
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
        prefs.edit().clear().commit()
        _sessionState.value = SessionState.LoggedOut
    }

    private fun hasStoredCredentials(): Boolean =
        !prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrBlank() &&
            !prefs.getString(KEY_INSTANCE_URL, null).isNullOrBlank()
}
