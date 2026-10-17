# Repo Review Remediation — Task Index

Individual, PR-sized task files derived from [`docs/backlog.md`](../../backlog.md),
which was produced from the six-reviewer repo sweep in [`docs/review/`](../../review/).
No code has been changed while producing these tasks.

**90 tasks** (one per backlog ID), all `Status: Todo`. Wave 0 is ready to
schedule; see [Execution model](#execution-model).

Source of truth for a task's priority, size, evidence and dependencies is its own
file; this index is a generated view of `Wave / Priority / Size / Dependencies`.

## Status

| Wave | Theme | Tasks | State |
|---|---|---|---|
| 0 | Release stamp/ordering, security, image OOM | 1–12 | Ready to schedule |
| 1 | Upload reliability, data correctness, onboarding | 13–24 | Blocked on Wave 0 interfaces |
| 2 | CI to the blueprint standard | 25–37 | Planned |
| 3 | Remaining data/perf/build hardening | 34–54 (excl. Wave 2 items) | Planned |
| 4 | Refactor, UX, a11y, tests, licensing | 55–90 | Planned |

## How to read

| Field | Meaning |
|---|---|
| ID | Backlog ID (`REL`/`SEC`/`UP`/`DATA`/`BUILD`/`REF`/`UX`/`PLAT`/`TEST`) |
| Pri | P0 = release/security/crash/data-loss · P1 = user-visible defect or major perf · P2 = quality gate, robustness, build health · P3 = polish, perf, refactor |
| Size | S ≈ <1 file · M ≈ a few files or schema/config · L ≈ cross-cutting |
| Dep | must land before this task |
| Wave | delivery wave; only file-disjoint tasks run concurrently |

Each task file has: **Problem** (with `file:line` evidence), **Scope**
(file-level changes), **Acceptance criteria**, **Dependencies**, **Verification**.

## Execution model

- One writer per worktree; within a wave only **file-disjoint** tasks run in
  parallel. `app/build.gradle`, `PixelfedRepository.kt`, `UploadViewModel.kt`
  and `MainViewModel.kt` are the main serialisation points — check the
  `Dependencies` and `Scope` sections before co-scheduling.
- Wave 0 is planned as four file-disjoint lanes (from the backlog):
  `release` (`.github/**`, `app/build.gradle` version block, daemon JVM),
  `build` (`gradle.properties`, ProGuard, `.gitignore`, catalog),
  `security` (`NetworkModule`, `TokenManager`, `MainActivity`/`MainViewModel`,
  manifest, `res/xml`), `image` (`ImageUtils` + its test).
- Commit the reviewed evidence (`docs/review/`, this index) and any newly
  exported Room schema with the task that produces them.

## Verification commands

- Build: `./gradlew :app:assembleLocalDevDebug`
- Unit: `./gradlew :app:testLocalDevDebugUnitTest`
- Prod gate (required pre-release): `./gradlew :app:testCiProdDebugUnitTest :app:lintCiProdRelease`
- Release: `aapt dump badging app/build/outputs/apk/ciProd/release/*.apk | grep versionName`
- Reports: `app/build/reports/tests/**`, `app/build/reports/lint-results-*.html`

## Tasks

| # | ID | Title | Wave | Pri | Size | Dep |
|---|----|-------|------|-----|------|-----|
| 1 | [REL-01](tasks/task-01-rel-01-tag-before-build.md) | Tag is pushed before the APK is built, signed or validated (orphan tags) | 0 | P0 | M | — |
| 2 | [REL-02](tasks/task-02-rel-02-stamp-version-from-tag.md) | `-Pandroid.versionName` is a no-op; every released APK claims `1.0.0` / `versionCode 1` | 0 | P0 | S | — |
| 3 | [REL-03](tasks/task-03-rel-03-run-tests-in-ci.md) | CI runs no unit tests and no full lint | 0 | P1 | S | — |
| 4 | [REL-04](tasks/task-04-rel-04-validate-prod-before-signing.md) | No prod-variant validation before signing/release | 0 | P1 | S | REL-01 |
| 5 | [REL-05](tasks/task-05-rel-05-resolve-build-tools-and-sign.md) | Signing depends on an unmaintained action + hardcoded build-tools `34.0.0` while `compileSdk 37` | 0 | P1 | M | — |
| 6 | [REL-13](tasks/task-06-rel-13-pin-jdk-and-daemon-jvm.md) | Pin the JDK and Gradle daemon JVM | 0 | P3 | S | — |
| 7 | [BUILD-01](tasks/task-07-build-01-gradle-caching-config-cache.md) | Enable Gradle caching and configuration cache | 0 | P2 | S | — |
| 8 | [REL-15](tasks/task-08-rel-15-ignore-kotlin-artifacts.md) | Ignore `.kotlin` and `.artifacts` | 0 | P3 | S | — |
| 9 | [SEC-01](tasks/task-09-sec-01-gate-http-logging-timeouts.md) | Gate HTTP logging and add timeouts | 0 | P0 | S | — |
| 10 | [SEC-02](tasks/task-10-sec-02-encrypt-token-storage-backup.md) | Encrypt token storage and exclude it from backup | 0 | P0 | M | — |
| 11 | [SEC-04](tasks/task-11-sec-04-401-session-handling.md) | Add 401 and session handling | 0 | P1 | M | — |
| 12 | [UP-01](tasks/task-12-up-01-bound-image-downscale-oom.md) | Bound the image downscale to avoid OOM | 0 | P0 | M | — |
| 13 | [UP-02](tasks/task-13-up-02-handle-intents-once.md) | Handle intents exactly once | 1 | P1 | S | — |
| 14 | [UP-03](tasks/task-14-up-03-exif-location-race.md) | Stop the EXIF location race | 1 | P1 | S | — |
| 15 | [UP-04](tasks/task-15-up-04-cancel-metadata-work.md) | Cancel superseded metadata work | 1 | P1 | S | — |
| 16 | [UP-05](tasks/task-16-up-05-durable-upload-progress-cancel.md) | Make uploads durable with progress and cancel | 1 | P1 | L | REL-03 |
| 17 | [UP-06](tasks/task-17-up-06-gate-upload-form.md) | Gate the upload form and dedupe picks | 1 | P1 | S | — |
| 18 | [DATA-01](tasks/task-18-data-01-off-main-asset-parse.md) | Parse the status asset off the main thread | 1 | P1 | S | — |
| 19 | [DATA-02](tasks/task-19-data-02-account-id-cache.md) | Cache the account id and parallelize bootstrap | 1 | P1 | M | SEC-04 |
| 20 | [DATA-03](tasks/task-20-data-03-refresh-ttl-single-flight.md) | Add TTL and single-flight to status refresh | 1 | P1 | M | — |
| 21 | [DATA-04](tasks/task-21-data-04-persist-tags-media-created-at.md) | Persist tags, media and the real created_at | 1 | P1 | M | — |
| 22 | [DATA-05](tasks/task-22-data-05-typed-fetch-results.md) | Return typed fetch results instead of swallowed errors | 1 | P1 | M | — |
| 23 | [UX-01](tasks/task-23-ux-01-login-recovery.md) | Recover the login screen on failure | 1 | P1 | S | — |
| 24 | [UX-02](tasks/task-24-ux-02-debug-tools-gate.md) | Gate debug tools out of prod | 1 | P1 | S | — |
| 25 | [REL-06](tasks/task-25-rel-06-sha-pin-actions.md) | SHA-pin all GitHub Actions | 2 | P2 | S | — |
| 26 | [REL-07](tasks/task-26-rel-07-least-privilege-permissions.md) | Least-privilege workflow permissions | 2 | P2 | S | — |
| 27 | [REL-08](tasks/task-27-rel-08-fail-fast-signing-secrets.md) | Fail fast on missing signing secrets | 2 | P2 | S | REL-05 |
| 28 | [REL-09](tasks/task-28-rel-09-pr-workflow-required-checks.md) | Add a PR workflow with always-on required checks | 2 | P2 | M | REL-03 |
| 29 | [REL-10](tasks/task-29-rel-10-dependabot.md) | Add Dependabot configuration | 2 | P2 | S | REL-06 |
| 30 | [REL-11](tasks/task-30-rel-11-checksums-retention.md) | Generate artifact checksums and set retention | 2 | P2 | S | — |
| 31 | [REL-12](tasks/task-31-rel-12-room-schema-export.md) | Configure and gate the Room schema export | 2 | P2 | M | BUILD-12 |
| 32 | [REL-14](tasks/task-32-rel-14-remove-dead-workflow-config.md) | Remove dead workflow config | 2 | P2 | S | — |
| 33 | [SEC-03](tasks/task-33-sec-03-oauth-state-pkce.md) | Add OAuth state and PKCE | 2 | P1 | S | — |
| 34 | [SEC-05](tasks/task-34-sec-05-drop-media-permissions.md) | Drop unused media permissions | 3 | P2 | S | — |
| 35 | [SEC-06](tasks/task-35-sec-06-narrow-oauth-scopes.md) | Narrow OAuth scopes | 3 | P2 | S | SEC-03 |
| 36 | [BUILD-04](tasks/task-36-build-04-robolectric-offline-resources.md) | Make Robolectric offline and resource-aware | 2 | P2 | M | — |
| 37 | [BUILD-07](tasks/task-37-build-07-lint-gate-baseline.md) | Add a lint gate with a baseline | 2 | P2 | M | REL-03 |
| 38 | [DATA-06](tasks/task-38-data-06-split-repository-seams.md) | Split the 740-line repository along six seams | 3 | P2 | L | DATA-04 |
| 39 | [DATA-07](tasks/task-39-data-07-count-cache-projection.md) | Count the cache with a projection query | 3 | P2 | S | — |
| 40 | [DATA-08](tasks/task-40-data-08-tag-search-cache-room.md) | Move the tag-search cache into Room | 3 | P2 | M | REL-12 |
| 41 | [DATA-09](tasks/task-41-data-09-datastore-migration.md) | Migrate preferences to DataStore | 3 | P2 | M | SEC-02 |
| 42 | [DATA-10](tasks/task-42-data-10-dto-nullability-fixtures.md) | Make DTO nullability explicit with fixtures | 3 | P2 | M | TEST-01 |
| 43 | [DATA-11](tasks/task-43-data-11-status-db-index-export-purge.md) | Index, export and purge the status database | 3 | P2 | M | REL-12 |
| 44 | [BUILD-02](tasks/task-44-build-02-collapse-flavor-matrix.md) | Collapse the flavor matrix | 3 | P2 | M | — |
| 45 | [BUILD-03](tasks/task-45-build-03-remove-test-alias.md) | Remove the `testDebugUnitTest` alias task | 3 | P2 | S | BUILD-02 |
| 46 | [BUILD-05](tasks/task-46-build-05-targetsdk-robolectric-pin.md) | Raise targetSdk with a Robolectric pin | 3 | P2 | M | BUILD-04 |
| 47 | [BUILD-06](tasks/task-47-build-06-proguard-rules-line-numbers.md) | Clean up ProGuard rules and keep line numbers | 3 | P3 | S | — |
| 48 | [BUILD-08](tasks/task-48-build-08-per-variant-oauth-scheme.md) | Give each variant its own OAuth redirect scheme | 3 | P3 | S | — |
| 49 | [UP-07](tasks/task-49-up-07-delete-temp-files.md) | Delete temporary upload files | 3 | P2 | M | — |
| 50 | [UP-08](tasks/task-50-up-08-prepare-image-once.md) | Prepare each image exactly once | 3 | P2 | M | UP-05 |
| 51 | [UP-09](tasks/task-51-up-09-preserve-draft.md) | Preserve the draft across process death | 3 | P2 | M | — |
| 52 | [UP-10](tasks/task-52-up-10-per-image-alt-text.md) | Send per-image alt text, clamped | 3 | P2 | M | UP-05 |
| 53 | [UP-11](tasks/task-53-up-11-source-format-exif-policy.md) | Preserve source format and define the EXIF policy | 3 | P2 | M | — |
| 54 | [UP-12](tasks/task-54-up-12-resize-error-handling.md) | Harden resize error handling and stream cleanup | 3 | P2 | M | UP-01 |
| 55 | [REF-01](tasks/task-55-ref-01-uploaduistate-split.md) | Introduce UploadUiState and split the ViewModel/screen | 4 | P2 | L | UP-03, UP-04 |
| 56 | [REF-02](tasks/task-56-ref-02-extract-tab-composables.md) | Extract tab content composables | 4 | P2 | M | UX-02 |
| 57 | [REF-03](tasks/task-57-ref-03-repository-seams.md) | Repository seams (alias of DATA-06) | 4 | P2 | S | DATA-06 |
| 58 | [UX-03](tasks/task-58-ux-03-destructive-confirmations-snackbars.md) | Confirm destructive actions and add snackbars | 4 | P2 | S | — |
| 59 | [UX-04](tasks/task-59-ux-04-keyboard-large-font-layout.md) | Fix keyboard and large-font layout | 4 | P2 | M | — |
| 60 | [UX-05](tasks/task-60-ux-05-back-tabs-persist.md) | Handle back within tabs and persist the tab | 4 | P2 | S | UP-09 |
| 61 | [UX-06](tasks/task-61-ux-06-accessibility-roles.md) | Fix accessibility roles and states | 4 | P2 | S | — |
| 62 | [UX-07](tasks/task-62-ux-07-extract-strings-i18n.md) | Extract strings for i18n | 4 | P2 | M | — |
| 63 | [UX-08](tasks/task-63-ux-08-night-theme.md) | Add a night theme to stop the white flash | 4 | P2 | S | — |
| 64 | [UX-09](tasks/task-64-ux-09-notification-permission-context.md) | Request notification permission in context | 4 | P2 | S | — |
| 65 | [UX-10](tasks/task-65-ux-10-recent-posts-html-remember.md) | Remember recent-post HTML parsing | 4 | P3 | S | — |
| 66 | [UX-11](tasks/task-66-ux-11-location-search-flash.md) | Stop the location-search flash | 4 | P3 | S | UP-03 |
| 67 | [UX-12](tasks/task-67-ux-12-thumbnail-keys-touch-targets.md) | Key the thumbnail list and enlarge touch targets | 4 | P3 | S | — |
| 68 | [UX-13](tasks/task-68-ux-13-dev-banner-inset.md) | Fix the dev-banner double inset | 4 | P3 | S | — |
| 69 | [UX-14](tasks/task-69-ux-14-hashtag-popup-position.md) | Measure the hashtag popup position | 4 | P3 | S | REF-01 |
| 70 | [UX-15](tasks/task-70-ux-15-previews-test-tags.md) | Add previews and test tags | 4 | P3 | S | — |
| 71 | [UX-16](tasks/task-71-ux-16-collection-create-failure.md) | Surface collection-create failures | 4 | P3 | S | DATA-05 |
| 72 | [UX-17](tasks/task-72-ux-17-tab-naming-logout.md) | Rename tabs and relocate logout | 4 | P3 | S | UX-03 |
| 73 | [UX-18](tasks/task-73-ux-18-theming-polish.md) | Theming polish notes | 4 | P3 | S | — |
| 74 | [PLAT-01](tasks/task-74-plat-01-harden-reminder-scheduling.md) | Harden reminder scheduling | 4 | P2 | S | — |
| 75 | [PLAT-02](tasks/task-75-plat-02-reminder-design-doc-and-tests.md) | Fix the reminder design doc and add tests | 4 | P2 | S | TEST-04 |
| 76 | [BUILD-09](tasks/task-76-build-09-coil-okhttp-and-repo-fallback.md) | Align Coil and OkHttp and add a repo fallback | 4 | P3 | M | — |
| 77 | [BUILD-10](tasks/task-77-build-10-prune-unused-deps-and-dead-config.md) | Prune unused dependencies and dead config | 4 | P3 | S | — |
| 78 | [BUILD-11](tasks/task-78-build-11-compose-compiler-metrics-and-stability.md) | Add Compose compiler metrics and stability config | 4 | P3 | S | — |
| 79 | [BUILD-12](tasks/task-79-build-12-baseline-profile-module.md) | Add a baseline profile module | 4 | P3 | M | — |
| 80 | [BUILD-13](tasks/task-80-build-13-ksp-catalog-comment.md) | Fix the KSP catalog comment | 4 | P3 | S | — |
| 81 | [DATA-12](tasks/task-81-data-12-place-search-fallback.md) | Stop the logged-out place-search fallback | 4 | P3 | S | SEC-04 |
| 82 | [DATA-13](tasks/task-82-data-13-file-uri-errors.md) | Handle file-URI errors properly | 4 | P3 | S | — |
| 83 | [DATA-14](tasks/task-83-data-14-cachedtagsearch-r8-keep.md) | Keep CachedTagSearch under R8 | 4 | P3 | S | DATA-08 |
| 84 | [DATA-15](tasks/task-84-data-15-remove-dead-data-code.md) | Remove dead data-layer code | 4 | P3 | S | REL-12 |
| 85 | [TEST-01](tasks/task-85-test-01-data-layer-mockwebserver-tests.md) | Add data-layer tests with MockWebServer | 4 | P2 | M | DATA-04, DATA-05, SEC-04 |
| 86 | [TEST-02](tasks/task-86-test-02-upload-failure-path-tests.md) | Add upload failure-path tests | 4 | P2 | M | UP-01, UP-03, UP-04, UP-06 |
| 87 | [TEST-03](tasks/task-87-test-03-in-memory-room-dao-tests.md) | Add in-memory Room DAO tests | 4 | P2 | M | REL-12 |
| 88 | [TEST-04](tasks/task-88-test-04-reminder-scheduling-tests.md) | Add reminder scheduling tests | 4 | P3 | S | — |
| 89 | [UP-13](tasks/task-89-up-13-upload-low-severity-bundle.md) | Upload low-severity fixes bundle | 4 | P3 | S | REF-01 |
| 90 | [REL-16](tasks/task-90-rel-16-copyright-and-license.md) | Add a copyright notice and an open-source license, shipped with the app | 4 | P2 | M | REL-11, BUILD-07 |

## Open decisions

Seven decisions block Wave 1/2 — see
[`docs/backlog.md` § Open decisions](../../backlog.md#open-decisions-need-operator-input-before-wave-12):
release cadence, branch protection, JDK pin, flavor matrix, credential storage,
upload execution model, and whether to commit `docs/review/`.
