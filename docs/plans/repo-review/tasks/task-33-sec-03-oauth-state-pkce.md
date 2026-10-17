# Task 33 — SEC-03: Add OAuth state and PKCE

**Wave 2 · Priority P1 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-04

## Problem
The authorize URL carries only `client_id`, `redirect_uri`, `response_type` and `scope` (`LoginScreen.kt:65-68`) — no `state` and no PKCE — and the exported `BROWSABLE` deep link accepts any `code` (`MainViewModel.kt:73-77`; `AndroidManifest.xml:41-48`). Any app on the device can fire `pixlit://oauth?code=<attacker code>` and silently bind the session to the attacker's Pixelfed account (login CSRF / session fixation), and without PKCE an intercepted redirect is directly redeemable. `fetchAccessToken` (`PixelfedApi.kt:44-53`) currently has no `code_verifier` to send.

## Scope
- Generate a random `state` in `LoginScreen.kt` before launching the Custom Tab, persist it (e.g. via `TokenManager`), and append `state` to the authorize URL.
- Generate a PKCE verifier (43–128 chars), append `code_challenge` (S256) and `code_challenge_method=S256`, and pass `code_verifier` to `fetchAccessToken`.
- In `MainViewModel.handleIntent`, verify the returned `state` against the stored value before exchanging the code; consume it once.
- Treat a missing or mismatched `state` as an auth error on the existing `_authError` path — never exchange the code.
- Leave the `read write follow` scope string to SEC-06.

## Acceptance criteria
- [ ] The authorize URL contains `state`, `code_challenge` and `code_challenge_method=S256`.
- [ ] A redirect with an absent or wrong `state` shows an auth error and issues no token request.
- [ ] The token exchange includes `code_verifier`, and a successful login still completes.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest`
- Manual: `adb shell am start -a android.intent.action.VIEW -d "pixlit-dev://oauth?code=x"` shows the state-mismatch error and no `/oauth/token` call.

```kotlin
val state = UUID.randomUUID().toString()
tokenManager.pendingOAuthState = state
Uri.parse(formattedUrl).buildUpon()
    .appendPath("oauth").appendPath("authorize")
    .appendQueryParameter("state", state)
    .appendQueryParameter("code_challenge", s256(verifier))
    .appendQueryParameter("code_challenge_method", "S256")
    .build()
```
