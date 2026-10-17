package ovh.litapp.pixlit.ui.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import ovh.litapp.pixlit.R
import ovh.litapp.pixlit.data.auth.TokenManager
import ovh.litapp.pixlit.data.repository.PixelfedRepository
import kotlinx.coroutines.launch
import ovh.litapp.pixlit.ui.theme.PixlitTheme

@Composable
fun LoginScreen(
    context: Context,
    tokenManager: TokenManager,
    repository: PixelfedRepository,
    initialErrorMessage: String? = null,
    onGenerateOAuthState: () -> String
) {
    val scope = rememberCoroutineScope()
    val redirectUri = context.getString(R.string.redirect_uri)
    val uiState = remember { LoginUiState(initialErrorMessage) }

    // A launched Custom Tab pauses this screen. Resuming without a redirect
    // result (the user closed the tab) must resolve the pending login, otherwise
    // the button spins forever with no way out.
    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                uiState.onScreenResumed()
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    LaunchedEffect(initialErrorMessage) {
        uiState.onExternalError(initialErrorMessage)
    }

    LoginContent(
        uiState = uiState,
        onLoginClick = { instanceUrl, manualClientId, manualClientSecret ->
            var formattedUrl = instanceUrl.trim()
            if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
                formattedUrl = "https://$formattedUrl"
            }
            formattedUrl = formattedUrl.trimEnd('/')

            scope.launch {
                val clientIdToUse: String?
                if (manualClientId.isNotBlank() && manualClientSecret.isNotBlank()) {
                    tokenManager.instanceUrl = formattedUrl
                    tokenManager.clientId = manualClientId.trim()
                    tokenManager.clientSecret = manualClientSecret.trim()
                    clientIdToUse = manualClientId.trim()
                } else {
                    val result = repository.registerApp(formattedUrl, redirectUri)
                    clientIdToUse = result.getOrNull()?.first
                    if (clientIdToUse.isNullOrBlank()) {
                        val reason = result.exceptionOrNull()?.localizedMessage
                            ?: "Could not reach $formattedUrl"
                        uiState.onLoginFailed(reason)
                    }
                }

                if (clientIdToUse.isNullOrBlank()) {
                    // Every failure above already resolved the loading state.
                    return@launch
                }

                val authUrl = Uri.parse(formattedUrl)
                    .buildUpon()
                    .appendPath("oauth")
                    .appendPath("authorize")
                    .appendQueryParameter("client_id", clientIdToUse)
                    .appendQueryParameter("redirect_uri", redirectUri)
                    .appendQueryParameter("response_type", "code")
                    // Uploader scope only: no DMs and no follows are requested.
                    .appendQueryParameter("scope", "read write")
                    // Anti-CSRF state, verified in MainViewModel.handleIntent before the exchange.
                    .appendQueryParameter("state", onGenerateOAuthState())
                    .build()

                val customTabsIntent = CustomTabsIntent.Builder().build()
                runCatching { customTabsIntent.launchUrl(context, authUrl) }
                    .onSuccess { uiState.onRedirectLaunched() }
                    .onFailure {
                        uiState.onLoginFailed(
                            it.localizedMessage ?: "Could not open the authorization page"
                        )
                    }
            }
        }
    )
}

@Composable
fun LoginContent(
    uiState: LoginUiState,
    onLoginClick: (String, String, String) -> Unit = { _, _, _ -> }
) {
    var instanceUrl by remember { mutableStateOf("https://pixelfed.social") }
    var manualClientId by remember { mutableStateOf("") }
    var manualClientSecret by remember { mutableStateOf("") }
    var showManualCredentials by remember { mutableStateOf(false) }
    val expandedState = stringResource(R.string.a11y_state_expanded)
    val collapsedState = stringResource(R.string.a11y_state_collapsed)
    val missingUrlError = stringResource(R.string.login_missing_url_error)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = instanceUrl,
            onValueChange = { instanceUrl = it },
            label = { Text(stringResource(R.string.login_instance_url_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            TextButton(
                onClick = { showManualCredentials = !showManualCredentials },
                modifier = Modifier.semantics {
                    stateDescription = if (showManualCredentials) expandedState else collapsedState
                }
            ) {
                Text(
                    text = stringResource(
                        if (showManualCredentials) R.string.login_advanced_hide
                        else R.string.login_advanced_show
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (showManualCredentials) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = manualClientId,
                onValueChange = { manualClientId = it },
                label = { Text(stringResource(R.string.login_client_id_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = manualClientSecret,
                onValueChange = { manualClientSecret = it },
                label = { Text(stringResource(R.string.login_client_secret_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        val errorMessage = uiState.errorMessage
        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = {
                if (instanceUrl.isBlank()) {
                    uiState.onLoginFailed(missingUrlError)
                    return@Button
                }
                uiState.onLoginStarted()
                onLoginClick(instanceUrl, manualClientId, manualClientSecret)
            },
            enabled = !uiState.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.login_button))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    PixlitTheme {
        LoginContent(uiState = remember { LoginUiState() })
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenErrorPreview() {
    PixlitTheme {
        LoginContent(
            uiState = remember {
                LoginUiState(
                    initialErrorMessage = "Could not connect to instance. Please check your URL and try again."
                )
            }
        )
    }
}
