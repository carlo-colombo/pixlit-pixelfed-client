# Task 19 — DATA-02: Cache the account id and parallelize bootstrap

**Wave 1 · Priority P1 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-07

## Problem
`verify_credentials` is re-fetched before nearly every operation: `PixelfedRepository.kt:448-460` (`fetchFromApi`), `:500-504` (`getMoreUserStatuses`, once per page) and `:336-339` (`getUserCollections`). Entering Upload therefore costs 4 sequential round-trips, two of them byte-identical (`UploadViewModel.kt:121-125`), even though `accountId` is stable for the whole session and could be resolved once (04 F-07).

## Scope
- Add an in-memory, session-scoped account-id cache to `data/repository/PixelfedRepository.kt`, keyed by `instanceUrl`, with one `suspend fun requireAccountId(api, authHeader): Result<String>`.
- Route `fetchFromApi`, `getMoreUserStatuses` and `getUserCollections` through `requireAccountId` instead of calling `verifyCredentials` inline.
- Run the independent bootstrap fetches concurrently at `ui/upload/UploadViewModel.kt:121-125` (`coroutineScope { async }`, or a repository-side `async`), so one Upload entry costs one round trip instead of two.
- Invalidate the cached id when the session changes (logout / session signal).
- Add a JUnit test in `app/src/test/java/ovh/litapp/pixlit/data/repository/PixelfedRepositoryTest.kt` for the cache behavior of the extracted id holder.

```kotlin
private val accountId = AtomicReference<String?>(null)
private suspend fun requireAccountId(api: PixelfedApi, authHeader: String): Result<String> =
    accountId.get()?.let { Result.success(it) }
        ?: api.verifyCredentials(authHeader).body()?.getIdString()
            ?.also { accountId.set(it) }?.let { Result.success(it) }
            ?: Result.failure(Exception("Failed to get account ID"))
```

## Acceptance criteria
- [ ] Within a session, `verify_credentials` is requested at most once per instance.
- [ ] "Load more" pages reuse the cached id and issue no credential request.
- [ ] Upload bootstrap runs the status and collection fetches concurrently.
- [ ] Logout clears the cached id so the next login re-resolves it.
- [ ] Failure to resolve the account id still reaches the existing `Result.failure` path (no fabricated id).

## Dependencies
- SEC-04 (the session signal owns the invalidation seam)

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest`
- Manual: `adb logcat` during one Upload entry — one `verify_credentials` request in the OkHttp log, not two.
