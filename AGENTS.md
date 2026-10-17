# AI Agent Project Guidelines

This document provides context and instructions for AI agents working on the Pixlit project. It
describes the **shipped** code and CI, not a plan.

## Project Context

- **Name:** Pixlit (Pixelfed Client)
- **Namespace:** `ovh.litapp.pixlit`
- **Main Goal:** Create a high-performance image uploader for Pixelfed, tailored for a single user
  (`carlo-colombo`). The primary focus is the upload flow; consumption (timeline/browsing) is
  explicitly out of scope.
- **License:** GPL-3.0-or-later. See [License](#license) below.

## Architecture

- **Pattern:** MVVM (Model-View-ViewModel) with a focus on Unidirectional Data Flow.
- **UI:** Jetpack Compose + Material 3. The upload screen collects exactly one flow,
  `UploadViewModel.uiState` (`UploadUiState`), and passes one `UploadCallbacks` object down; the
  former 27 per-field `MutableStateFlow`s and the 45-parameter composable are gone. Tab bodies are
  extracted (`UploadTabContent`, `RecentPostsTabContent`, `DebugTabContent`, `SettingsTabContent`)
  and the Social screen lives in `ui/social/`, outside the upload feature.
- **Dependency Injection:** Hilt. `PixlitApplication` provides the worker factory; modules live in
  `di/` (`NetworkModule`, `DatabaseModule`).
- **Repository seams:** `PixelfedRepository` is a thin facade over extracted seams (the old
  740-line, 14-responsibility class):

  | Package | Seam | Responsibility |
  |---|---|---|
  | `data/tags` | `TagAggregator` | Pure tag extraction/ranking (no Android dependency) |
  | `data/tags` | `TagSearchCache` | Room-backed hashtag-search cache with a TTL |
  | `data/auth` | `OAuthParsers`, `Pkce`, `TokenManager`, `AuthInterceptor` | OAuth response parsing, S256 PKCE, encrypted credential store + session state, bearer token attachment and 401 reporting |
  | `data/session` | `SessionRepository` | App registration and authorization-code exchange |
  | `data/media` | `MediaUploader`, `UploadFileStore` | Multipart media upload and the prepared-file lifecycle |
  | `data/status` | `StatusRemoteSource`, `StatusCache` | Single-flight status refresh and Room read/write + DTO mapping |
  | `data/api` | `PixelfedApi`, `PixelfedApiFactory`, `BlueskyApi` | Retrofit interfaces; the factory memoizes one `PixelfedApi` per instance URL (no per-request `Retrofit` rebuild) |

  `data/Fetch` is the typed read result (`Fresh` / `Cached` / `Failed`) that replaced
  error-swallowing `Result<T>`s, and `data/repository/AccountIdCache` memoizes
  `verify_credentials` per instance+token.
- **Persistence:**
  - **Room** for the status + tag-search cache. `AppDatabase` is at **version 3** with
    `exportSchema = true`; the generated schema is committed at
    `app/schemas/ovh.litapp.pixlit.data.db.AppDatabase/3.json`. Every later schema change must bump
    the version and ship a migration (`MIGRATION_2_3` is the current example).
  - **DataStore (Preferences)** `pixlit_reminders` for the Friday/Saturday reminder times
    (`ReminderPreferences`); no `SharedPreferences`, no blocking `commit()`.
  - **EncryptedSharedPreferences** (Keystore-backed `MasterKey`) for the OAuth token and client
    secret (`TokenManager`).
- **Background work:**
  - Uploads run in a `@HiltWorker` (`data/work/UploadWorker`) enqueued by `UploadScheduler`, not in
    `viewModelScope`. The worker runs a foreground service of type `dataSync`, checkpoints every
    uploaded media id through `UploadJobStore`, and passes the job id as the stable
    `Idempotency-Key` so a retry can neither re-upload media nor duplicate the post.
  - Art Show reminders are `AlarmManager` inexact, idle-tolerant alarms (`ReminderScheduler`);
    WorkManager is used only for the optional Bluesky theme fetch (`ArtShowReminderWorker`).
- **Networking:** Retrofit + OkHttp + Gson. The shared `OkHttpClient` sets connect/read/write/call
  timeouts, attaches `AuthInterceptor`, and installs HTTP logging only for debug builds at
  `Level.BASIC` with the `Authorization` header redacted — never `BODY`.

## Coding Standards

- **Kotlin:** Follow idiomatic Kotlin practices. Prefer `val` over `var`, and use `Data` classes
  for models.
- **Compose:** Use state hoisting. Aim for reusable, stateless composables.
- **Naming:** Follow standard Android/Kotlin naming conventions (`PascalCase` for classes,
  `camelCase` for functions and variables).
- **Documentation:** Use KDoc for public APIs and complex logic. Comments explain a non-obvious
  constraint (why), not what the code does.

## Tools & Libraries

- **Networking:** Retrofit for API definitions, OkHttp for the client, Gson for JSON.
- **Images:** Coil for asynchronous image loading (`coil-compose`, v2.x).
- **Testing:** JUnit 4, Robolectric, MockWebServer, Room testing, Turbine, MockK, and
  `androidx.work:work-testing`.
- **Persistence:** Room, DataStore (Preferences), EncryptedSharedPreferences.

## Build

### Toolchain and variants

- **Gradle:** the wrapper (`gradle-9.8.1`). Always use `./gradlew`.
- **JVM:** Java 21 source/target, compiled with the JetBrains Runtime 21 (see
  [JVM policy](#jvm-policy)).
- **SDK levels:** `minSdk 34`, `targetSdk 37`, `compileSdk 37`.
- **Variants:** one flavor dimension, `env` (`dev`, `prod`) × build types `debug`, `release` →
  `devDebug`, `devRelease`, `prodDebug`, `prodRelease`. The old `local`/`ci` distribution dimension
  is gone.
  - `dev`: application id suffix `.dev` (`ovh.litapp.pixlit.dev`), label `Pixlit Dev`, OAuth
    redirect scheme `pixlit-dev`, `BuildConfig.DEBUG_TOOLS = true` (Debug tab + simulate notification).
  - `prod`: application id `ovh.litapp.pixlit`, label `Pixlit`, OAuth redirect scheme `pixlit`,
    `BuildConfig.DEBUG_TOOLS = false`.
  - Release build types enable R8 minification and resource shrinking.
- **Fresh worktree:** Android Gradle Plugin needs an SDK path. A new worktree has no
  `local.properties` until you create one; without it the build fails with "SDK location not found".
  Create `local.properties` (gitignored) with:

  ```properties
  sdk.dir=/path/to/Android/Sdk
  ```

### Common commands

All commands are run from the repository root.

| Purpose | Command |
|---|---|
| Assemble the local dev APK | `./gradlew :app:assembleDevDebug` |
| Run the dev unit tests | `./gradlew :app:testDevDebugUnitTest` |
| Lint the dev debug variant | `./gradlew :app:lintDevDebug` |
| Prod gate (pre-release, mirrors CI) | `./gradlew :app:testProdDebugUnitTest :app:lintProdRelease` |
| Assemble the release APK | `./gradlew :app:assembleProdRelease` |

APK outputs:
`app/build/outputs/apk/dev/debug/app-dev-debug.apk` and
`app/build/outputs/apk/prod/release/app-prod-release-unsigned.apk`.

AGP creates unit-test tasks only for the tested build type (`debug`), so `testDevDebugUnitTest` and
`testProdDebugUnitTest` are the full set — there is no `testProdReleaseUnitTest`. Use
`lintProdRelease` (not `lintProdDebug`) for the release gate. CI passes
`-Pandroid.versionName` / `-Pandroid.versionCode`; local builds fall back to `1.0.0` / `1`.

### Robolectric (offline, API 34)

The JVM test stack runs Robolectric on **API 34** (`sdk=34` in
`app/src/test/resources/robolectric.properties`) while the app compiles against SDK 37. To avoid a
live Maven fetch during test initialisation, `androidAllInstrumented` is declared in the version
catalog, `copyRobolectricAndroidAll` stages that jar, and the tests run with
`robolectric.offline=true`. **Keep the catalog version in lockstep with the `sdk=34` pin** or every
Robolectric test fails with "Unable to locate dependency". JVM tests do not cover API 35+ runtime
behaviour; validate such changes on a device/emulator.

## CI and Release Invariants

- **Workflows:** `.github/workflows/pr.yml` runs on pull requests; `.github/workflows/build.yml`
  runs on every push to `main` (release). They share
  `.github/actions/setup-jdk-gradle/action.yml`.
- **PR workflow** (`pr.yml`): a `changes` job ignores paths that cannot affect the Android build
  (`docs/**`, `**/*.md`, `LICENSE`, `.github/dependabot.yml`); the `build` job runs
  `:app:assembleDevDebug :app:testDevDebugUnitTest :app:lintDevDebug`; an always-on
  `required-checks` job reports even for skipped docs-only PRs so branch protection never hangs.
- **Validate before tag:** `build.yml` computes the tag with `DRY_RUN: true`, builds
  `:app:assembleProdRelease`, runs `:app:testProdDebugUnitTest :app:lintProdRelease`, and **only
  then** pushes the tag (pinned via `CUSTOM_TAG`). A validation failure can never leave a tag
  without a release. `cancel-in-progress: false` so an in-flight release is not cancelled once its
  tag exists.
- **SHA-pinned actions:** every third-party action is pinned to a commit SHA (with a `# vN` comment).
  Do not replace a pin with a mutable tag. Dependabot keeps the pins fresh.
- **Least privilege:** `permissions: contents: read` at workflow level; `contents: write` only on
  the push-only release job.
- **Signing:** build-tools are resolved dynamically from the installed SDK (no hardcoded version),
  and the step fails fast when `SIGNING_KEY`, `ALIAS`, or `KEY_STORE_PASSWORD` is missing — there
  are no fallback defaults.
- **Dependabot automerge:** `dependabot.yml` updates gradle and github-actions weekly (grouped
  minor/patch). `pr.yml`'s `automerge` job merges Dependabot PRs once `build`/`required-checks`
  pass, using a PAT stored as **`DEPENDABOT_TOKEN`**. This secret must be configured as a
  **Dependabot secret** (Settings → Secrets and variables → Dependabot), not an Actions secret:
  Dependabot-triggered runs get a read-only `GITHUB_TOKEN` and do not receive Actions secrets. The
  PAT needs `contents: write` + `pull-requests: write`. When the secret is absent the job warns and
  skips instead of failing.
- **License and notices:** after signing, `build.yml` asserts the APK contains
  `assets/licenses/{LICENSE,NOTICE,THIRD_PARTY_LICENSES.md}` before publishing. The release attaches
  those three files, generates a `sha256sum` `.sha256` sidecar, and uses `retention-days: 90`.

## JVM Policy

Pin the **JetBrains Runtime 21** everywhere — one JVM vendor/version, kept in lockstep:

- Local daemon: `gradle/gradle-daemon-jvm.properties` (`toolchainVendor=JETBRAINS`,
  `toolchainVersion=21`). With any other `JAVA_HOME`, Gradle provisions a second JDK into
  `~/.gradle/jdks` on every run.
- CI: `.github/actions/setup-jdk-gradle/action.yml` installs `java-version: 21`,
  `distribution: jetbrains`, then `gradle/actions/setup-gradle`.

Keep those two files the only places that name the JDK vendor/version.

## Credential and Secret Model

**In the app:**

- OAuth2 per instance. The user either lets Pixlit register the client, or enters a client id/secret.
  The default instance is `https://pixelfed.social`; scopes are `read write` (no DMs, no follows).
- The access token and client secret are stored in Keystore-backed `EncryptedSharedPreferences`, and
  the credential file is excluded from Android Auto Backup and device-to-device transfer
  (`app/src/main/res/xml/data_extraction_rules.xml`).
- `AuthInterceptor` attaches the bearer token and reports 401 through `TokenManager`; a revoked
  token moves the session to `Expired` and routes the UI back to login.
- PKCE (S256) and an anti-CSRF `state` protect the authorization-code flow. The PKCE verifier is
  **in-memory only** and consumed once with the token exchange.

**In CI:**

- Signing secrets: `SIGNING_KEY` (base64 keystore), `ALIAS`, `KEY_STORE_PASSWORD`. No defaults.
- `DEPENDABOT_TOKEN` (Dependabot secret) for automerge.
- `GITHUB_TOKEN` for tag/release.
- Never commit `local.properties`, keystores, or tokens; only the documented secrets exist.

## License

Pixlit is free software, licensed under the **GNU General Public License, version 3 or later**
(`GPL-3.0-or-later`). Copyright (C) 2026 Carlo Colombo.

The root `LICENSE`, `NOTICE`, and `THIRD_PARTY_LICENSES.md` are the single source of truth. The
`stageLicenseAssets` Gradle task copies them into `assets/licenses/` for every variant, and the
app shows them under Settings → About. Do not edit a packaged copy — edit the root file.

## Working with the Pixelfed API

- Refer to the official [Pixelfed API Documentation](https://docs.pixelfed.org/technical-documentation/api/).
- Authentication is handled via OAuth2 (authorization code + S256 PKCE).
