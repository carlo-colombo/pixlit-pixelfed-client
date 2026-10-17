package ovh.litapp.pixlit.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Async status for the login screen, hoisted out of the composables so there is
 * one place that can end every login attempt.
 *
 * Only a launched authorization redirect may leave [isLoading] true. A failed
 * app registration, a redirect that never returned (the Custom Tab was closed),
 * and an auth error from the surrounding flow all resolve it, so the Log In
 * button cannot spin forever with no way out.
 */
class LoginUiState(initialErrorMessage: String? = null) {
    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf(initialErrorMessage)
        private set

    private var awaitingRedirect = false

    /** The user tapped Log In; registration or the redirect is starting. */
    fun onLoginStarted() {
        isLoading = true
        errorMessage = null
        awaitingRedirect = false
    }

    /** The authorization page opened; loading stays until the redirect returns. */
    fun onRedirectLaunched() {
        awaitingRedirect = true
    }

    /** Registration could not produce usable credentials. */
    fun onLoginFailed(message: String) {
        isLoading = false
        awaitingRedirect = false
        errorMessage = message
    }

    /** An error came back from the auth flow (OAuth authorize, token exchange). */
    fun onExternalError(message: String?) {
        if (!message.isNullOrBlank()) {
            isLoading = false
            awaitingRedirect = false
            errorMessage = message
        }
    }

    /**
     * The screen resumed. A pending redirect that did not come back (the user
     * closed the Custom Tab) is abandoned so the button is tappable again.
     */
    fun onScreenResumed() {
        if (awaitingRedirect) {
            isLoading = false
            awaitingRedirect = false
        }
    }
}
