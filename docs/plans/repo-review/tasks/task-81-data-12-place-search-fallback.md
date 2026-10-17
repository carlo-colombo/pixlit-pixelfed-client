# Task 81 — DATA-12: Stop the logged-out place-search fallback

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-22

## Problem
`PixelfedRepository.kt:257-258` does `val instanceUrl = tokenManager.instanceUrl ?: "https://pixelfed.social"`
and passes `accessToken?.let { "Bearer $it" }` as a nullable auth header, so a logged-out
`searchPlaces` sends an unauthenticated request to a third-party instance the user never chose; on
an instance without a public place index the result is an unexplained empty list, and the failure is
further masked by F-11.

## Scope
- `PixelfedRepository.kt:257-261`: return `Result.failure(...)` when `instanceUrl` or `accessToken` is
  absent, matching the other methods (`:187`, `:331`, `:543`), and remove the hard-coded
  `pixelfed.social` default.
- Drop the now-nullable `authHeader` parameter from `PixelfedApi.searchPlaces` / `searchHashtags` once
  SEC-04's `AuthInterceptor` lands (this item depends on it).
- Add a test asserting the logged-out call returns a failure and issues no request.

```kotlin
val instanceUrl = tokenManager.instanceUrl ?: return@withContext Result.failure(Exception("Not logged in"))
val accessToken = tokenManager.accessToken ?: return@withContext Result.failure(Exception("Not logged in"))
```

## Acceptance criteria
- [ ] No code path defaults an instance URL to `pixelfed.social`.
- [ ] A logged-out `searchPlaces` returns a failure result and performs zero HTTP calls.
- [ ] Authenticated place search behaviour is unchanged; existing repository tests stay green.

## Dependencies
- SEC-04

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests 'ovh.litapp.pixlit.data.repository.PixelfedRepositoryTest'`
