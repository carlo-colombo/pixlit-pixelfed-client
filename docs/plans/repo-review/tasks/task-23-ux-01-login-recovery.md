# Task 23 — UX-01: Recover the login screen on failure

**Wave 1 · Priority P1 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) "Login button hangs on a spinner forever when app registration fails"

## Problem
The Log In button sets `isLoading = true` (`LoginScreen.kt:181-185`) and nothing in `LoginContent` ever resets it. When `repository.registerApp(...)` fails (bad URL, no network, non-Pixelfed host) the failure branch at `LoginScreen.kt:50-57` only leaves `clientIdToUse = null` — no Custom Tab opens, `errorMessage` stays null, and the CTA spins forever with no way out but killing the app. The same dead-end occurs if the user closes the Custom Tab without consenting (05-ux-compose.md).

## Scope
- Lift `isLoading`/`errorMessage` in `ui/auth/LoginScreen.kt` into a small `LoginUiState` holder so the async login callback can resolve it.
- Resolve every outcome: success → keep loading until the redirect returns; `registerApp` failure → `isLoading = false` plus the failure message (`it.localizedMessage ?: "Could not reach $formattedUrl"`) into the existing error text at `LoginScreen.kt:162-171`.
- Reset loading when the auth flow returns without a usable code, so closing the Custom Tab re-enables the button.
- Keep the success path (Custom Tab launch) unchanged.

```kotlin
data class LoginUiState(val isLoading: Boolean = false, val errorMessage: String? = null)
// registerApp success  -> isLoading stays true, Custom Tab opens
// registerApp failure  -> isLoading = false, errorMessage = "Could not reach $formattedUrl"
// redirect without code-> isLoading = false, button re-enabled
```

## Acceptance criteria
- [ ] A failed `registerApp` shows an error and re-enables the Log In button.
- [ ] Closing the Custom Tab without consenting leaves a usable, re-tappable button.
- [ ] No path leaves `isLoading == true` without a pending redirect.
- [ ] The happy path still launches the Custom Tab and logs in.

## Dependencies
- None

## Verification
- `./gradlew :app:assembleLocalDevDebug`
- Manual: airplane mode (or a bogus instance URL) → tap Log In → error text appears and the button is enabled again.
