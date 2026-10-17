# Task 20 — DATA-03: Add TTL and single-flight to status refresh

**Wave 1 · Priority P1 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-08

## Problem
The staleness guard at `PixelfedRepository.kt:414-419` is `forceRefresh || !tokenManager.isLoggedIn()`, but `isLoggedIn()` is exactly "accessToken non-blank && instanceUrl non-blank" and the branch is already gated on non-blank access token — so `!isLoggedIn()` is always false inside it. Non-forced loads can never refresh (the skip is even logged as an error at `:420`), while `UploadViewModel.kt:123,190` work around it by hard-coding `forceRefresh = true`, i.e. a full network fetch on every screen entry with no effective cache (04 F-08).

## Scope
- Replace the flag with a real TTL plus single-flight around `getUserTopTagsAndPosts` in `data/repository/PixelfedRepository.kt`: a `ttlMs` constant, a `Mutex`, and a `lastFetchAt` timestamp.
- Within TTL and `forceRefresh = false`, return the Room/static path without touching the network; a forced load or an expired TTL fetches and updates `lastFetchAt`.
- Hold the whole read-through-fetch in the mutex so concurrent callers (init + pull-to-refresh) share one in-flight fetch — one request and one `insertStatuses`.
- Demote the "skipping API fetch" log at `:420` from error to debug/absent.
- Add a JUnit test for the TTL predicate (force / fresh / expired).

```kotlin
private val ttlMs = 5 * 60_000L
private val refreshMutex = Mutex()
private var lastFetchAt = 0L

suspend fun getUserTopTagsAndPosts(forceRefresh: Boolean = false): Result<TagsAndPosts> =
    refreshMutex.withLock {
        if (!forceRefresh && System.currentTimeMillis() - lastFetchAt < ttlMs) fromCache()
        else fetchFromApi(...).onSuccess { lastFetchAt = System.currentTimeMillis() }
    }
```

## Acceptance criteria
- [ ] Two calls with `forceRefresh = false` inside the TTL issue zero HTTP requests.
- [ ] Two concurrent calls issue exactly one fetch and one `insertStatuses`.
- [ ] A forced call (or an expired TTL) refetches and advances `lastFetchAt`.
- [ ] The genuine-failure path still falls back to Room/static data (no fabricated empty result).
- [ ] Pull-to-refresh after the TTL performs exactly one network fetch.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest`
