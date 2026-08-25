package ovh.litapp.pixlit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import ovh.litapp.pixlit.data.auth.TokenManager
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import ovh.litapp.pixlit.ui.auth.LoginScreen
import ovh.litapp.pixlit.ui.upload.UploadScreen
import ovh.litapp.pixlit.ui.theme.PixlitTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var repository: PixelfedRepository

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel.handleIntent(intent)
        val activity = this

        setContent {
            val isLoggedIn by viewModel.isLoggedIn.collectAsState()
            val isAuthProcessing by viewModel.isAuthProcessing.collectAsState()
            val authError by viewModel.authError.collectAsState()

            PixlitTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    DevBanner()
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        if (isAuthProcessing) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        } else if (isLoggedIn) {
                            UploadScreen(
                                repository = repository,
                                onLogout = { viewModel.logout() }
                            )
                        } else {
                            LoginScreen(
                                context = activity,
                                tokenManager = tokenManager,
                                repository = repository,
                                initialErrorMessage = authError
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIntent(intent)
    }
}
