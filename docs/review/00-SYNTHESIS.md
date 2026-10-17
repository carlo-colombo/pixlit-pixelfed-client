# Pixlit repository review — consolidated synthesis

Six independent read-only reviews, benchmarked against the `neurhome` blueprint.
Detail lives in the sibling files `01-…` through `06-…`. Nothing was executed
(no Gradle/CI run); every claim is source-cited in the detail reports.

> Cross-check note: findings marked **[2×]** were independently found by more
> than one reviewer, so they are high-confidence.

## Headline

The app is functionally complete but ships through a pipeline that **cannot
stamp or prove a release**, while the code still has **two release-build
credential leaks**, a **crash-prone image resize path**, and an **upload that
can be silently lost**. CI is doing less than it appears: it builds a signed
APK and publishes a GitHub release without running a single test, lint check,
or version stamp.

---

## Critical / high, deduplicated by theme

### A. CI & release process (the blueprint gap) — detail `01`, `02`

| # | Finding | Evidence | Blueprint fix |
|---|---------|----------|---------------|
| A1 | **Tag pushed before the APK is built/signed** → any later failure (or a second push cancelling the job) leaves an orphan tag with no release.**[2×]** | `build.yml:30-39` bump_and_tag runs first; assemble at `:67`; `cancel-in-progress: true` at `:14` | neurhome: `DRY_RUN` version → build → validate → `CUSTOM_TAG` push |
| A2 | **`-Pandroid.versionName` is a no-op** — `app/build.gradle:17` hardcodes `versionName "1.0.0"`, so every released APK claims `1.0.0`/`versionCode 1` regardless of tag.**[2×]** | only match for `android.versionName` is `build.yml:74`; `findProperty` absent | `versionName = findProperty("android.versionName")`, add `-Pandroid.versionCode` |
| A3 | **CI runs zero unit tests and zero (full) lint**; the 53 existing tests never gate a PR/release.**[2×]** | `build.yml` has no `test*`/`lint*` task | `:app:testCiDevDebugUnitTest :app:lintCiDevDebug` on PR; `testCiProdDebugUnitTest :app:lintCiProdRelease` before signing |
| A4 | **Hardcoded build-tools `34.0.0`** for signing while `compileSdk 37`; unmaintained `r0adkll` action + deprecated `::set-output`.**[2×]** | `build.yml:79-86` | resolve highest installed build-tools, explicit `zipalign`/`apksigner` |
| A5 | **All actions pinned to mutable tags** (`@v4/@v5/@v6/@v1/@v2`) in a job holding signing secrets + `contents: write`. | `build.yml:25,33,42,48,101,108` | SHA-pin every action; add Dependabot |
| A6 | **`contents: write` on the whole job, including PR runs.** | `build.yml:17-22` | workflow-level `contents: read`, write only on the release path |
| A7 | **Signing secrets have silent fallbacks** `\|\| 'key'`, `\|\| 'password'`. | `build.yml:83-84` | fail fast; no defaults |
| A8 | **No PR workflow**: no paths-ignore change detection, no always-on `required-checks` job, no `workflow_dispatch`. | only `build.yml` exists | port neurhome `pr.yml` shape |
| A9 | **No Dependabot, no checksums, no `retention-days`, no Room schema gate.** | `.github/` lacks `dependabot.yml`; `build.yml:100-104` | copy neurhome config + checksum + retention + schema check |
| A10 | **JDK/daemon drift**: JDK 17 in CI, JBR 21 locally, no `gradle-daemon-jvm.properties`, no shared composite action. | `build.yml:42-44`; `.gradle/config.properties:2` | pin JBR 21 in daemon props + composite action, lockstep |

### B. Security — detail `04`, `06`

- **B1 [critical] Release builds log secrets and full image bodies.** `NetworkModule.kt:31-38` installs `HttpLoggingInterceptor` at `Level.BODY` unconditionally → `client_secret`/`access_token` in logcat on the signed release APK, and the ~8 MB multipart body buffered/stringified per upload. Fix: `Level.BASIC` behind `BuildConfig.DEBUG`, `redactHeader("Authorization")`, and add timeouts (write 120 s / call 180 s) — the client has none, so large uploads can time out mid-flight. **[2×]**
- **B2 [critical] OAuth token + client secret stored in plaintext SharedPreferences and uploaded by Auto Backup.** `TokenManager.kt:11,47-61`; `AndroidManifest.xml:14` `allowBackup="true"` with no `dataExtractionRules`. Fix: `EncryptedSharedPreferences` + `data_extraction_rules.xml` excluding `pixlit_prefs.xml`. **[2×]**
- **B3 [high] OAuth has no `state` and no PKCE**, and the exported deep link accepts any `code`. `LoginScreen.kt:65-68`; `MainViewModel.kt:73-77`. Fix: `state` + PKCE verifier, verify on redirect. **[2×]**
- **B4 [high] No 401 handling anywhere** — an expired/revoked token keeps `isLoggedIn()==true` forever, UI degrades to placeholder data. Fix: `AuthInterceptor` + `SessionState` flow. **[2×]**

### C. Crash / correctness — detail `06`, `03`, `04`

- **C1 [critical] Resize path decodes the full-resolution bitmap → OOM; `catch (Exception)` cannot catch `OutOfMemoryError`.** `ImageUtils.kt:313-317, 325-331, 378-386, 389-406` — an 8 MB downscale request resolves to `inSampleSize = 1`, so a 48 MP photo allocates ~256 MB peak and crashes the app's primary flow. Fix: `ImageDecoder` with `setTargetSize` (minSdk 34 guarantees it), bounded sample size, `catch (OutOfMemoryError)`, regression test.
- **C2 [high] Intents re-handled on every Activity creation** → a shared photo is appended twice on rotation (consumes 2 of 6 slots, uploads the file twice) and a spent OAuth code is re-exchanged. `MainActivity.kt:53,102-107`; `MainViewModel.kt:44-59,76-99`. Fix: consume extras once, guard `onCreate` with `savedInstanceState == null`.
- **C3 [high] EXIF location race can overwrite a manual choice and publish the wrong city in the caption.** `UploadViewModel.kt:158-192` (guard only checked before a multi-second `Geocoder` call). Fix: re-check on the main thread after geocode + cancellable job.
- **C4 [high] `updateMetadata` launches an uncancelled coroutine per emission** → stale dimensions/size and a stuck "Calculating…". `UploadViewModel.kt:134-142, 249-266`. Fix: `collectLatest` + suspend function.
- **C5 [high] Uploads run in `viewModelScope`** → back/process death silently drops the post and orphans uploaded media; no idempotency key → retry duplicates the post. `UploadViewModel.kt:537`; `PixelfedRepository.kt:193-243`. Fix: `@HiltWorker` + foreground notification + `Idempotency-Key`.
- **C6 [high] Room cache is lossy** (drops `tags`/`media_attachments`, stores fetch-time as `created_at`) so cached tag rankings ≠ live ones and order is arbitrary. `PixelfedRepository.kt:422-430, 465-471, 509-513`; `StatusEntity.kt`. Fix: persist what the aggregator reads + real `created_at`.
- **C7 [high] Results are swallowed** — tag/status reads always return `Result.success`, places/collections fabricate empty/local data on error. `PixelfedRepository.kt:271-273, 349-352, 445`. Fix: typed `Fetch<T>` (Fresh/Cached/Failed).

### D. Performance — detail `06`, `04`, `03`, `02`

- **D1 [high] 1 MB asset JSON parsed on the main thread on every Upload-screen construction.** `UploadViewModel.kt:58` → `PixelfedRepository.kt:286-306` (`readText` + fresh `Gson`). Fix: lazy memoized parse on `Dispatchers.Default`.
- **D2 [high] Entering Upload fires 4 HTTP calls, 2 identical** (`verify_credentials` re-fetched per call/page). `PixelfedRepository.kt:448-460, 500-504, 336-339`; `UploadViewModel.kt:121-125`. Fix: memoize `accountId` per session, parallelize, cache with TTL + single-flight.
- **D3 [high] Image is decoded/resized twice** (preview + upload) and temp files are never deleted → ~48 MB dead JPEGs per post. `UploadViewModel.kt:258-263`; `PixelfedRepository.kt:194-198, 727-740`; `ImageUtils.kt:310, 412`. Fix: prepare once, pass the file, delete in `finally`.
- **D4 [medium] `refreshCacheSummary()` after every debounced tag search** does a full `SELECT *` + two JSON re-parses per keystroke. `PixelfedRepository.kt:578-580`; `UploadViewModel.kt:453-460`. Fix: `COUNT(*)` projection, refresh on demand.
- **D5 [medium] `RecentPostCard` re-parses HTML + recompiles a regex on every recomposition.** `RecentPostsSection.kt:71-82`. Fix: `remember(...)`.
- **D6 [medium] Gradle caching, configuration cache, parallel, metaspace cap, and daemon pin all absent.** `gradle.properties` (3 lines). Fix: neurhome's property set. **[2×]**
- **D7 [medium] 2×2 flavor matrix multiplies test JVMs / R8 runs 4× for no functional coverage.** `app/build.gradle:27-46`. Fix: keep one `env` dimension or scope unit tests to one variant.

### E. Refactoring / architecture — detail `03`, `04`, `05`

- **E1 [medium] `UploadScreen` (~845 lines) + `UploadViewModel` (~606 lines) with 27 `MutableStateFlow`s and a 45-parameter composable**, hosting 5 unrelated tabs (incl. out-of-scope Social and a Debug tab). Propose `UploadUiState`, plus `ImagePipeline` / `LocationController` / `CaptionController` / `CollectionController` / `UploadSubmitter`, and tab extraction.
- **E2 [medium] `PixelfedRepository` (740 lines, 14 responsibilities)** with a per-request `Retrofit` rebuild. Six ordered seams given (`TagAggregator`, `OAuthParsers`, `SessionRepository`, `MediaUploader`, `StatusRemoteSource`+`Cache`, `PixelfedApiFactory`).
- **E3 [medium] Room `exportSchema = false`, no schema dir, no migration path, no index on `instanceUrl`, no logout purge.** `AppDatabase.kt:4-7`; `StatusEntity.kt`; `DatabaseModule.kt`. First `version = 2` will crash on open.

### F. UX — detail `05`

- **F1 [high] Login spinner dead-end** when `registerApp` fails: `isLoading` set `true` and never reset → unrecoverable onboarding. `LoginScreen.kt:52-56, 181-185`.
- **F2 [high] Debug tab + "Simulate Art Show Notification" ship in the prod APK.** `UploadScreen.kt:259, 684, 736-741`; `MainActivity.kt:97`.
- **F3 [medium] No confirmation/undo** for logout and photo removal; no `SnackbarHost` for upload success/failure.
- **F4 [medium] Keyboard covers the Upload button** (`imePadding()` missing; `delay(100)` scroll hack) and fixed `height(240/420.dp)` breaks large font.
- **F5 [medium] Back exits the app from any tab** (no `BackHandler`).
- **F6 [medium] a11y**: resize row is a double TalkBack target; "Advanced credentials" has no role/state; ~all copy hardcoded (no i18n); no night `themes.xml` → white flash in dark mode.
- **F7 [medium] Over-broad `READ_MEDIA_IMAGES`/`READ_MEDIA_VISUAL_USER_SELECTED`** declared but unused (picker is `ACTION_GET_CONTENT`) — privacy/Play liability.

---

## Suggested execution order

**Wave 0 — stop the bleeding (release correctness + security), small diffs:**
1. A2 wire `versionName`/`versionCode` to the tag.
2. A1/A3 reorder release: dry-run tag → build → **validate** → push `CUSTOM_TAG`; add test+lint gates.
3. B1 gate HTTP logging + add timeouts.
4. B2 encrypt/exclude token storage.
5. C1 fix the resize decode path.

**Wave 1 — make upload safe and observable:**
6. C2 one-shot intents; C3/C4 stale-state races; C5 WorkManager + progress/cancel + idempotency; D3 temp-file lifecycle; F1 login dead-end.

**Wave 2 — CI to the blueprint standard:**
7. A4–A10 (build-tools, SHA pins, least privilege, `pr.yml` with `required-checks`, Dependabot, Room schema gate, composite JDK action, Gradle caching).

**Wave 3 — structural:**
8. D1/D2/D4/D5 perf fixes; C6/C7 data correctness; E1–E3 refactors; F2–F7 UX/a11y.

---

## Caveats

- Reviews were read-only; no Gradle, Robolectric, or CI run. Verify with
  `./gradlew :app:testLocalDevDebugUnitTest`, then the new PR/release tasks.
- One conflict worth flagging: `docs/bluesky-art-show-reminders.md` describes
  WorkManager-based scheduling while the shipped code uses `AlarmManager`
  (`06`, low). README matches the code; the design doc is stale.
- These reports live under `docs/review/` and are the evidence base for
  [`docs/backlog.md`](../backlog.md); they are currently untracked (see the
  backlog's open decisions).
