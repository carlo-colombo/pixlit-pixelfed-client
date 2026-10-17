package ovh.litapp.pixlit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ovh.litapp.pixlit.data.auth.Pkce
import ovh.litapp.pixlit.data.auth.SessionState
import ovh.litapp.pixlit.data.auth.TokenManager
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import java.util.UUID
import javax.inject.Inject

/**
 * The authorize-URL parameters of one login attempt (SEC-03): the anti-CSRF [state] and the S256
 * PKCE [codeChallenge]. The verifier that produced the challenge never leaves [MainViewModel]; it is
 * held in memory and sent only with the token exchange.
 */
data class OAuthAuthorizeRequest(val state: String, val codeChallenge: String)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenManager: TokenManager,
    private val repository: PixelfedRepository
) : ViewModel() {

    private val _isLoggedIn = MutableStateFlow(tokenManager.isLoggedIn())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _isAuthProcessing = MutableStateFlow(false)
    val isAuthProcessing: StateFlow<Boolean> = _isAuthProcessing.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _prefillTheme = MutableStateFlow<String?>(null)
    val prefillTheme: StateFlow<String?> = _prefillTheme.asStateFlow()

    private val _sharedImageUris = MutableStateFlow<List<Uri>>(emptyList())
    val sharedImageUris: StateFlow<List<Uri>> = _sharedImageUris.asStateFlow()

    // Anti-CSRF `state` and the PKCE verifier for the in-flight authorization redirect. In-memory
    // only: the Custom Tab normally returns to the same process, and SEC-03 consumes both once.
    private var pendingOAuthState: String? = null
    private var pendingCodeVerifier: String? = null

    init {
        viewModelScope.launch {
            tokenManager.sessionState.collect { state ->
                when (state) {
                    SessionState.Authenticated -> _isLoggedIn.value = true
                    SessionState.Expired -> {
                        _isLoggedIn.value = false
                        _authError.value = "Session expired — sign in again"
                    }
                    SessionState.LoggedOut -> _isLoggedIn.value = false
                }
            }
        }
    }

    fun handleIntent(intent: Intent?) {
        val action = intent?.action
        val type = intent?.type

        if (action == Intent.ACTION_SEND && (type == null || type.startsWith("image/"))) {
            val uri = runCatching {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            }.getOrNull()
            if (uri != null) {
                _sharedImageUris.value = listOf(uri)
            }
        } else if (action == Intent.ACTION_SEND_MULTIPLE && (type == null || type.startsWith("image/"))) {
            val uris = runCatching {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            }.getOrNull()
            if (!uris.isNullOrEmpty()) {
                _sharedImageUris.value = uris.filterNotNull()
            }
        }

        if (runCatching { intent?.getBooleanExtra("prefill", false) }.getOrDefault(false) == true) {
            _prefillTheme.value = runCatching { intent?.getStringExtra("theme") }.getOrNull() ?: ""
        }
        val uri = intent?.data
        val redirectUri = context.getString(R.string.redirect_uri)
        val scheme = redirectUri.split("://").first()

        if (uri != null && uri.scheme == scheme && uri.host == "oauth") {
            val error = uri.getQueryParameter("error")
            val errorDescription = uri.getQueryParameter("error_description")
            if (error != null) {
                _authError.value = "OAuth Authorize Error: ${errorDescription ?: error}"
                return
            }

            val code = uri.getQueryParameter("code")
            if (code != null) {
                // Verify the redirect belongs to an authorization request this app started before
                // exchanging the code; an absent or mismatched state is treated as an auth error.
                // The PKCE verifier is read here (not from the redirect) so a captured code cannot
                // be redeemed by an app that did not start this login.
                val expectedState = pendingOAuthState
                val codeVerifier = pendingCodeVerifier
                val returnedState = uri.getQueryParameter("state")
                if (expectedState == null || codeVerifier == null || returnedState != expectedState) {
                    _authError.value = "OAuth state mismatch — sign-in was not completed"
                    return
                }
                pendingOAuthState = null
                pendingCodeVerifier = null
                _isAuthProcessing.value = true
                _authError.value = null
                viewModelScope.launch {
                    val result = repository.exchangeCodeForToken(code, redirectUri, codeVerifier)
                    _isAuthProcessing.value = false
                    result.fold(
                        onSuccess = {
                            _isLoggedIn.value = true
                        },
                        onFailure = { ex ->
                            val msg = ex.localizedMessage ?: ex.message ?: ex.toString()
                            Log.e("MainViewModel", "OAuth Token Exchange Failed: $msg", ex)
                            _authError.value = "OAuth Token Exchange Failed:\n$msg"
                        }
                    )
                }
            }
        }
    }

    fun consumePrefill() { _prefillTheme.value = null }

    /**
     * Generates a random anti-CSRF `state` and a PKCE verifier with its S256 challenge, keeping the
     * verifier until [handleIntent] exchanges the returned code. [handleIntent] refuses to exchange
     * a code whose `state` does not match.
     */
    fun createOAuthState(): OAuthAuthorizeRequest {
        val state = UUID.randomUUID().toString()
        val verifier = Pkce.generateVerifier()
        pendingOAuthState = state
        pendingCodeVerifier = verifier
        return OAuthAuthorizeRequest(state = state, codeChallenge = Pkce.s256Challenge(verifier))
    }

    fun consumeSharedImageUris() { _sharedImageUris.value = emptyList() }

    fun logout() {
        tokenManager.clear()
        _isLoggedIn.value = false
        _authError.value = null
    }

    fun setAuthProcessing(processing: Boolean) {
        _isAuthProcessing.value = processing
    }

    fun setAuthError(error: String?) {
        _authError.value = error
    }
}
