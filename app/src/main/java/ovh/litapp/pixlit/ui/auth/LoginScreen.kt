package ovh.litapp.pixlit.ui.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
    initialErrorMessage: String? = null
) {
    val scope = rememberCoroutineScope()
    val redirectUri = context.getString(R.string.redirect_uri)

    LoginContent(
        initialErrorMessage = initialErrorMessage,
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
                    if (result.isSuccess) {
                        clientIdToUse = result.getOrNull()?.first
                    } else {
                        clientIdToUse = null
                        // Handle error message passing back to LoginContent if needed
                        // For now, simpler to keep some logic inside LoginContent or use a State class
                    }
                }

                if (!clientIdToUse.isNullOrBlank()) {
                    val authUrl = Uri.parse(formattedUrl)
                        .buildUpon()
                        .appendPath("oauth")
                        .appendPath("authorize")
                        .appendQueryParameter("client_id", clientIdToUse)
                        .appendQueryParameter("redirect_uri", redirectUri)
                        .appendQueryParameter("response_type", "code")
                        .appendQueryParameter("scope", "read write follow")
                        .build()

                    val customTabsIntent = CustomTabsIntent.Builder().build()
                    customTabsIntent.launchUrl(context, authUrl)
                }
            }
        }
    )
}

@Composable
fun LoginContent(
    initialErrorMessage: String? = null,
    onLoginClick: (String, String, String) -> Unit = { _, _, _ -> }
) {
    var instanceUrl by remember { mutableStateOf("https://pixelfed.social") }
    var manualClientId by remember { mutableStateOf("") }
    var manualClientSecret by remember { mutableStateOf("") }
    var showManualCredentials by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(initialErrorMessage) }

    LaunchedEffect(initialErrorMessage) {
        if (!initialErrorMessage.isNullOrBlank()) {
            errorMessage = initialErrorMessage
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Connect to Pixlit",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = instanceUrl,
            onValueChange = { instanceUrl = it },
            label = { Text("Pixelfed Instance URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showManualCredentials = !showManualCredentials }
        ) {
            Text(
                text = if (showManualCredentials) "▲ Hide manual API credentials" else "▼ Advanced: Enter Client ID & Secret",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (showManualCredentials) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = manualClientId,
                onValueChange = { manualClientId = it },
                label = { Text("Client ID (Optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = manualClientSecret,
                onValueChange = { manualClientSecret = it },
                label = { Text("Client Secret (Optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer {
                    Text(
                        text = errorMessage!!,
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
                    errorMessage = "Please enter instance URL"
                    return@Button
                }
                isLoading = true
                errorMessage = null
                onLoginClick(instanceUrl, manualClientId, manualClientSecret)
            },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Log In with Pixlit")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    PixlitTheme {
        LoginContent()
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenErrorPreview() {
    PixlitTheme {
        LoginContent(initialErrorMessage = "Could not connect to instance. Please check your URL and try again.")
    }
}
