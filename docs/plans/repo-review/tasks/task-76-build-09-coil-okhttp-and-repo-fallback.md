# Task 76 — BUILD-09: Align Coil and OkHttp and add a repo fallback

**Wave 4 · Priority P3 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) medium-supply-chain

## Problem
`gradle/libs.versions.toml:12,14` pins `okhttp = "5.5.0"` next to `coil = "2.7.0"`, but Coil 2.7 is
built against OkHttp 4.x and the app does use Coil's network path (`RecentPostsSection.kt:92`
`AsyncImage`), so Gradle resolves one OkHttp major against an incompatible client. The same finding
records toolchain drift from the blueprint (AGP `9.3.2` vs `9.4.1`, Kotlin `2.4.10` vs `2.4.21`,
Compose BOM `2026.08.00` vs `2026.09.00`, Room `2.8.4` vs `2.8.5`, Robolectric `4.16.1` vs `4.17`,
KSP `2.3.11` vs `2.3.12`, Turbine `1.1.0` vs `1.2.1`, core-ktx `1.19.0` vs `1.19.1`, wrapper
`9.5.0` vs `9.8.1` at `gradle/wrapper/gradle-wrapper.properties:3`). `settings.gradle:15-18`
declares `google()` plus a Google-hosted Maven Central mirror and no `mavenCentral()`, so a mirror
outage breaks all resolution.

## Scope
- Move to Coil 3 (`io.coil-kt.coil3:coil-compose` + `coil-network-okhttp`) so the network stack
  major matches OkHttp 5, or verify the current 2.7 + 5.5 combination with the dependency report
  and record the verification in the PR.
- Update the Coil call sites if the artifact changes (`RecentPostsSection.kt:92`,
  `ImagePagerSection.kt:44-56`, `OrderPreviewList.kt:62`).
- Add `mavenCentral()` next to the mirror in both `pluginManagement` and
  `dependencyResolutionManagement` (`settings.gradle:15-18`).
- Bump the drifted versions listed above to the blueprint values in `gradle/libs.versions.toml`
  and `gradle/wrapper/gradle-wrapper.properties:3`, in one deliberate commit (not interleaved with
  the Coil swap if the build fails).

## Acceptance criteria
- [ ] `./gradlew :app:dependencies --configuration ciDevDebugRuntimeClasspath | grep -E 'okhttp|coil'`
      shows a single OkHttp major and a Coil artifact that targets it.
- [ ] Both repository blocks declare `mavenCentral()` as a fallback.
- [ ] `./gradlew :app:assembleLocalDevDebug :app:testLocalDevDebugUnitTest` is green after the bumps.
- [ ] No source change outside the version catalog, `settings.gradle`, and any Coil import rename.

## Dependencies
- None

## Verification
- `./gradlew :app:dependencies --configuration ciDevDebugRuntimeClasspath | grep -E 'okhttp|coil'`
