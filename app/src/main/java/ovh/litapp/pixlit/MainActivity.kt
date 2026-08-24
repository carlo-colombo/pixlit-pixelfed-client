package ovh.litapp.pixlit

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ovh.litapp.pixlit.data.auth.TokenManager
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import ovh.litapp.pixlit.ui.auth.LoginScreen
import ovh.litapp.pixlit.ui.upload.UploadScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var tokenManager: TokenManager
    private lateinit var repository: PixelfedRepository

    private var isAuthProcessing = mutableStateOf(false)
    private var isLoggedInState = mutableStateOf(false)
    private var oauthErrorMessageState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tokenManager = TokenManager(this)
        repository = PixelfedRepository(this, tokenManager)

        isLoggedInState.value = tokenManager.isLoggedIn()

        handleIntent(intent)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isAuthProcessing.value) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (isLoggedInState.value) {
                        UploadScreen(
                            repository = repository,
                            onLogout = {
                                tokenManager.clear()
                                isLoggedInState.value = false
                                oauthErrorMessageState.value = null
                            }
                        )
                    } else {
                        LoginScreen(
                            context = this,
                            tokenManager = tokenManager,
                            repository = repository,
                            initialErrorMessage = oauthErrorMessageState.value
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        if (uri != null && uri.scheme == "pixelfed-app" && uri.host == "oauth") {
            val error = uri.getQueryParameter("error")
            val errorDescription = uri.getQueryParameter("error_description")
            if (error != null) {
                val oauthErrText = "OAuth Authorize Error: ${errorDescription ?: error}"
                oauthErrorMessageState.value = oauthErrText
                Toast.makeText(this, oauthErrText, Toast.LENGTH_LONG).show()
                return
            }

            val code = uri.getQueryParameter("code")
            if (code != null) {
                isAuthProcessing.value = true
                oauthErrorMessageState.value = null
                CoroutineScope(Dispatchers.Main).launch {
                    val result = repository.exchangeCodeForToken(code, "pixelfed-app://oauth")
                    isAuthProcessing.value = false
                    result.fold(
                        onSuccess = {
                            isLoggedInState.value = true
                            Toast.makeText(this@MainActivity, "Logged in successfully!", Toast.LENGTH_SHORT).show()
                        },
                        onFailure = { ex ->
                            val msg = ex.localizedMessage ?: ex.message ?: ex.toString()
                            oauthErrorMessageState.value = "OAuth Token Exchange Failed:\n$msg"
                            Toast.makeText(this@MainActivity, "OAuth Failed: $msg", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        }
    }
}
