# Pixlit Improvement Backlog

Generated from the six-reviewer repo sweep requested on 2026-10-17. Raw evidence
lives in [`docs/review/`](review/):

- [`00-SYNTHESIS.md`](review/00-SYNTHESIS.md) — consolidated, deduplicated
- [`01-ci-release-blueprint-gap.md`](review/01-ci-release-blueprint-gap.md) — CI/release vs `neurhome`
- [`02-gradle-build-config.md`](review/02-gradle-build-config.md) — build config + build perf
- [`03-upload-flow.md`](review/03-upload-flow.md) — core upload flow
- [`04-data-network.md`](review/04-data-network.md) — repository/API/auth/DI
- [`05-ux-compose.md`](review/05-ux-compose.md) — UX + Compose quality
- [`06-images-db-background.md`](review/06-images-db-background.md) — images/Room/WorkManager/entry

Every finding below cites the source report and the `file:line` it rests on. No
code has been changed for this backlog. **No item is marked `verified` by a
build run yet** — the P0 rows were re-read against source by the parent; the
rest carry the reviewer's own evidence.

Per-task breakdowns (90 files, one per ID) are in
[`docs/plans/repo-review/`](plans/repo-review/README.md).

Blueprint for conventions, CI and release: [`../neurhome`](../../neurhome)
(`.github/workflows/{build,pr}.yml`, `.github/actions/setup-jdk-gradle/action.yml`,
`.github/dependabot.yml`, `gradle/gradle-daemon-jvm.properties`, `docs/backlog.md`).

## Status

Not started. Wave 0 is ready to schedule. See [Delivery plan](#delivery-plan).

## ID scheme

| Prefix | Area |
|---|---|
| `REL` | Release pipeline, CI/CD, repo automation |
| `SEC` | Security, credentials, auth, privacy |
| `UP` | Upload flow (UI, ViewModel, image preparation) |
| `DATA` | Repository, API, caching, persistence |
| `BUILD` | Gradle / Android build configuration |
| `REF` | Refactoring / architecture |
| `UX` | User experience, Compose quality, accessibility |
| `PLAT` | Background work, notifications, app entry |
| `TEST` | Test coverage (beyond the CI gate itself, which is `REL`) |

## How to read

| Field | Meaning |
|---|---|
| Pri | P0 = release correctness / security / crash / data loss · P1 = user-visible defect or major performance · P2 = quality gate, robustness, build health · P3 = polish, perf, refactor |
| Size | S ≈ <1 file / trivial · M ≈ a few files or schema/config · L ≈ cross-cutting |
| Ver | `2×` = independently found by two reviewers (and/or re-read against source by the parent) · `review` = single reviewer with file:line evidence |
| Dep | must land before this item |
| Src | report + finding number |

---

## Delivery plan

Five waves, ordered so that each de-risks the next. Within a wave, only
**file-disjoint** tasks run concurrently (one writer per worktree); everything
else is sequential.

| Wave | Theme | IDs | Exit gate |
|---|---|---|---|
| **0** | Stop the bleeding: release stamp/ordering, secret logging, OOM | REL-01…05, REL-13, BUILD-01, REL-15, SEC-01, SEC-02, SEC-04, UP-01 | A `main` push produces a tagged, signed APK whose `versionName` equals the tag, with no secret in logcat, built only after tests+lint pass |
| **1** | Upload reliability, data correctness, onboarding | UP-02…06, DATA-01…05, UX-01, UX-02 | Upload survives process death; EXIF/metadata races gone; login failure recoverable; debug tools absent from prod |
| **2** | CI to the blueprint standard | REL-06…12, REL-14, SEC-03, BUILD-04, BUILD-07 | `pr.yml` with `required-checks`, SHA-pinned actions, least privilege, Dependabot, Room schema gate, lint gate, Gradle caching |
| **3** | Remaining data/perf/build | SEC-05, SEC-06, DATA-06…11, BUILD-02, BUILD-03, BUILD-05, BUILD-06, BUILD-08, UP-07…12 | No main-thread I/O; single-flight + TTL caching; flavor matrix cost removed |
| **4** | Refactor, UX, a11y, tests | REF-01…03, UX-03…18, PLAT-01, PLAT-02, BUILD-09…13, DATA-12…15, TEST-01…04, UP-13 | Shared `UploadUiState`; tabs extracted; confirm/undo/IME/back/a11y fixed; data-layer tests |
| **5 (final)** | Documentation refresh — **DOC-01 / task 91** | Update `AGENTS.md` + `README.md` to the shipped code and CI | Docs match the repo; every documented command works |

### Wave 0 lanes (file-disjoint, can run in parallel)

| Lane | Owns | IDs |
|---|---|---|
| W0-A `release` | `.github/**`, `app/build.gradle` (version block only), `gradle/gradle-daemon-jvm.properties` | REL-01, REL-02, REL-03, REL-04, REL-05, REL-13 |
| W0-B `build` | `gradle.properties`, `app/proguard-rules.pro`, `.gitignore`, `gradle/libs.versions.toml` | BUILD-01, REL-15 |
| W0-C `security` | `di/NetworkModule.kt`, `data/auth/TokenManager.kt`, `MainActivity.kt`, `MainViewModel.kt`, `AndroidManifest.xml`, `res/xml/**` | SEC-01, SEC-02, SEC-04 |
| W0-D `image` | `utils/ImageUtils.kt`, `utils/ImageUtilsTest.kt` | UP-01 |

Conflict note: `app/build.gradle` is owned by **W0-A only** in Wave 0; W0-B's
items do not touch it. `MainViewModel.kt` is split later (SEC-04 in W0-C,
UP-02 in Wave 1) — do not run those lanes concurrently.

### Verification commands

- Build: `./gradlew :app:assembleLocalDevDebug`
- Unit (local): `./gradlew :app:testLocalDevDebugUnitTest` (delete the alias task first, see BUILD-03)
- Prod gate (required pre-release): `./gradlew :app:testCiProdDebugUnitTest :app:lintCiProdRelease`
- Release smoke: install the built APK, confirm `aapt dump badging … | grep versionName`
- Reports: `app/build/reports/tests/**`, `app/build/reports/lint-results-*.html`

---

## P0 — release correctness, security, crash, data loss

| ID | Task | Size | Ver | Dep | Src |
|---|---|---|---|---|---|
| REL-01 | **Tag is pushed before the APK is built, signed or validated → orphan tags.** `build.yml:30-39` pushes the tag first; assemble/sign/release follow at `:67-110`; `cancel-in-progress: true` (`:14`) can cancel a release after the tag exists. Fix: compute the tag with `DRY_RUN: true`, stamp the build, validate, then push with `CUSTOM_TAG`. | M | 2× | — | 01#1 |
| REL-02 | **`-Pandroid.versionName` is a no-op; every released APK claims `1.0.0` / `versionCode 1`.** `app/build.gradle:16-17` hardcodes both; no `findProperty` exists; `build.yml:74` passes the flag to nothing. Fix: `versionName = findProperty("android.versionName") ?: "1.0.0"`, add `-Pandroid.versionCode` derived from the tag. | S | 2× | — | 01#2, 02#1 |
| SEC-01 | **Release APK logs OAuth secrets and buffers the whole upload body.** `NetworkModule.kt:31-38` installs `HttpLoggingInterceptor` at `Level.BODY` unconditionally, on the shared singleton client used for `registerApp`/`fetchAccessToken`/`uploadMedia`; no timeouts configured. Fix: `Level.BASIC` only when `BuildConfig.DEBUG`, `redactHeader("Authorization")`, add connect/read/write/call timeouts. | S | 2× | — | 04 F-02, 06 |
| SEC-02 | **Access token and `client_secret` are plaintext SharedPreferences and are Auto-Backed-up.** `TokenManager.kt:11,47-61`; `AndroidManifest.xml:14` `allowBackup="true"` with no `dataExtractionRules`/`fullBackupContent`. Fix: `EncryptedSharedPreferences` + `res/xml/data_extraction_rules.xml` excluding `pixlit_prefs.xml`. | M | 2× | — | 04 F-01, 02 |
| UP-01 | **Resize path decodes the full-resolution bitmap; `catch (Exception)` cannot catch the resulting `OutOfMemoryError`.** `ImageUtils.kt:389-406` (`calculateInSampleSize` cannot reduce an 8 MB target below the source), `:325-331` (both bitmaps resident), `:378-386` (`catch (Exception)`); reached from `UploadViewModel.kt:258-261` and `PixelfedRepository.kt:194-196`. Fix: `ImageDecoder` + `setTargetSize` (minSdk 34 guarantees it), bounded decoded pixels, `catch (OutOfMemoryError)`, regression test on an oversized fixture. | M | 2× | — | 06 C1 |

Acceptance for Wave 0: see [Wave 0 exit criteria](#wave-0-exit-criteria).

---

## P1 — user-visible defects, upload reliability, major performance

| ID | Task | Size | Ver | Dep | Src |
|---|---|---|---|---|---|
| REL-03 | **CI runs no unit tests and no full lint.** `build.yml` only invokes `assemble…Release`; the 53 existing tests never gate anything. Fix: add `:app:testCiDevDebugUnitTest :app:lintCiDevDebug` to PR CI. | S | 2× | — | 01#3, 02#2 |
| REL-04 | **No prod-variant validation before signing/release.** Mirror `neurhome`'s `testProdDebugUnitTest` + `lintProdRelease` step for `ciProd` before the tag is pushed. | S | 2× | REL-01 | 01#3 |
| REL-05 | **Signing depends on an unmaintained action + hardcoded build-tools `34.0.0` while `compileSdk 37`.** `build.yml:79-86`. Fix: resolve the installed build-tools dynamically and run `zipalign`/`apksigner` explicitly (as `neurhome` did after a real failure). | M | 2× | — | 01#6, 02 |
| SEC-03 | **OAuth has no `state` and no PKCE; the exported deep link accepts any `code`.** `LoginScreen.kt:65-68`; `MainViewModel.kt:73-77`; `AndroidManifest.xml` `BROWSABLE`. Fix: generate/verify `state`, add S256 PKCE; also narrow scopes from `read write follow`. | S | 2× | — | 04 F-04 |
| SEC-04 | **No 401 handling anywhere; `isLoggedIn()` only checks non-blank, so a revoked token leaves the UI silently degraded forever.** Fix: `AuthInterceptor` + a `SessionState` flow that routes back to login and owns `accountId` caching. | M | 2× | — | 04 F-06 |
| UP-02 | **Intents are re-handled on every `Activity` creation.** `MainActivity.kt:53,102-107`; a shared photo is appended twice on rotation (consumes two of six slots, uploads the file twice) and a spent OAuth code is re-exchanged. Fix: consume extras once; guard `onCreate` with `savedInstanceState == null`. | S | 2× | — | 06 C2 |
| UP-03 | **EXIF auto-detection can overwrite a manual location and publish the wrong city in the caption.** `UploadViewModel.kt:158-192` re-checks the guard only before the multi-second `Geocoder` call. Fix: re-check on Main after the round-trip, hold it in a cancellable job. | S | review | — | 03 |
| UP-04 | **`updateMetadata` launches an uncancelled coroutine per emission → stale metadata and a stuck "Calculating…".** `UploadViewModel.kt:134-142, 249-266`. Fix: `collectLatest` + a single `suspend` step keyed by URI. | S | 2× | — | 03, 06 |
| UP-05 | **Uploads run in `viewModelScope`; no progress, cancel, partial-failure recovery or idempotency.** `UploadViewModel.kt:537`; `PixelfedRepository.kt:193-243`. Fix: `@HiltWorker` + foreground notification + `Idempotency-Key` + per-photo progress + cancel. | L | 2× | REL-03 | 03, 04 F-05 |
| UP-06 | **Images added/removed during an in-flight upload are silently discarded; the 6-cap is silent and duplicate URIs are allowed.** `UploadViewModel.kt:382-386, 558-572`; `UploadScreen.kt:447`. Fix: gate the form on `!isUploading`, `distinct()`, surface the cap, clear only submitted URIs. | S | review | — | 03 |
| DATA-01 | **~1 MB asset JSON is read and parsed on the main thread on every Upload-screen construction.** `UploadViewModel.kt:58` → `PixelfedRepository.kt:286-306` (`readText` + fresh `Gson`). Fix: lazy, memoized, `Dispatchers.Default`, injected `Gson`. | S | 2× | — | 04 F-03, 06 |
| DATA-02 | **Opening Upload costs 4 HTTP calls, 2 identical.** `UploadViewModel.kt:121-125`; `PixelfedRepository.kt:448-460, 500-504, 336-339` re-call `verify_credentials`. Fix: memoize `accountId` per session, run independent fetches concurrently. | M | review | SEC-04 | 04 F-07 |
| DATA-03 | **The "refresh if stale" condition is self-contradictory, so non-forced loads never refresh; forced loads refresh every time.** `PixelfedRepository.kt:414-419`. Fix: real TTL + `Mutex` single-flight. | M | review | — | 04 F-08 |
| DATA-04 | **Room cache is lossy: drops `tags`/`media_attachments` and stores fetch time as `created_at`.** `PixelfedRepository.kt:422-430, 465-471, 509-513`. Fix: persist what the aggregator reads + real `created_at`. | M | 2× | — | 04 F-10, 06 |
| DATA-05 | **Domain errors are laundered into `Result.success` / fabricated data.** `PixelfedRepository.kt:271-273, 349-352, 445`. Fix: typed `Fetch<T>` (Fresh/Cached/Failed) and map HTTP codes once. | M | review | — | 04 F-11 |
| UX-01 | **Login spinner is an unrecoverable dead-end when `registerApp` fails.** `LoginScreen.kt:52-56, 181-185` sets `isLoading = true` and never resets on failure. Fix: lift loading/error into a `LoginUiState`; always resolve. | S | review | — | 05 |
| UX-02 | **Debug tab and "Simulate Art Show Notification" ship in the prod APK.** `UploadScreen.kt:259, 684, 736-741`; `MainActivity.kt:97`. Fix: `buildConfigField("boolean", "DEBUG_TOOLS", …)` per flavor and gate the tab + callback. | S | review | — | 05 |

---

## P2 — quality gates, robustness, build health

### Release / CI

| ID | Task | Size | Ver | Dep | Src |
|---|---|---|---|---|---|
| REL-06 | **All third-party actions are pinned to mutable tags** in a job holding signing secrets + `contents: write` (`build.yml:25,33,42,48,101,108`). Fix: SHA-pin every action (SHAs already resolved in `neurhome`'s workflows). | S | 2× | — | 01#4, 02 |
| REL-07 | **`contents: write` is granted to the job that also runs on PRs** (`build.yml:17-22`). Fix: workflow-level `contents: read`; write only on the push-only release path. | S | 2× | — | 01#5, 02 |
| REL-08 | **Signing secrets have silent fallbacks** `\|\| 'key'` / `\|\| 'password'` (`build.yml:83-84`). Fix: fail fast on missing secrets. | S | review | REL-05 | 01#7 |
| REL-09 | **No PR workflow**: no paths-ignore change detection, no always-on `required-checks` job, no `workflow_dispatch`; docs-only PRs pay for a full release assembly (`build.yml:9-11`). Fix: port `neurhome/pr.yml`. | M | 2× | REL-03 | 01#12, 02 |
| REL-10 | **No Dependabot config**; action/version pins drift. Fix: copy `neurhome/.github/dependabot.yml` (gradle + github-actions, grouped minor/patch). | S | 2× | REL-06 | 01#10, 02 |
| REL-11 | **No artifact checksum and no `retention-days`** (`build.yml:100-104`). Fix: `sha256sum` + attach `.sha256` to the release; set retention. | S | 2× | — | 01#9, 14 |
| REL-12 | **Room schema export is not configured, so the committed-schema gate is impossible.** No `schemaDirectory`/`app/schemas`; `AppDatabase.kt:4-7` `exportSchema = false`. Fix: configure export, commit `app/schemas`, add the CI dirtiness check. | M | 2× | BUILD-12 | 01#8, 06 |
| REL-14 | **Dead workflow config and an inconsistent PR fallback version.** `build.yml:3-4` unused env; `FLAVOR` written but never read; `1.0.0-pr` (`:71`) vs `1.0.0-pr-<run>` (`:92`). Fix: delete dead config, derive once. | S | review | — | 01#15 |
| REL-15 | **`.gitignore` misses `.kotlin/`** (and `.artifacts/`), so build-session state shows as untracked noise. | S | 2× | — | 01#16, 02 |
| REL-16 | **No copyright notice, no project license, and no license or notice document in the release.** `ls LICENSE* NOTICE*` is empty; `README.md` has no License section; the APK carries only AndroidX `META-INF/**/LICENSE.txt` plus one dependency `NOTICE.md`; `build.yml:243-258` attaches only the APK and its `.sha256`. Fix: add `LICENSE`/`NOTICE`/`THIRD_PARTY_LICENSES.md`, stage them into `assets/licenses/`, show them in an About surface, attach them to the release and state the SPDX id and source tag, and pin them with a JVM test (port `neurhome` `db45ece`). | M | repo | REL-11, BUILD-07 | repo |

### Security / privacy

| ID | Task | Size | Ver | Dep | Src |
|---|---|---|---|---|---|
| SEC-05 | **`READ_MEDIA_IMAGES` / `READ_MEDIA_VISUAL_USER_SELECTED` are declared but never used** (the picker is `ACTION_GET_CONTENT`; reads go through the granted URI). `AndroidManifest.xml:9-10`. Fix: switch to `PickMultipleVisualMedia` and drop the permissions. | S | review | — | 05 |
| SEC-06 | **OAuth scopes request `read write follow`, more than an uploader needs.** `PixelfedRepository.kt:80`. Fix: narrow to `read write` unless DMs/following are intended. | S | review | SEC-03 | 04 F-04 |

### Upload

| ID | Task | Size | Ver | Dep | Src |
|---|---|---|---|---|---|
| UP-07 | **Every processed photo is copied into `cacheDir` and never deleted** (up to ~48 MB of dead JPEGs per post, including un-stripped EXIF originals). `ImageUtils.kt:310, 412`; `PixelfedRepository.kt:727-740`. Fix: delete in `finally` / clear a dedicated uploads dir at process start. | M | 2× | — | 03, 04 F-13, 06 |
| UP-08 | **Each photo is decoded/resized twice** (once for preview metadata, once for upload). `UploadViewModel.kt:258-263`; `PixelfedRepository.kt:194-198`. Fix: prepare once, pass the file to the repository. | M | 2× | UP-05 | 03, 06 |
| UP-09 | **Rotation/process death loses the whole in-progress post.** No `SavedStateHandle` in `UploadViewModel.kt:27-31`; `remember` for tab/query. Fix: `rememberSaveable` + `SavedStateHandle` for caption/URIs. | M | review | — | 03 |
| UP-10 | **The caption is sent as the alt `description` of every image, with no length guard** (`PixelfedRepository.kt:203-209`), which is an a11y problem and can 422 the upload after media is orphaned. Fix: per-photo alt text, clamped. | M | review | UP-05 | 04 F-16 |
| UP-11 | **The multipart part is always `.jpg`/`image/*` regardless of source format, and resizing drops EXIF/flattens alpha.** `PixelfedRepository.kt:200-201`; `ImageUtils.kt:300-302, 333`. Fix: resolve real mime/name, decide EXIF/alpha policy. | M | 2× | — | 04, 06 |
| UP-12 | **`resizeImageDownToMaxBytes` has no `try`/`catch` and does not `use {}` its stream**; a disk/decoder error escapes the caller and leaves partial temp files. `ImageUtils.kt:294-352`. | M | review | UP-01 | 06 |

### Data

| ID | Task | Size | Ver | Dep | Src |
|---|---|---|---|---|---|
| DATA-06 | **`PixelfedRepository` is 740 lines / 14 responsibilities and rebuilds a `Retrofit` per request** (`:55-62` + 8 call sites). Fix: six ordered seams (`TagAggregator`, `OAuthParsers`, `SessionRepository`, `MediaUploader`, `StatusRemoteSource`+`Cache`, `PixelfedApiFactory`). | L | 2× | DATA-04 | 04 F-12, F-15 |
| DATA-07 | **`getCacheSummary()` materialises every cached row just to count it, and runs after every debounced tag search.** `PixelfedRepository.kt:578-580`; `UploadViewModel.kt:453-460`. Fix: `COUNT(*)` projection; refresh on demand. | S | 2× | — | 03, 04 F-14 |
| DATA-08 | **Tag-search cache lives in SharedPreferences: unbounded, no TTL, non-atomic read-modify-write, blocking `commit()`.** `PixelfedRepository.kt:528-559`; `TokenManager.kt:36-41`. Fix: move to Room with a TTL (or bound + make atomic). | M | review | REL-12 | 04 F-17 |
| DATA-09 | **`SharedPreferences` with `commit()` (blocking) used for tokens/reminders; one write on the main thread; no reactive state.** `TokenManager.kt:26-70`; `ReminderPreferences.kt:9-22`; `MainViewModel.kt:103-104`. Fix: DataStore. | M | 2× | SEC-02 | 06 |
| DATA-10 | **DTO nullability and JSON tolerance are unverified; no fixture parsing tests.** `PixelfedApi.kt:194-196`, `BlueskyApi.kt:16`, `NetworkModule.kt:25-27`. Fix: make nullable boundary explicit + `app/src/test/resources/fixtures/**`. | M | review | TEST-01 | 04 F-18 |
| DATA-11 | **Room has no `instanceUrl` index, no `exportSchema`, and no logout purge; `clearStatuses` is dead.** `StatusEntity.kt`, `StatusDao.kt:11-21`, `AppDatabase.kt:4-7`. Fix: index + export + purge on logout + composite identity. | M | 2× | REL-12 | 04 F-20, 06 |

### Build

| ID | Task | Size | Ver | Dep | Src |
|---|---|---|---|---|---|
| BUILD-01 | **Gradle caching, configuration cache, parallel execution, metaspace cap and daemon pin are all absent** (`gradle.properties` is 3 lines). Fix: adopt `neurhome`'s property set (`-Xmx4g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC`, caching, config-cache, parallel, incremental). | S | 2× | — | 02 |
| BUILD-04 | **Robolectric fetches `android-all` from a live Maven mirror at test time and is not configured for resources** (no `includeAndroidResources`, no `robolectric.properties`). `app/build.gradle:72-78`. Fix: declare/stage the runtime, `includeAndroidResources = true`, pin `sdk`. | M | review | — | 02 |
| BUILD-07 | **No lint configuration or gate**; only fatal lint runs. `app/build.gradle` has no `lint {}`. Fix: `warningsAsErrors`, `abortOnError`, baseline, upload report. | M | 2× | REL-03 | 02 |

### Refactoring (seeds)

| ID | Task | Size | Ver | Dep | Src |
|---|---|---|---|---|---|
| REF-01 | **`UploadScreen` (~845 lines) + `UploadViewModel` (~606 lines) with 27 `MutableStateFlow`s and a 45-parameter composable.** Fix: introduce `UploadUiState` (single `StateFlow`) and extract `ImagePipeline`/`LocationController`/`CaptionController`/`CollectionController`/`UploadSubmitter`. | L | 2× | UP-03, UP-04 | 03, 05 |
| REF-02 | **Extract the five tab bodies out of `UploadContent`.** `UploadScreen.kt:255-790`. Fix: `UploadTabContent`, `RecentPostsTabContent`, `DebugTabContent`, `SettingsTabContent`; move `SocialScreen` out of the upload feature. | M | 2× | UX-02 | 03, 05 |
| REF-03 | **See DATA-06** (repository seams listed there to avoid duplication). | — | — | DATA-06 | 04 |

### UX

| ID | Task | Size | Ver | Dep | Src |
|---|---|---|---|---|---|
| UX-03 | **No confirmation/undo for destructive actions and no `SnackbarHost`** (no `SnackbarHost` exists anywhere; logout `UploadScreen.kt:327-329`, remove-photo `:384-390`). Fix: logout dialog, remove undo, snackbar for upload feedback. | S | 2× | — | 05 |
| UX-04 | **Keyboard covers the Upload button; fixed heights break large font.** `UploadScreen.kt:269-276, 486-490, 618-655`; `RecentPostsSection.kt:84`. Fix: `imePadding()`, `heightIn`, drop the `delay(100)` hack. | M | 2× | — | 05 |
| UX-05 | **System back exits the app from any tab** (no `BackHandler`; `UploadScreen.kt:258`). Fix: `BackHandler(enabled = selectedTab != 0)`. Persist the tab for rotation. | S | 2× | UP-09 | 05 |
| UX-06 | **Accessibility: resize row is a double TalkBack target; "Advanced credentials" has no role/state.** `UploadScreen.kt:424-441`; `LoginScreen.kt:122-133`. Fix: `toggleable(role = Role.Checkbox)` with `onCheckedChange = null`; real button/semantics. | S | 2× | — | 05 |
| UX-07 | **i18n not viable: `strings.xml` is empty and copy is hardcoded** (`res/values/strings.xml`). Fix: extract user-visible copy screen by screen via `stringResource`. | M | 2× | — | 05 |
| UX-08 | **No night theme → white flash before Compose draws.** `res/values/themes.xml:3`. Fix: DayNight parent + `values-night/themes.xml` (or splash screen). | S | review | — | 05 |
| UX-09 | **`POST_NOTIFICATIONS` is requested on cold start, before it is needed, with no rationale.** `MainActivity.kt:47-50`; `ArtShowNotifier.kt:22`. Fix: request from the action that needs it + Settings state when denied. | S | 2× | — | 05, 06 |

### Platform / background

| ID | Task | Size | Ver | Dep | Src |
|---|---|---|---|---|---|
| PLAT-01 | **Reminder scheduling can crash on corrupt prefs, re-requests permission every launch, and uses the adaptive icon as `setSmallIcon`.** `ReminderScheduler.kt:19-21`; `ArtShowNotifier.kt:18,30`; `MainActivity.kt:47-51`. Fix: `runCatching`, clamp stored hour/minute, dedicated monochrome icon, channel once. | S | review | — | 06 |
| PLAT-02 | **`docs/bluesky-art-show-reminders.md` describes WorkManager-based scheduling; the code uses `AlarmManager`.** Fix: correct the doc and add the missing `ArtShowReminderWorker`/`ArtShowNotifier` test. | S | review | TEST-04 | 06 |

### Tests

| ID | Task | Size | Ver | Dep | Src |
|---|---|---|---|---|---|
| TEST-01 | **Add `MockWebServer` + `room-testing` and cover the data layer** (401→session, 500 not silently empty, TTL single-flight, mapper round trips, DAO). | M | 2× | DATA-04, DATA-05, SEC-04 | 04 F-19 |
| TEST-02 | **Add upload failure-path tests**: upload failure resets flags, 6-cap/duplicates, EXIF race, oversized-image bound, `content://` fixtures. | M | 2× | UP-01, UP-03, UP-04, UP-06 | 03, 06 |
| TEST-03 | **Add in-memory Room DAO tests** (insert/replace/count/order/purge) — none exist today. | M | 2× | REL-12 | 04, 06 |

---

## P3 — polish, performance, refactor, build

| ID | Task | Size | Ver | Src |
|---|---|---|---|---|
| REL-13 | **JDK/daemon drift**: CI runs JDK 17, the local daemon runs JBR 21, no `gradle-daemon-jvm.properties`, no shared setup action. `build.yml:42-44`; `.gradle/config.properties:2`. Fix: pin one JDK in both places. | S | 2× | 01#13, 02 |
| BUILD-02 | **2×2 flavor matrix multiplies test JVMs and R8 runs 4× for no functional coverage.** `app/build.gradle:27-46`. Fix: collapse to one `env` dimension or scope unit tests to one variant. | M | 2× | 02 |
| BUILD-03 | **Hand-rolled `testDebugUnitTest` alias is not a `Test` task** (breaks `--tests`, runs only one flavor, will collide if the flavor matrix is collapsed). `app/build.gradle:82-85`. | S | 2× | BUILD-02 | 02 |
| BUILD-05 | **`targetSdk 35` lags `compileSdk 37`** — silent opt-out of API 36/37 behaviour changes; Robolectric SDK follows `targetSdk`. `app/build.gradle:10,15`. | M | 2× | BUILD-04 | 02 |
| BUILD-06 | **ProGuard rules duplicate shipped library rules, and `SourceFile`/`LineNumberTable` are missing** so release stack traces are unmappable. `proguard-rules.pro:14-38`. | S | review | — | 02 |
| BUILD-08 | **`local` and `ci` variants share an OAuth redirect scheme**, so two coexisting installs make the callback ambiguous. `app/build.gradle:29-46`; `src/dev/res/values/strings.xml:2`. | S | review | — | 02 |
| BUILD-09 | **`coil = 2.7.0` is built against OkHttp 4.x while the app pins OkHttp 5.5.0**; plus AGP/Kotlin/Compose drift from `neurhome` and no `mavenCentral()` fallback. `libs.versions.toml:12,14`. | M | review | — | 02 |
| BUILD-10 | **Unused/redundant deps and dead config**: `material-icons-core` alongside `extended`, dead `androidTest*` block, `mockk-android`, `vectorDrawables`, empty `main/res/values/strings.xml`, `rootProject.name = "PixelfedClient"` vs "Pixlit". | S | review | — | 02 |
| BUILD-11 | **No Compose compiler metrics/stability configuration.** `app/build.gradle:62-64`. Fix: `composeCompiler { metricsDestination … }` + stability config. | S | review | — | 02 |
| BUILD-12 | **No baseline profile** (app classes absent from the merged profile). Fix: `:baselineprofile` module; skip `profileinstaller` at minSdk 34. | M | review | — | 02 |
| BUILD-13 | **KSP catalog comment says the Kotlin pairing is a guess** (`libs.versions.toml:25`). Fix or delete. | S | review | — | 02 |
| DATA-12 | **`searchPlaces` falls back to `pixelfed.social` when logged out and sends no auth.** `PixelfedRepository.kt:257-261`. | S | review | SEC-04 | 04 F-22 |
| DATA-13 | **`getFileFromUri` swallows the cause, uses `printStackTrace`, and does not `use {}` its streams.** `PixelfedRepository.kt:733-739`. | S | review | — | 04 F-23 |
| DATA-14 | **No ProGuard keep for `CachedTagSearch`, a private reflective Gson type**; no test runs against the minified prod variant. `PixelfedRepository.kt:39-41, 528-534`; `proguard-rules.pro:21`. | S | review | DATA-08 | 04 F-24 |
| DATA-15 | **Dead code and stale imports mislead readers** (`RegisterAppResponse`, `TokenResponse`, `getStatusesFlow`, `clearStatuses`, `getUserTopTags`, `cachedTagsJson`). | S | review | REL-12 | 04 F-21 |
| UP-13 | **Upload low-severity bundle**: `showTagPopup` never resets; shared `_isSearchingPlaces`; collections empty-fallback hides "none"; `addImages` leaves `_isError`; misleading "Load 10 more"; double tag-search per keystroke; repository swallows `CancellationException`. | S | review | REF-01 | 03 (LOW) |
| UX-10 | **`RecentPostCard` re-parses HTML and recompiles a regex per recomposition.** `RecentPostsSection.kt:71-82`. | S | 2× | — | 03, 05 |
| UX-11 | **Location search flashes "Use … as location name" during the debounce window.** `UploadViewModel.kt:187-203`; `PositionSelectionSection.kt:180-186`. | S | review | UP-03 | 05 |
| UX-12 | **Thumbnail row has no item keys and a ~54 dp touch target.** `OrderPreviewList.kt:48`. | S | review | — | 05 |
| UX-13 | **Dev builds double-apply the status-bar inset.** `src/dev/DevBanner.kt:22-27`; `UploadScreen.kt:321`. | S | review | — | 05 |
| UX-14 | **Hashtag popup is positioned from an assumed 8 dp glyph width.** `UploadScreen.kt:461-469`. | S | review | REF-01 | 05 |
| UX-15 | **Preview coverage uneven; no `testTag`s / `testTagsAsResourceId`.** Fix: previews for the four components + `@PreviewParameter` samples + root test tag. | S | review | — | 05 |
| UX-16 | **Collection-creation dialog closes and clears on tap even when creation fails** (`CollectionSelectionSection.kt:126-133`; `UploadViewModel.kt:272-290`). | S | review | DATA-05 | 05 |
| UX-17 | **Tab naming "Last" and prominent app-bar Logout hurt discoverability.** `UploadScreen.kt:259, 326-329`. | S | review | UX-03 | 05 |
| UX-18 | **Theming notes**: `dynamicColor = true` makes the brand palette dead; nav-bar icon appearance not set; notification small icon is the adaptive foreground; no predictive-back opt-in. `Theme.kt:46-67`; `ArtShowNotifier.kt:37`. | S | review | — | 05 |
| TEST-04 | **Reminder tests cover only `nextRunDelay` (2 UTC cases)** — add DST, PendingIntent identity, `ReminderPreferences` defaults, and a `TestListenableWorkerBuilder` fallback test. `ReminderSchedulerTest.kt:7-18`. | S | review | — | 06 |

---

## Wave 0 exit criteria

Wave 0 is the only wave whose acceptance is fully specified here; later waves get
their own task files when scheduled.

1. **REL-02** — after a `main` push, `aapt dump badging` of the released APK
   shows `versionName` equal to the pushed tag; a local `./gradlew assembleCiProdRelease`
   with `-Pandroid.versionName=9.9.9` shows `9.9.9`.
2. **REL-01** — the tag/release only appear after `testCiProdDebugUnitTest` +
   `lintCiProdRelease` pass; simulating a failing test leaves **no** new tag.
3. **REL-03/04** — CI fails on a deliberately broken unit test; the PR workflow
   is present and the release workflow validates `ciProd` before signing.
4. **SEC-01** — a release build's logcat contains no `Authorization` header and
   no `client_secret`/`access_token`; uploads still succeed.
5. **SEC-02** — the token file is unreadable as plaintext (encrypted) and is
   excluded from cloud backup/device transfer.
6. **UP-01** — resizing an oversized (e.g. 48 MP) fixture completes without OOM
   and produces a bounded bitmap; a regression test asserts the bound.
7. **REL-13** — CI and the local daemon use the same JDK version/vendor.
8. `./gradlew :app:testLocalDevDebugUnitTest` is green.

## Open decisions (need operator input before Wave 1/2)

1. **Release cadence** — every `main` push currently creates a GitHub release.
   Keep that, or release on demand (`workflow_dispatch` / tag push)?
2. **Branch protection** — `neurhome` deliberately does *not* require
   `required-checks`. Should Pixlit require it, given it has a release job on `main`?
3. **Min SDK 34 / JDK 21** — pin everything to JBR 21 like `neurhome`, or keep
   the CI JDK at 17 and add a daemon pin for 17 instead?
4. **Flavor matrix** — collapse the `distribution` dimension, or keep four
   variants and scope unit tests to one?
5. **Credential storage** — `EncryptedSharedPreferences` is the quick fix;
   DataStore + Keystore is the durable one. Which now?
6. **Upload execution model** — foreground `@HiltWorker` vs. keeping the
   ViewModel upload and adding only progress/cancel. The report recommends the
   worker for durability.
7. **`docs/review/`** — these six reports are currently untracked. Commit them
   as the evidence base, or keep them out of git and rely on this backlog?
