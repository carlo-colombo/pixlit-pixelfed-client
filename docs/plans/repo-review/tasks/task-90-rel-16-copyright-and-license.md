# Task 90 — REL-16: Add a copyright notice and an open-source license, and ship it with the app

**Wave 4 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: repo state (this task is not from the
six-reviewer sweep; it was added after `neurhome` shipped the same change in commit `db45ece`)

## Problem

Pixlit publishes a signed APK on every push to `main` but states no terms for it and attributes
almost none of its dependencies:

- **No project license.** `ls LICENSE* NOTICE*` at the repo root matches nothing and `README.md`
  has no License heading, so a redistribution has no granted rights.
- **The release carries no license document.** `build.yml:243-258` uploads and releases only
  `pixelfed-client-<tag>.apk` and its `.sha256`.
- **The APK carries no project license and only partial attribution.** `app-ci-prod-release.apk`
  contains exactly five AndroidX `META-INF/**/LICENSE.txt` files and one dependency
  `META-INF/NOTICE.md` (Eclipse Jakarta Dependency Injection). Retrofit, Gson, Coil, Hilt,
  WorkManager and OkHttp contribute no notice, and `app/build.gradle:92-94` additionally drops the
  generic `/META-INF/{AL2.0,LGPL2.1}` files.

`neurhome` closed the identical gap in `db45ece`; this task ports that shape to Pixlit's Groovy
build and its tabbed UI.

## Scope

- Add `LICENSE` (GPL-3.0-or-later, matching `neurhome` unless the operator chooses otherwise),
  `NOTICE` (copyright line, SPDX identifier, source URL, no-warranty sentence) and
  `THIRD_PARTY_LICENSES.md` (one entry per resolved runtime artifact, grouped by license, with the
  full Apache-2.0/BSD-3-Clause/MIT texts) at the repo root.
  - Audit the list from `:app:dependencies --configuration ciProdReleaseRuntimeClasspath`; record
    each artifact's declared license from its published POM. Do not hand-wave the transitive set.
- Add a `## License` section to `README.md`.
- Stage the three root files into the APK assets as `assets/licenses/*` from a **generated**
  directory rather than a committed copy under `app/src/main/assets`.
  - `neurhome` does this in `app/build.gradle.kts:58-69` (`licenseAssetsDir` + `stageLicenseAssets`
    `Copy` task) and `:184-191` (`androidComponents { onVariants { … addGeneratedSourceDirectory(…) } }`).
    Port it to Groovy. AGP 9 refuses Provider-backed outputs in the legacy `sourceSets` API, so the
    Variant API is required; verify the Groovy call against the AGP `SourceDirectories.Flat` docs.
- Surface the documents in the app. Pixlit has no About screen, so add one inside the existing
  Settings tab (`UploadScreen.kt:263-267`; prod index 3, with `DEBUG_TOOLS` index 4): version from
  `BuildConfig.VERSION_NAME`, copyright line, the packaged license and third-party texts, and a
  Corresponding Source link. Keep it a stateless composable behind the tab dispatch so the
  `DEBUG_TOOLS` index arithmetic is untouched.
- In `build.yml`, before publishing: verify the signed APK contains the three assets, attach
  `LICENSE`/`NOTICE`/`THIRD_PARTY_LICENSES.md` to the uploaded artifact and to the
  `softprops/action-gh-release` `files:` list, and add a release `body:` that states the SPDX
  identifier and the Corresponding Source tag.
- Add a JVM test that pins the packaged assets (mirror `neurhome`'s
  `app/src/test/java/ovh/litapp/neurhome3/LicenseAssetsTest.kt`) so a broken assets wiring fails
  `:app:testCiProdDebugUnitTest` rather than shipping an APK with no license.
- Leave the GPLv3 `LICENSE` text byte-identical — do not edit the appendix placeholders; record the
  holder in `NOTICE`, `README.md` and per-file headers on new files.

## Acceptance criteria

- [ ] `LICENSE`, `NOTICE` and `THIRD_PARTY_LICENSES.md` exist at the repo root; `README.md` has a `## License` section.
- [ ] `unzip -l app/build/outputs/apk/ciProd/release/*.apk` lists `assets/licenses/LICENSE`, `assets/licenses/NOTICE` and `assets/licenses/THIRD_PARTY_LICENSES.md`.
- [ ] The Settings tab shows the version, copyright, license text, third-party notices and a source link.
- [ ] The release workflow verifies the assets, attaches all three documents and its body names the SPDX id and the source tag.
- [ ] The new asset test passes in `:app:testCiProdDebugUnitTest`.

## Dependencies

- REL-11 (defines the release artifact list this task extends), BUILD-07 (lint gate — the new UI must be lint-clean).
- Serialisation: this task edits `build.yml` (REL-01…REL-11) and `UploadScreen.kt` (REF-01/REF-02); do not co-schedule with those.

## Verification

- `./gradlew :app:assembleCiProdRelease`
- `unzip -l app/build/outputs/apk/ciProd/release/app-ci-prod-release-unsigned.apk`
- `./gradlew :app:testCiProdDebugUnitTest :app:lintCiProdRelease`
- Change a byte in the root `LICENSE`, rebuild, and confirm the packaged `assets/licenses/LICENSE` changes: that proves the asset is generated from the root file, not a stale committed copy.
