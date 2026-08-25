package ovh.litapp.pixlit

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ovh.litapp.pixlit.data.auth.TokenManager
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import javax.inject.Inject

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

    fun handleIntent(intent: Intent?) {
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
                _isAuthProcessing.value = true
                _authError.value = null
                viewModelScope.launch {
                    val result = repository.exchangeCodeForToken(code, redirectUri)
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
