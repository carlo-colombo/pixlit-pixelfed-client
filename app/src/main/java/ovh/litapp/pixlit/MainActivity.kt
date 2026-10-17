package ovh.litapp.pixlit

import android.content.Intent
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import dagger.hilt.android.AndroidEntryPoint
import ovh.litapp.pixlit.data.auth.TokenManager
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import ovh.litapp.pixlit.data.reminder.ReminderPreferences
import ovh.litapp.pixlit.data.reminder.ReminderScheduler
import ovh.litapp.pixlit.data.reminder.ArtShowNotifier
import ovh.litapp.pixlit.ui.auth.LoginScreen
import ovh.litapp.pixlit.ui.upload.UploadScreen
import ovh.litapp.pixlit.ui.theme.PixlitTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var repository: PixelfedRepository
    @Inject lateinit var reminderPreferences: ReminderPreferences
    @Inject lateinit var reminderScheduler: ReminderScheduler
    @Inject lateinit var artShowNotifier: ArtShowNotifier

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // POST_NOTIFICATIONS is requested in context when a reminder is enabled (Settings tab),
        // never on cold start. Scheduling is best-effort so a stale reminder time cannot crash startup.
        runCatching { reminderScheduler.scheduleAll() }
            .onFailure { Log.w("MainActivity", "Failed to schedule art-show reminders on startup", it) }

        viewModel.handleIntent(intent)
        val activity = this

        setContent {
            val isLoggedIn by viewModel.isLoggedIn.collectAsState()
            val isAuthProcessing by viewModel.isAuthProcessing.collectAsState()
            val authError by viewModel.authError.collectAsState()
            val prefillTheme by viewModel.prefillTheme.collectAsState()
            val sharedImageUris by viewModel.sharedImageUris.collectAsState()

            LaunchedEffect(isLoggedIn) {
                if (isLoggedIn && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                    checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_MEDIA_LOCATION), 101)
                }
            }

            PixlitTheme {
                // The root owns the top status-bar inset once, so DevBanner and the screens below
                // no longer each pad it; screens own their own bottom/IME insets.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .semantics { testTagsAsResourceId = true }
                        .testTag("pixlit_root")
                ) {
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
                                onLogout = { viewModel.logout() },
                                prefillTheme = prefillTheme,
                                onPrefillConsumed = { viewModel.consumePrefill() },
                                sharedImageUris = sharedImageUris,
                                onSharedImageUrisConsumed = { viewModel.consumeSharedImageUris() },
                                reminderPreferences = reminderPreferences,
                                reminderScheduler = reminderScheduler,
                                onSimulateNotification = if (BuildConfig.DEBUG_TOOLS) {
                                    { artShowNotifier.notify("#Minimal", false) }
                                } else {
                                    {}
                                }
                            )
                        } else {
                            LoginScreen(
                                context = activity,
                                tokenManager = tokenManager,
                                repository = repository,
                                initialErrorMessage = authError,
                                onGenerateOAuthState = { viewModel.createOAuthState() }
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
