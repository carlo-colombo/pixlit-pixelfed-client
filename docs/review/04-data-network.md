I have all the evidence I need. Here is the complete artifact.

---

# Pixlit — Data Layer Review (networking, API, repository, DI, auth)

**Scope reviewed:** `data/repository/PixelfedRepository.kt` (740 lines), `data/api/PixelfedApi.kt`, `data/api/BlueskyApi.kt`, `data/repository/BlueskyArtShowRepository.kt`, `data/auth/TokenManager.kt`, `di/NetworkModule.kt`, `di/DatabaseModule.kt`, `data/db/{StatusEntity,StatusDao,AppDatabase}.kt`, `app/src/test/**` (PixelfedRepositoryTest, TokenManagerTest, BlueskyArtShowRepositoryTest), plus call sites (`MainViewModel`, `UploadViewModel`, `LoginScreen`) and build config (`app/build.gradle`, `proguard-rules.pro`, `AndroidManifest.xml`, `gradle/libs.versions.toml`).
**Blueprint reference:** `/home/carlo/projects/neurhome` (AGENTS.md + `data/` + `.github/workflows/build.yml`).

---

## Summary of what is sound (so it is not re-litigated below)

- Retrofit **suspend** functions are used throughout, so network I/O already leaves the main thread inside OkHttp; `withContext(Dispatchers.IO)` in the repository is redundant-but-harmless, and `BlueskyArtShowRepository` correctly omits it (`BlueskyArtShowRepository.kt:33-56`).
- The DTO layer is deliberately tolerant: ids are `JsonElement` (`PixelfedApi.kt:128-226`) with `toSafeString()` (`PixelfedApi.kt:110-125`), so a server that returns `"id": 495200482468784807` or `"id": "…"` parses either way. Unknown fields are tolerated (Gson default).
- Media descriptions come back **inline** in `media_attachments`, so there is *no* per-status N+1 for alt text (`PixelfedApi.kt:204-210`). The bottleneck is elsewhere (see F-07).
- `uploadMedia` multipart construction is correct: `@Part file` + `@Part("description") RequestBody` produces `Content-Disposition: form-data; name="description"`; `media_ids[]` as a repeated `@Field` matches the Pixelfed/Mastodon contract (`PixelfedApi.kt:73-99`).
- Token/registration/error body parsing is manual and defensive, and is genuinely unit-tested (`PixelfedRepositoryTest.kt:184-247`).

---

## Findings

### [SEVERITY: critical] F-01 — OAuth access token and client secret are stored in plaintext SharedPreferences **and** uploaded by Auto Backup

**Evidence**
- `data/auth/TokenManager.kt:11` — `private val prefs: SharedPreferences = context.getSharedPreferences("pixlit_prefs", Context.MODE_PRIVATE)`
- `data/auth/TokenManager.kt:47-49` and `:59-61` — `var accessToken` / `var clientSecret` stored verbatim into those prefs.
- `app/src/main/AndroidManifest.xml:14` — `android:allowBackup="true"`, and there is **no** `android:dataExtractionRules` / `android:fullBackupContent` attribute. `find app/src/main/res/xml` returns nothing.

**Impact** Every login writes a long-lived Pixelfed bearer token plus the instance `client_secret` to a world-of-the-app readable XML file that Android Auto Backup copies to the user's Google Drive (`allowBackup` defaults to including `sharedpref`). A rooted device, an `adb backup` on an unlocked device, or a restored backup on another device leaks a token that grants full `read write follow` posting rights to the account. Since `minSdk 34` there is no excuse not to encrypt.

**Recommendation** Two independent fixes, both small:

1. Encrypt the secret material. Add `androidx.security:security-crypto` and put only the token/secret behind it (leave cosmetic prefs as-is):

```kotlin
@Singleton
class TokenStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "pixlit_secure", MasterKey.Builder(context).setKeyScheme(AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    var accessToken: String? ...
}
```

2. Exclude the file from backup regardless (`app/src/main/res/xml/data_extraction_rules.xml`), mirroring neurhome's rule-XML discipline:

```xml
<data-extraction-rules>
  <cloud-backup><exclude domain="sharedpref" path="pixlit_prefs.xml" /></cloud-backup>
  <device-transfer><exclude domain="sharedpref" path="pixlit_prefs.xml" /></device-transfer>
</data-extraction-rules>
```

**Blueprint reference** neurhome treats backup surfaces as a first-class, test-pinned concern: `app/src/main/res/xml/data_extraction_rules.xml` plus `BackupRulesTest.kt`, which asserts the rule shape on every `testDebugUnitTest` run ("Cloud backup REQUIRES client-side encryption", AGENTS.md → *Backup Policy*). Mirror that with a `PrefsBackupExclusionTest`.

---

### [SEVERITY: critical] F-02 — Bearer tokens and full image bodies are written to logcat in **release** builds

**Evidence**
- `di/NetworkModule.kt:31-38`:
```kotlin
val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
return OkHttpClient.Builder().addInterceptor(logging).build()
```
- This client is a `@Singleton` injected into `PixelfedRepository` (`PixelfedRepository.kt:47`), so **every** call carries `Authorization: Bearer …` (9 call sites: `PixelfedRepository.kt:205, 221, 234, 261, 337, 377, 451, 501, 548`).

**Impact** Two problems at once. (a) Security: `Level.BODY` logs request headers, i.e. the long-lived access token, in clear text on every request, and `BODY` is unconditional — nothing gates it on `BuildConfig.DEBUG`. (b) Performance: for `POST api/v1/media` the interceptor buffers and hex-dumps the entire multipart body, so each upload of up to 6 × 8 MB is copied into memory and stringified into logcat — measurable jank/GC and a real OOM risk on low-RAM devices, on the very path the product exists for.

**Recommendation**
```kotlin
val logging = HttpLoggingInterceptor().apply {
    level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
    redactHeader("Authorization")
}
```
`BASIC` (never `BODY`) is enough for this app, and `redactHeader` keeps the token out of debug logs too. Also enable `buildFeatures { buildConfig = true }` — the module currently does not set it (`app/build.gradle:63-65` sets only `compose = true`).

**Blueprint reference** neurhome keeps logcat capture in the debug source set only and explicitly forbids moving it behind production code paths (`DebugLogcatCaptureProvider` in `src/debug/AndroidManifest.xml`, AGENTS.md → *Architecture*). Same principle: diagnostics must not be compiled into the prod variant.

---

### [SEVERITY: high] F-03 — The app parses a 24 128-line asset JSON on the main thread, on every Upload-screen creation

**Evidence**
- `app/src/main/assets/pixelfed-statuses.json` — 24 128 lines (verified via read: `[24123 more lines in file]`).
- `PixelfedRepository.kt:286-306` — `fun getStaticStatuses()` is **not** `suspend`, does `context.assets.open(...).bufferedReader().readText()` and `Gson().fromJson(jsonString, listType)` inline; it also news up a **fresh `Gson()` per call** (`:300`) instead of the injected singleton.
- `PixelfedRepository.kt:314-316` — `getDefaultStaticTagCounts()` → `extractTopTagCountsFromStatuses(getStaticStatuses(), …)`.
- `ui/upload/UploadViewModel.kt:58` — `private val _topTags = MutableStateFlow(repository.getDefaultStaticTagCounts())`, a **property initializer**, therefore executed synchronously on the main thread while the ViewModel is constructed.
- Two more main-thread call sites: `UploadViewModel.kt:345` and `:354` — `viewModelScope.launch { … repository.getDefaultStaticTagCounts() }` runs on `Dispatchers.Main.immediate` and calls a non-suspend function that reads and parses the whole asset again.

**Impact** Every navigation to the Upload destination pays a full asset read + Gson parse + regex tag scan over 24 k statuses on the UI thread — classic ANR/jank on cold start and on the fallback path, which is exactly when the network already failed (worst possible moment). It is repeated work: after line 58, `init` immediately calls `fetchTags(forceRefresh = true)` (`UploadViewModel.kt:123`), which will overwrite `_topTags` anyway.

**Recommendation** Make it lazy, off-main, and memoized once:

```kotlin
private val staticStatuses: List<StatusItem> by lazy {
    runCatching {
        context.assets.open("pixelfed-statuses.json").use { gson.fromJson(it.reader(), STATUS_LIST_TYPE) }
    }.getOrDefault(emptyList())
}
suspend fun getDefaultStaticTagCounts(): List<TagCount> = withContext(Dispatchers.Default) {
    extractTopTagCountsFromStatuses(staticStatuses, MAX_DISPLAYED_TAGS)
}
```
and in the ViewModel start `_topTags` empty (`MutableStateFlow(emptyList())`), filling it from the `init` fetch or from a `viewModelScope.launch`. Use the injected `gson`, and hoist `TypeToken<List<StatusItem>>` into a `val`.

---

### [SEVERITY: high] F-04 — OAuth flow has no `state` and no PKCE; the deep-link handler accepts any `code`

**Evidence**
- `ui/auth/LoginScreen.kt:65-68` builds the authorize URL with only `client_id`, `redirect_uri`, `response_type`, `scope` — no `state`.
- `MainViewModel.kt:73-77`:
```kotlin
if (uri != null && uri.scheme == scheme && uri.host == "oauth") {
    …
    val code = uri.getQueryParameter("code")
```
The intent filter is `BROWSABLE` (`AndroidManifest.xml:41-48`), so any app on the device can fire `pixlit://oauth?code=<attacker code>`.

**Impact** Two concrete attacks. (1) The code is exchanged with no provenance check, so a malicious app can inject *its own* authorization code and have the user's device silently bind to the attacker's Pixelfed account (session fixation / login CSRF) — subsequent uploads go to the wrong account. (2) Without PKCE, an intercepted redirect (custom-scheme hijack by another app registering `pixlit`) is directly redeemable.

**Recommendation** Add both; both are ~15 lines:

```kotlin
// LoginScreen
val state = UUID.randomUUID().toString()
val verifier = randomPkceVerifier()                     // 43-128 chars
tokenManager.pendingOAuthState = state
tokenManager.pendingCodeVerifier = verifier
… .appendQueryParameter("state", state)
  .appendQueryParameter("code_challenge", s256(verifier))
  .appendQueryParameter("code_challenge_method", "S256")

// MainViewModel.handleIntent
if (uri.getQueryParameter("state") != tokenManager.consumePendingOAuthState()) {
    _authError.value = "OAuth state mismatch — login aborted"; return
}
```
Pass `code_verifier` in `fetchAccessToken` (`PixelfedApi.kt:44-53`). Note the current `registerApp` scopes (`read write follow`, `PixelfedRepository.kt:80`) already request more than an uploader needs — consider `read write`.

---

### [SEVERITY: high] F-05 — Uploads run in `viewModelScope`: no WorkManager, no retry, no idempotency — a post can be lost or duplicated

**Evidence**
- `ui/upload/UploadViewModel.kt:537` — `viewModelScope.launch { … repository.uploadPhotosAndCreateStatus(…) }`; `viewModelScope` is cancelled when the Upload destination leaves composition.
- `PixelfedRepository.kt:193-243` — sequential per-image `api.uploadMedia` then one `api.createStatus`, with no persisted job state, no `Idempotency-Key`, no partial-progress short-circuit.
- The project already has WorkManager wired up (`app/build.gradle:116-118`, `androidx.hilt.work`, and the default initializer is removed in `AndroidManifest.xml:91-99`) but it is used only for reminders (`data/reminder/ArtShowReminderWorker.kt`).

**Impact** For an app whose entire purpose is uploading: back-press, rotation-to-background, or process death mid-upload silently drops the post (and any already-uploaded media become orphans on the server). Conversely, if `createStatus` succeeds server-side but the response is lost (mobile network), a user retry creates a **duplicate post** with **freshly re-uploaded media**. There is no way for the user to tell which happened.

**Recommendation** Move the upload into a `@HiltWorker` with a foreground notification, retry/backoff, and an idempotency key:

```kotlin
val request = api.createStatus(headers = mapOf("Idempotency-Key" to uploadJob.id), …)  // add @HeaderMap
```
Make `createStatus` accept a stable key generated once per post (persist it in the job/`Room` row). Emit per-photo progress to a `Flow` the UI collects. Keep `uploadPhotosAndCreateStatus` as the single seam the worker calls, so the ViewModel only enqueues.

**Blueprint reference** neurhome's rule is that anything that must survive the process is not started from a ViewModel scope; its scheduling/one-shot work lives in the data layer behind a repository seam (AGENTS.md → *Database*: "`LogRetention` runs at most one pass a day on IO from `NeurhomeApplication`").

---

### [SEVERITY: high] F-06 — No 401 handling anywhere: a dead token looks like "Not logged in"/generic HTTP errors, with no re-auth path

**Evidence** A repo-wide search for `401|Unauthorized|refresh_token|expires_in|Authenticator` over `app/src/main` returns **zero** matches. Every call maps a 401 to an opaque failure string, e.g.:
- `PixelfedRepository.kt:479-481` — `Result.failure(Exception("Failed to verify credentials: ${verifyResponse.code()}"))`
- `PixelfedRepository.kt:552` — `Result.failure(Exception("Tag search failed: ${response.code()}"))`
- `getUserCollections` swallows it entirely and returns fake collections (`:349-352`).
- `TokenManager.isLoggedIn()` (`TokenManager.kt:63-65`) only checks that a string is non-blank, so it keeps returning `true` for an expired/revoked token.

**Impact** When the token is revoked (user logs out elsewhere, instance rotates keys, app is restored from backup — see F-01), the app stays "logged in" forever: every screen degrades to static placeholder data with no explanation, and the user has no in-app path back to `LoginScreen` because `MainViewModel.isLoggedIn` never flips (`MainViewModel.kt:27`). Silent, unrecoverable-from-UI failure state.

**Recommendation** Centralise auth in an interceptor + a session signal rather than per-call headers:

```kotlin
class AuthInterceptor @Inject constructor(private val tokenManager: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer ${tokenManager.accessToken}").build()
        val response = chain.proceed(request)
        if (response.code == 401) onUnauthorized()   // emit to a SessionState flow
        return response
    }
}
```
Expose `SessionState { Authenticated, Expired, LoggedOut }` from `TokenManager` as a `StateFlow`, have `MainViewModel` collect it and route to `LoginScreen` with "Session expired — please sign in again". Drop `authHeader: String` from every `PixelfedApi` method (`PixelfedApi.kt:23-101`) — 9 call sites currently construct `"Bearer $accessToken"` by hand (`PixelfedRepository.kt:205, 221, 234, 261, 337, 377, 451, 501, 548`).

---

### [SEVERITY: high] F-07 — `verify_credentials` is re-fetched before nearly every operation; entering the Upload screen costs 4 HTTP calls, 2 of them identical

**Evidence**
- `PixelfedRepository.kt:448-460` (`fetchFromApi`): `verifyCredentials` → `getUserStatuses`.
- `PixelfedRepository.kt:500-504` (`getMoreUserStatuses`): `api.verifyCredentials(authHeader).body()?.getIdString()` **again for every page**.
- `PixelfedRepository.kt:336-339` (`getUserCollections`): `verifyCredentials` → `getUserCollections`.
- `ui/upload/UploadViewModel.kt:121-125`:
```kotlin
init {
    fetchTags(forceRefresh = true)   // → verify_credentials + statuses
    fetchCollections()               // → verify_credentials + collections
    refreshCacheSummary()
}
```

**Impact** 4 sequential round-trips before the Upload screen is usable, two of which are byte-identical `verify_credentials` calls; each subsequent "load more" page adds a third redundant request (`UploadViewModel.kt:372`). `accountId` is never memoized, even though it is stable for the session. On a mobile link at ~150 ms RTT this is ~600 ms of pure scaffolding latency on every screen entry, and it doubles the chance of hitting a failure.

**Recommendation** Cache the account id once per session (in-memory, keyed by instance) and run independent requests concurrently:

```kotlin
private val accountId = AtomicReference<String?>(null)
private suspend fun requireAccountId(api: PixelfedApi): Result<String> =
    accountId.get()?.let { Result.success(it) } ?: api.verifyCredentials(…).let { … also { accountId.set(id) } }

// UploadScreen bootstrap: one round trip, not two
val (statuses, collections) = coroutineScope {
    val s = async { statusesSource.load() }
    val c = async { collectionsSource.load() }
    s.await() to c.await()
}
```
Invalidate `accountId` on logout (F-06's session signal) — that gives you the cache-invalidation seam for free.

---

### [SEVERITY: high] F-08 — The "refresh if stale" condition is self-contradictory, so non-forced loads can never refresh

**Evidence** `PixelfedRepository.kt:414-419`:
```kotlin
val apiResult = if (!accessToken.isNullOrBlank() && (forceRefresh || !tokenManager.isLoggedIn())) {
    fetchFromApi(instanceUrl, accessToken)
} else {
    Result.failure(Exception("Skipping API fetch"))
}
```
`tokenManager.isLoggedIn()` is exactly "`accessToken` non-blank && `instanceUrl` non-blank", so inside the `!accessToken.isNullOrBlank()` branch `!isLoggedIn()` is always `false`. The comment above it (`:413`, "or we don't have enough data") describes a staleness check that does not exist.

**Impact** `getUserTopTagsAndPosts(forceRefresh = false)` — the default — **always** takes the `Room`/static path. Any caller that does not pass `forceRefresh = true` shows stale-or-placeholder data with no network fallback, and the "skip" is logged as an error (`:420`), polluting crash logs with a non-error. Today `UploadViewModel` sidesteps this by hard-coding `forceRefresh = true` (`:123`, `:190`), which is the opposite failure: a full network refresh on every screen entry with no TTL, i.e. no effective cache.

**Recommendation** Replace the flag with a real TTL + single-flight, which fixes both halves:

```kotlin
private val ttlMs = 5 * 60_000L
private val refreshMutex = Mutex()
private var lastFetchAt = 0L

suspend fun getUserTopTagsAndPosts(forceRefresh: Boolean = false): Result<TagsAndPosts> =
    refreshMutex.withLock {
        if (!forceRefresh && System.currentTimeMillis() - lastFetchAt < ttlMs) return@withLock fromCache()
        fetchFromApi(...).onSuccess { lastFetchAt = System.currentTimeMillis() }
    }
```
The mutex also gives you in-flight de-duplication (two concurrent callers — e.g. `init` and pull-to-refresh — currently issue two identical status fetches and two `insertStatuses`).

---

### [SEVERITY: high] F-09 — No upload-appropriate timeouts on the shared OkHttp client

**Evidence** `di/NetworkModule.kt:36-38` — `OkHttpClient.Builder().addInterceptor(logging).build()`: no `connectTimeout`, `writeTimeout`, `readTimeout`, or `callTimeout` anywhere in the module, and the repository builds its Retrofit from this client (`PixelfedRepository.kt:55-62`). The only timeout in the app is a one-off coroutine timeout for place search (`:262`, `withTimeoutOrNull(8_000L)`).

**Impact** With OkHttp's stock 10 s write/read budget, a 6-photo post (up to 6 × 8 MB, `ImageUtils.MAX_BYTES_8MB`) on a typical mobile uplink will trip `SocketTimeoutException` mid-upload long before the server responds — and the failure lands as an opaque `Result.failure(e)` from `PixelfedRepository.kt:247`, after some media have already been uploaded (F-05). Meanwhile place search *is* bounded, so the timeouts are inconsistent in both directions.

**Recommendation** Set explicit budgets on the shared client, and prefer `callTimeout` over ad-hoc `withTimeoutOrNull` so cancellation is propagated to the OkHttp call:

```kotlin
OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)   // large multipart uploads
    .callTimeout(180, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()
```
(Confirm the exact defaults against the pinned `okhttp = "5.5.0"` — `gradle/libs.versions.toml:12`.) Then delete the `withTimeoutOrNull` special case at `PixelfedRepository.kt:262` in favour of a per-call `@Headers`/tag timeout, or keep it and document why.

---

### [SEVERITY: high] F-10 — The Room cache is lossy: cached statuses lose `tags` and `media_attachments`, so cached tag rankings differ from live ones

**Evidence**
- `PixelfedRepository.kt:422-430` — the `StatusEntity` → `StatusItem` mapping only restores `id`, `content`, `text`, `description`:
```kotlin
StatusItem(
    id = com.google.gson.JsonPrimitive(entity.id),
    content = entity.content, text = entity.text, description = entity.description
)
```
- `StatusEntity.kt:8-16` has no `tags` and no `media_attachments` columns at all.
- The live path uses both: `extractTopTagCountsFromStatuses` reads `status.tags` and `status.mediaAttachments` (`PixelfedRepository.kt:701-706`).
- `StatusEntity.createdAt` is written as `System.currentTimeMillis()` at *insert* time (`:469`, `:512`), not the status's own `created_at`, and `OnConflictStrategy.REPLACE` (`StatusDao.kt:18`) rewrites it on every refetch.

**Impact** The tag cloud — the app's primary helper for building a caption — silently changes content depending on whether the data came from the network or the cache: tags that only exist in `tags[]` or in media alt text vanish (`PixelfedRepositoryTest.kt:44-62` proves the live path *does* read media descriptions). Ordering is also non-deterministic: `ORDER BY createdAt DESC` (`StatusDao.kt:11`) sorts by fetch time, so a REPLACE re-fetch reshuffles "recent posts".

**Recommendation** Persist what the aggregator consumes, and sort by the real timestamp:

```kotlin
@Entity(tableName = "statuses", indices = [Index("instanceUrl")])
data class StatusEntity(
    @PrimaryKey val id: String,
    …, val createdAt: Long,     // ← from status.created_at, not now()
    val instanceUrl: String,
    val tagsJson: String?,      // gson of List<TagItem>
    val mediaDescriptionsJson: String?,
)
```
Add a `toEntity()`/`toItem()` mapper pair in one file and unit-test the round trip (see F-19). Also: `SELECT *` unbounded (`StatusDao.kt:11-14`) plus no retention means the table grows forever; add `LIMIT :limit` and a prune call in `insertStatuses`.

---

### [SEVERITY: high] F-11 — Results are swallowed: the tag/status API reports `Result.success` unconditionally, and failures are rendered as empty data

**Evidence**
- `PixelfedRepository.kt:445` — `getUserTopTagsAndPosts` ends with `Result.success(TagsAndPosts(...))`; the *only* `failure` is `instanceUrl == null` (`:406-408`). So `result.fold(onFailure = …)` in `UploadViewModel.kt:351-356` is dead code, and genuine network errors are indistinguishable from success.
- `PixelfedRepository.kt:271-273` — `searchPlaces` returns `Result.success(emptyList())` for any non-2xx:
```kotlin
if (response.isSuccessful && response.body() != null) { Result.success(response.body()!!) }
else { Result.success(emptyList()) }
```
- `PixelfedRepository.kt:349-352` and `:390-398` — `getUserCollections`/`createCollection` convert *any* exception or HTTP error into `Result.success` of fabricated local data (`id = JsonPrimitive("local_${System.currentTimeMillis()}")`).
- `PixelfedRepository.kt:238-240` — collection assignment failures are logged and dropped, yet `UploadViewModel.kt:558-561` reports "and added to N collection(s)".

**Impact** Every domain error is laundered into "no results" or "success". A user whose instance returns 500 sees an empty place list; a user whose collection creation failed sees the collection in the list, assigns an upload to it, gets a silent 404 on `addStatusToCollection` (invalid `local_…` id), and is told the upload went into that collection. There is no telemetry or user-visible signal to distinguish "nothing found" from "broken".

**Recommendation** Model the three outcomes explicitly and let only *fallback-data* be silent, never *the error*:

```kotlin
sealed interface Fetch<T> {
    data class Fresh<T>(val value: T) : Fetch<T>
    data class Cached<T>(val value: T, val cause: Throwable) : Fetch<T>  // stale-but-usable
    data class Failed(val cause: Throwable) : Fetch<Nothing>
}
```
Map HTTP codes to typed causes in one place (`HttpException`/`PixelfedError(code, parsedMessage)`) instead of `Exception("Tag search failed: 404")`, and surface `Cached(cause)` as a subtle "showing cached data — refresh failed" affordance. For collections, either fail honestly or mark the item `isLocal = true` and exclude it from `addStatusToCollection`. Same for the silent registration failure at `LoginScreen.kt:50-57`, where a failed `registerApp` produces no message at all (`clientIdToUse = null`, comments only).

---

### [SEVERITY: medium] F-12 — `PixelfedRepository` is 740 lines and 14 responsibilities; concrete seams below, ordered by churn

**Evidence** One class contains: OAuth app registration and token exchange (`:65-162`), multipart media upload and post creation (`:165-249`), temp-file copying (`:727-740`), place search (`:252-279`), asset JSON loading (`:286-306`), static fake collections (`:318-325`), collections CRUD (`:327-399`), a network+Room cache strategy (`:402-446`), pagination (`:492-518`), a SharedPreferences JSON tag-search cache (`:525-563`), cache introspection (`:567-588`), tag aggregation as a companion-object static utility (`:595-720`), and OAuth/error body parsing (`:601-661`). It constructs its own `Retrofit` per call (`:55-62`) and mutates auth state (`:88-90`, `:141`).

**Impact** Any change to one concern (e.g. adding `Idempotency-Key`) touches a file where auth, caching, parsing, and upload all live; nothing here is testable without Robolectric + a real `Context` (see F-19); and the class cannot be split by build variant or lazily loaded.

**Recommendation** Keep `PixelfedRepository` as a thin facade and move behaviour out — the smallest churn order is bottom-up, each step independently shippable:

| Step | Extract to | Lines moved | Why first |
|---|---|---|---|
| 1 | `data/tags/TagAggregator.kt` (object; `extractTopTagCountsFromStatuses`, `extractTagsFromPostsText`) | `:595-720` | Already pure/static; zero call-site change beyond the import |
| 2 | `data/auth/OAuthParsers.kt` (`parseTokenResponseBody`, `parseRegistrationResponseBody`, `parseErrorResponseBody`) | `:601-661` | Pure; keeps the existing tests green with an import change |
| 3 | `data/session/SessionRepository.kt` (`registerApp`, `exchangeCodeForToken` + the `TokenManager` writes) | `:65-162` | Single owner of auth state — the seam F-06 needs |
| 4 | `data/media/MediaUploader.kt` (temp-file lifecycle, multipart, collection attach) | `:165-249, 727-740` | The seam F-05's worker needs |
| 5 | `data/status/StatusRemoteSource.kt` + `StatusCache` (Room read/write + mappers) | `:402-518` | Kills the mixed network/cache logic of F-08/F-10 |
| 6 | `data/api/PixelfedApiFactory.kt` | `:55-62` + 8 `getRetrofit().create()` sites | Removes per-request Retrofit construction (F-15) |

```kotlin
@Singleton
class PixelfedApiFactory @Inject constructor(
    private val client: OkHttpClient, private val gson: Gson, private val tokenManager: TokenManager,
) {
    @Volatile private var cached: Pair<String, PixelfedApi>? = null
    fun get(): PixelfedApi {
        val base = tokenManager.instanceUrl!!.let { if (it.endsWith("/")) it else "$it/" }
        return cached?.takeIf { it.first == base }?.second ?: run {
            val api = Retrofit.Builder().baseUrl(base).client(client)
                .addConverterFactory(GsonConverterFactory.create(gson)).build()
                .create(PixelfedApi::class.java)
            cached = base to api; api
        }
    }
}
```

**Blueprint reference** neurhome already uses exactly this shape and documents it as load-bearing: *"`NeurhomeRepository` is the read model over the app data and the facade the ViewModels inject. It delegates the per-concern reads and writes to five collaborators — `LauncherAppsSource`, `HomeRankingCoordinator`, `LaunchLogRepository`, `ApplicationMetadataRepository`, `DatabaseExporter`"* (neurhome `AGENTS.md` → *Architecture*). Its `WeatherRepository` shows the companion testability rule: a `WeatherService` **interface** + `WeatherServiceImpl`, with the repository only mapping to `Result` (`data/weather/WeatherRepository.kt:37-56`).

---

### [SEVERITY: medium] F-13 — Temp files from every upload are never deleted

**Evidence**
- `PixelfedRepository.kt:727-740` — `getFileFromUri` creates `File.createTempFile("upload_", ".jpg", context.cacheDir)` and never deletes it.
- `PixelfedRepository.kt:194-199` — the file returned by `getFileFromUri` **or** by `ImageUtils.resizeImageDownToMaxBytes` is used for the multipart body and then dropped. `ImageUtils` cleans up its own intermediate copy (`utils/ImageUtils.kt:150-159`) but returns `resized_upload_*.jpg` (`:310`) for the caller to own.
- No `delete()` anywhere in the upload path.

**Impact** Up to 6 × 8 MB = ~48 MB of dead JPEGs per post accumulate in `cacheDir`, persisting until the OS reclaims cache under pressure. On a photo-upload app used daily, that is hundreds of MB of churn, extra `fsync`/IO, and a `DiskFull`-class failure mode for a feature unrelated to uploads.

**Recommendation** Own the lifecycle with `use`-style cleanup around the upload loop:

```kotlin
for (uri in imageUris) {
    val file = (if (resizeTo8Mb) ImageUtils.resizeImageDownToMaxBytes(…) else getFileFromUri(uri))
        ?: return@withContext Result.failure(…)
    try {
        … uploadMedia(MultipartBody.Part.createFormData("file", file.name, file.asRequestBody(…)))
    } finally {
        file.delete()
    }
}
```
Also give the temp file its real extension — `createTempFile("upload_", ".jpg")` forces `.jpg` on PNG/WebP/HEIC originals (`:731`), which is a `Content-Type`/filename mismatch on the wire.

---

### [SEVERITY: medium] F-14 — `getCacheSummary()` loads every cached status row just to count them, and is called after every tag search

**Evidence**
- `PixelfedRepository.kt:578-580` — `tokenManager.instanceUrl?.let { statusDao.getStatuses(it).size } ?: 0` — full `SELECT *` materialised into `List<StatusEntity>` to read `.size`.
- `ui/upload/UploadViewModel.kt:453-460` — `searchTagSuggestions` calls `refreshCacheSummary()` after **every** debounced search, which serialises and re-parses two JSON blobs and scans the status table.

**Impact** O(n) allocation per keystroke-debounced search, growing with the unbounded cache of F-10. Wasted IO and garbage on a path the user triggers by typing.

**Recommendation** Add a projection query and drop the eager refresh:

```kotlin
@Query("SELECT COUNT(*) FROM statuses WHERE instanceUrl = :instanceUrl")
suspend fun countStatuses(instanceUrl: String): Int
```
and refresh the summary only when the cache panel is opened (or on a `Flow` of `countStatuses`), not on every suggestion lookup.

---

### [SEVERITY: medium] F-15 — A new `Retrofit` + Gson converter + API proxy is built for every single request

**Evidence** `PixelfedRepository.kt:55-62` defines `getRetrofit(baseUrl)`, and it is invoked inline at 8 sites: `:77, :128, :190, :260, :336, :376, :450, :500, :546`. `di/NetworkModule.kt` provides no `PixelfedApi` (only `BlueskyApi`, `:41-49`), because the base URL is runtime state.

**Impact** Each call re-runs Retrofit's method-annotation parsing/caching setup and allocates a fresh dynamic proxy — pure overhead on every request, including every pagination step, and it leaves no natural home for the auth interceptor of F-06 or for a `MockWebServer` base URL in tests (F-19).

**Recommendation** See the `PixelfedApiFactory` in F-12 step 6 — a `@Singleton` caching one `PixelfedApi` per base URL (invalidate on instance change). This simultaneously removes 8 duplicated construction sites and enables F-06's interceptor.

---

### [SEVERITY: medium] F-16 — The caption is sent as the alt `description` of *every* image, with no length guard

**Evidence** `PixelfedRepository.kt:203-209`:
```kotlin
val descRequestBody = caption.toRequestBody("text/plain".toMediaTypeOrNull())
val mediaResponse = api.uploadMedia(…, description = descRequestBody)
```
inside the per-image loop, so each of up to 6 photos receives the identical full caption (hashtags, location block appended at `UploadViewModel.kt:543-546`) as its alt text. There is no length validation anywhere (`grep maxLength|take(1000)|2000` finds nothing in the upload path).

**Impact** Two issues. Semantics: alt text becomes a duplicate caption instead of a per-image description, which is a real accessibility and Pixelfed/Bluesky-culture faux pas — and it means `StatusItem.mediaAttachments[].description` feeding the tag aggregator (`PixelfedRepository.kt:701-706`) just re-counts the caption's own hashtags. Robustness: Pixelfed validates media `description` length; an over-long caption turns `POST api/v1/media` into a 4xx and the whole post fails with `"Media upload failed: 422 …"` (`:211`) — before any status exists, leaving orphaned media.

**Recommendation** Send `description` only when the user actually provides per-image alt text (add an optional per-photo alt field in the UI), and clamp defensively:

```kotlin
val alt = altTexts[uri]?.trim()?.take(MAX_ALT_CHARS)?.takeIf { it.isNotEmpty() }
val descRequestBody = alt?.toRequestBody("text/plain".toMediaTypeOrNull())
```
Verify the exact server limit against `https://docs.pixelfed.org/technical-documentation/api/` for the target instance and pin it in a named constant.

---

### [SEVERITY: medium] F-17 — The tag-search cache lives in SharedPreferences: unbounded, no TTL, non-atomic read-modify-write

**Evidence**
- `PixelfedRepository.kt:528-541` — reads the whole map from `tokenManager.tagSearchCacheJson`, then `:558-559` writes back `gson.toJson(cache, cacheType)`.
- `TokenManager.kt:36-41` — `prefs.edit().putString(KEY_TAG_SEARCH_CACHE, value).commit()` (synchronous, blocking `commit()`).
- Keys are `${instanceUrl}|$query` and entries are never evicted; cleared only by `clear()` on logout (`TokenManager.kt:68-70`).
- `TokenManager.kt:23-34` — `cachedTagsJson` / `tagsCacheTime` are **read** (`PixelfedRepository.kt:573`, `:29-34`) but never written anywhere, so the "Legacy cached tags" row always reports 0 (`:581`).

**Impact** The whole map is deserialised and re-serialised on every cache hit/miss (O(cache size) per keystroke-debounced search), written with a blocking `commit()`, grows without bound, and can lose entries when two searches interleave (read-modify-write on a `String`, not a transaction). Storing a JSON blob inside a preferences file also makes each write rewrite the entire file that holds the access token.

**Recommendation** Move the cache to Room (it is relational data) with a TTL column, or at minimum make it bounded and atomic:

```kotlin
@Query("SELECT namesJson FROM tag_search_cache WHERE cacheKey = :key AND fetchedAt > :minFetchedAt")
suspend fun get(key: String, minFetchedAt: Long): String?
@Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(entry: TagSearchCacheEntity)
```
Delete the never-written `cachedTagsJson`/`tagsCacheTime` keys and the "Legacy cached tags" row rather than keeping a permanently-zero diagnostic.

---

### [SEVERITY: medium] F-18 — DTO nullability and JSON tolerance are unverified; Gson gives no compile-time safety here

**Evidence**
- `di/NetworkModule.kt:25-27` — `GsonBuilder().setStrictness(Strictness.LENIENT)`, i.e. the parser is configured to *accept* malformed JSON rather than reject it.
- Non-null Kotlin collection properties with defaults that Gson can populate with `null` (Gson instantiates via `Unsafe`, so constructor defaults are bypassed): `PixelfedApi.kt:194-196` `HashtagSearchResponse.hashtags: List<TagItem> = emptyList()`, `BlueskyApi.kt:16` `BlueskyPosts.posts: List<BlueskyPost> = emptyList()`. Consumers survive only because they happen to use `?.`/`.orEmpty()` (`PixelfedRepository.kt:555`, `BlueskyArtShowRepository.kt:40`); the next consumer to write `body.posts.size` gets a raw NPE.
- Non-null assertions on bodies: `PixelfedRepository.kt:87, 130, 271, 380` (`response.body()!!`), guarded today but one refactor away from an NPE.
- There is **no test at all** that parses a recorded server payload into these DTOs; `PixelfedRepositoryTest` only exercises hand-built `StatusItem`s and the three body parsers.

**Impact** The API contract is effectively untested. A field rename or a v2 response-shape change on some Pixelfed version degrades silently into empty tag clouds and "Unknown Place" instead of a visible error — indistinguishable from F-11's "no results".

**Recommendation** Make the nullable boundary explicit and pin it with fixtures:

```kotlin
data class HashtagSearchResponse(@SerializedName("hashtags") val hashtags: List<TagItem>? = null)
data class BlueskyPosts(val posts: List<BlueskyPost>? = null)
```
and add `app/src/test/resources/fixtures/{verify_credentials,statuses,media,search_v2,places_search,collections}.json` with parsing tests:

```kotlin
@Test fun `statuses fixture parses media descriptions and tags`() {
    val body = gson.fromJson(fixture("statuses.json"), Array<StatusItem>::class.java)
    assertEquals(listOf("#design"), body.first().mediaAttachments!!.map { it.description })
}
```
That also nails down the two endpoints whose response shape is currently assumed rather than verified: `@GET("api/v1/places/search")` returning a bare `Response<List<PlaceItem>>` (`PixelfedApi.kt:81-88`) and `@GET("api/v2/search")` returning `HashtagSearchResponse` (`:90-96`) — both silently masked by F-11 if the shape differs.

---

### [SEVERITY: medium] F-19 — Test gaps: the entire network/auth/cache layer is untested; no `MockWebServer`, no `room-testing`

**Evidence**
- `PixelfedRepositoryTest.kt` (18 tests) covers only companion pure functions plus a *simulated* list-shift (`:250-300`) that tests the test, not production code.
- `TokenManagerTest.kt` is a Robolectric round-trip of SharedPreferences getters/setters — it asserts nothing about expiry, concurrency, or encryption.
- `BlueskyArtShowRepositoryTest.kt` covers `parseWeeklyChallenge`/`atUriToPostUrl`/`parseBlueSkyArtShowTheme` well; `fetchTheme`/`fetchWeeklyChallenge` (the network orchestration at `BlueskyArtShowRepository.kt:33-56`) are untested.
- `gradle/libs.versions.toml` has `mockk`, `turbine`, `kotlinx-coroutines-test` but **no** `okhttp.mockwebserver` and **no** `androidx.room:room-testing`.
- The repository's constructor takes `OkHttpClient`, `Gson`, `StatusDao`, `Context` and builds Retrofit internally (`PixelfedRepository.kt:44-53`) — there is no injection point to substitute a fake server.

**Impact** None of the highest-risk logic found above (F-08's contradiction, F-10's lossy mapping, F-11's swallowing, F-04's state check) can regress-test today. The pure functions are well covered; the behaviour is not.

**Recommendation** Three cheap additions, in order of value:

1. **Mappers** (pure, no deps): `StatusItem → StatusEntity → StatusItem` round trip, `toSafeString` on `null`/primitive/object, `PlaceItem.getDisplayName`, `CollectionItem.getDisplayName` fallbacks (`PixelfedApi.kt:159-164, 198-201`), `parseChallengeTags` `"#A + #B"` grouping.
2. **`MockWebServer` + the `PixelfedApiFactory` of F-15**: assert that a 401 emits `SessionState.Expired`, that a 500 from `searchPlaces` is a `Failed` not an empty success, and that `forceRefresh=false` within TTL issues **zero** requests.
3. **`room-testing`** for `StatusDao` (insert/replace/count/order), matching neurhome's JVM DAO test suite.

**Blueprint reference** neurhome runs DAO and migration tests on the JVM with real in-memory SQLite (`room-testing`, AGENTS.md → *Build And Test*: *"the JVM stack includes Robolectric, `androidx.room:room-testing`, `kotlinx-coroutines-test` and Turbine, so the DAO and migration tests run on the JVM"*).

---

### [SEVERITY: low] F-20 — Room database: single-column primary key, no index, `exportSchema = false`, no logout purge

**Evidence**
- `data/db/StatusEntity.kt:9` — `@PrimaryKey val id: String` while rows are scoped by `instanceUrl`; status ids are numeric and reused across instances.
- `data/db/StatusDao.kt:10-14` — `WHERE instanceUrl = :instanceUrl` with no index; `AppDatabase.kt:6` — `exportSchema = false`; `DatabaseModule.kt:20-26` — builder without migrations or `fallbackToDestructiveMigration`.
- `StatusDao.clearStatuses` (`:20-21`) has **no caller**; `TokenManager.clear()` on logout (`MainViewModel.kt:113-116`) does not touch Room.

**Impact** Re-pointing the app at a different instance (the login screen accepts any URL, `LoginScreen.kt:36-40`) silently REPLACEs rows sharing an id, and the previous instance's content survives logout. `exportSchema = false` means the first schema change has no baseline and no migration test path.

**Recommendation** `indices = [Index("instanceUrl")]`, composite key `@PrimaryKey(primaryKey = ["instanceUrl","id"])` (or keep `id` but namespace it), `exportSchema = true` with schemas committed, and call `statusDao.clearStatuses(instanceUrl)` from the logout path.

**Blueprint reference** neurhome keeps `exportSchema = true`, 22 declared `AutoMigration`s in lockstep with the version constant, and CI that *fails* a build whose schema export is not committed (AGENTS.md → *Database*).

---

### [SEVERITY: low] F-21 — Dead code and stale imports mislead readers about what is live

**Evidence** No references outside their own definitions: `PixelfedApi.kt:128-135` `RegisterAppResponse`, `:137-144` `TokenResponse` (parsing happens manually at `PixelfedRepository.kt:601-661` instead); `StatusDao.kt:12` `getStatusesFlow`, `:21` `clearStatuses`; `PixelfedRepository.kt:521-523` `getUserTopTags`; `TokenManager.kt:23-34` `cachedTagsJson`/`tagsCacheTime`. Unused imports: `PixelfedRepository.kt:17` `GsonBuilder`, `:28` `HttpLoggingInterceptor`.

**Impact** `getStatusesFlow` existing but unused is particularly misleading: it suggests the repository exposes reactive cached reads, when in fact every screen re-triggers imperative fetches (F-07/F-08). `TokenResponse` suggests token metadata (expiry) is tracked — it never is.

**Recommendation** Delete them, except keep `StatusDao.getStatusesFlow` if F-08's fix routes the UI through Room; in that case wire it up so the cache is observable. Also: `DatabaseModule.provideStatusDao` (`:28-29`) is not `@Singleton` while the database is — mark it `@Singleton` for consistency.

---

### [SEVERITY: low] F-22 — `searchPlaces` silently falls back to `pixelfed.social` when logged out and sends no auth

**Evidence** `PixelfedRepository.kt:257-261`:
```kotlin
val instanceUrl = tokenManager.instanceUrl ?: "https://pixelfed.social"
val accessToken = tokenManager.accessToken
… val authHeader = accessToken?.let { "Bearer $it" }
```
**Impact** An unauthenticated call hard-coded to a third-party instance — a stray request goes to a server the user never chose, and on an instance without a public place index the result is an unexplained empty list (F-11 masks even a 401).

**Recommendation** `?: return@withContext Result.failure(NotLoggedIn)` — consistent with every other method in the file (`:187`, `:331`, `:543`), and drop the nullable `authHeader` parameter from `PixelfedApi.searchPlaces`/`searchHashtags` once F-06's interceptor lands.

---

### [SEVERITY: low] F-23 — `getFileFromUri` swallows the failure cause and uses `printStackTrace`

**Evidence** `PixelfedRepository.kt:733-739`:
```kotlin
} catch (e: Exception) {
    e.printStackTrace()
    null
}
```
The `null` becomes a user-facing `"Unable to process image file"` (`:198`), losing the distinction between "permission revoked", "URI from a dead provider", and "cacheDir full".

**Recommendation** `Log.e(TAG, "getFileFromUri failed for $uri", e)` and return a `Result<File>` (or throw a typed `ImageAccessException`) so the message names the cause; also close the streams with `use { }` so a mid-copy exception does not leak descriptors (`:729-735` opens both without `use`).

---

### [SEVERITY: low] F-24 — Release-build obfuscation is not covered for the one reflective, non-`data.api` Gson type

**Evidence** `proguard-rules.pro:21` keeps `ovh.litapp.pixlit.data.api.**` only, while `CachedTagSearch` is a **private** class in `data.repository` (`PixelfedRepository.kt:39-41`) that is deserialised reflectively through `TypeToken<Map<String, CachedTagSearch>>` (`:529-534`, `:568-571`) with **no** `@SerializedName` on its field. Release builds run R8 (`app/build.gradle:52-56`), and the file's own comment (`proguard-rules.pro:13`) records that a `TypeToken`-related release crash already happened once.

**Impact** Field renaming is self-consistent within one build, so this is not a guaranteed crash — but any future read of a cache blob written by a different version, or any R8 renaming/dedup decision on that private class, breaks the cache silently (parse → empty map → every search re-fetches). And nothing in CI would catch it: `:app:testDebugUnitTest` maps to `testLocalDevDebugUnitTest` only (`app/build.gradle:78-81`), so no unit test ever runs against the minified prod variant.

**Recommendation** `-keep class ovh.litapp.pixlit.data.repository.CachedTagSearch { *; }` (or annotate its field `@SerializedName("names")`), and add `:app:testProdDebugUnitTest` to CI as neurhome does (`AGENTS.md` → *Build And Test*: *"Run `:app:testProdDebugUnitTest` as well as `:app:testDevDebugUnitTest`… a release run has already failed at the prod-variant validation step while the dev suite was green"*). This fades away entirely if F-17 moves the cache to Room.

---

## Top 5 actions

1. **Stop leaking credentials (F-01, F-02).** Gate `HttpLoggingInterceptor` on `BuildConfig.DEBUG` with `redactHeader("Authorization")` and `Level.BASIC`; move `accessToken`/`clientSecret` to `EncryptedSharedPreferences` and exclude `pixlit_prefs.xml` in `data_extraction_rules.xml`. Both are ~30 lines total and are the only findings with external blast radius.
2. **Make uploads survive the process (F-05, F-09).** Move `uploadPhotosAndCreateStatus` behind a `@HiltWorker` with a foreground notification, add an `Idempotency-Key` to `createStatus`, delete the temp files in a `finally`, and set explicit 120 s write / 180 s call timeouts on the shared `OkHttpClient`.
3. **Fix auth lifecycle (F-04, F-06).** Add `state` + PKCE to the authorize URL and verify `state` in `handleIntent`; introduce an `AuthInterceptor` + `SessionState` so a 401 routes the user to re-login instead of silently degrading to placeholder data — and let it own `accountId` caching, which removes two of the four requests fired on every Upload-screen entry (F-07).
4. **Kill the main-thread work and the lie about caching (F-03, F-08, F-10).** Make the static statuses a memoized, `Dispatchers.Default` load with the injected `gson`; replace `forceRefresh || !isLoggedIn()` with a real TTL + `Mutex` single-flight; persist `tags`/`media_descriptions` and the server `created_at` in `StatusEntity` so cached rankings equal live ones.
5. **Split the 740-line repository along the six seams in F-12, in that order,** and land the accompanying pure-function + `MockWebServer` + `room-testing` tests (F-19) as each seam moves — starting with `TagAggregator` and `OAuthParsers`, which are already pure and already half-tested, so they cost one import change each.

---

**Verification commands a supervisor must run** (I did not execute anything):
- `./gradlew :app:testLocalDevDebugUnitTest` — the three existing test classes (baseline, should stay green).
- `./gradlew :app:assembleProdRelease` after adding any keep rule in F-24, since only R8 exercises the `CachedTagSearch` reflection path.
- On-device: `adb logcat | grep -i "Authorization\|Bearer"` during login and one upload, to confirm F-02 before and after the fix.