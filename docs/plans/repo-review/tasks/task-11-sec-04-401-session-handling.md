# Task 11 — SEC-04: Add 401 and session handling

**Wave 0 · Priority P1 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-06

## Problem
A repo-wide search for `401|Unauthorized|refresh_token|expires_in|Authenticator` over `app/src/main` returns zero matches. Every call maps a 401 to an opaque `Result.failure` (`PixelfedRepository.kt:479-481,552`); `getUserCollections` even returns fake data (`:349-352`). `TokenManager.isLoggedIn()` (`:63-65`) only checks that the string is non-blank, so a revoked token leaves the app "logged in" forever with no route back to `LoginScreen` because `MainViewModel.isLoggedIn` never flips (`MainViewModel.kt:27`).

## Scope
- Add an `AuthInterceptor` that attaches `Bearer` from `TokenManager` and emits an unauthorized signal when `response.code == 401`.
- Expose `SessionState { Authenticated, Expired, LoggedOut }` as a `StateFlow` from `TokenManager`.
- `MainViewModel`: collect `SessionState`, flip `isLoggedIn`, and route to `LoginScreen` with a "Session expired — sign in again" message; also own `accountId` caching (needed by DATA-02).
- `PixelfedApi.kt:23-101`: drop the `authHeader: String` parameter and the 9 hand-built `"Bearer $accessToken"` call sites (`PixelfedRepository.kt:205,221,234,261,337,377,451,501,548`).

```kotlin
class AuthInterceptor @Inject constructor(private val tokenManager: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer ${tokenManager.accessToken}").build()
        val response = chain.proceed(request)
        if (response.code == 401) tokenManager.onUnauthorized()
        return response
    }
}
```

## Acceptance criteria
- [ ] A 401 response flips `SessionState` to `Expired` and the UI routes to login.
- [ ] No `authHeader` parameter remains on the `PixelfedApi` interface.
- [ ] `isLoggedIn()` no longer returns `true` for an expired token.

## Dependencies
- None.

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest` (the `MockWebServer` 401 test lands with TEST-01)
