# Task 38 — DATA-06: Split the 740-line repository along six seams

**Wave 3 · Priority P2 · Size L · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-12, F-15

## Problem
`PixelfedRepository` is 740 lines with 14 responsibilities: OAuth registration/token exchange
(`PixelfedRepository.kt:65-162`), multipart upload and temp-file copying (`:165-249, :727-740`),
place search (`:252-279`), asset JSON loading (`:286-306`), collections CRUD (`:327-399`), the
network+Room cache strategy (`:402-446`), pagination (`:492-518`), a SharedPreferences tag cache
(`:525-563`), cache introspection (`:567-588`) and the tag aggregators (`:595-720`). It also builds a
new `Retrofit` on every request via `getRetrofit(baseUrl)` (`:55-62`, invoked at 8 sites) — F-15.
Nothing here is testable without Robolectric and a real `Context` (F-19).

## Scope
- Step 1: extract `data/tags/TagAggregator.kt` (`:595-720`) — pure/static, import-only call-site change.
- Step 2: extract `data/auth/OAuthParsers.kt` (`:601-661`) — pure, keeps the existing tests green.
- Step 3: `data/session/SessionRepository.kt` (`registerApp`, `exchangeCodeForToken` + `TokenManager` writes, `:65-162`).
- Step 4: `data/media/MediaUploader.kt` (temp-file lifecycle, multipart, collection attach, `:165-249, :727-740`).
- Step 5: `data/status/StatusRemoteSource.kt` + `StatusCache` (Room read/write + mappers, `:402-518`).
- Step 6: `data/api/PixelfedApiFactory.kt` (`:55-62` + the 8 `getRetrofit().create()` sites) — `@Singleton`, one `PixelfedApi` cached per base URL.

```kotlin
@Singleton
class PixelfedApiFactory @Inject constructor(
    private val client: OkHttpClient, private val gson: Gson, private val tokenManager: TokenManager,
) {
    @Volatile private var cached: Pair<String, PixelfedApi>? = null
    fun get(): PixelfedApi { /* normalized base URL -> rebuild only on instance change */ }
}
```

## Acceptance criteria
- [ ] Each seam is an independently shippable commit; `PixelfedRepository` stays the injected facade.
- [ ] No `Retrofit`/Gson converter/API proxy is constructed per request after step 6.
- [ ] Steps 1–2 pass existing tests with import-only changes (no behaviour change).
- [ ] `TagAggregator`/`OAuthParsers` are unit-tested without Robolectric or `Context`.

## Dependencies
- DATA-04 (cache must persist `tags`/`media_attachments` + real `created_at` before the cache seam moves)

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest` and `./gradlew :app:assembleLocalDevDebug`
