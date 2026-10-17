# Task 36 — BUILD-04: Make Robolectric offline and resource-aware

**Wave 2 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) medium-Robolectric

## Problem
Robolectric resolves `android-all` at test runtime from a hardcoded live Maven mirror, and the test task is not configured for Android resources or an SDK pin: `app/build.gradle:72-78` sets only heap, `-Xshare:off` and `robolectric.dependency.repo.url`, with no `includeAndroidResources` and no `robolectric.properties` (`app/src/test/resources` does not exist). Tests pass only because none of them touch resources or the merged manifest, so the first Compose/`R.*` test fails for environmental reasons — and the download is an undeclared task input that Gradle's cache cannot invalidate.

## Scope
- Add `testOptions { unitTests { includeAndroidResources = true } }` in `app/build.gradle`.
- Add `app/src/test/resources/robolectric.properties` pinning `sdk=35` (current `targetSdk`; BUILD-05 owns the bump).
- Replace the runtime mirror download with a Gradle-resolved, staged `android-all` jar and run Robolectric offline (`robolectric.offline=true`, `robolectric.dependency.dir`), declaring the jar version as a test-task input so a version change re-runs the tests.
- Remove `-Xshare:off`, or keep it with a comment recording the reproducible JDK CDS crash it works around.
- Follow `neurhome/app/build.gradle.kts:36-58,151-172` for the staged-jar shape.

## Acceptance criteria
- [ ] A test that touches `R.*` / a themed `Context` passes.
- [ ] `./gradlew :app:testLocalDevDebugUnitTest` passes with no network access.
- [ ] Changing the declared `android-all` version invalidates and re-runs the test task.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest`
- `./gradlew :app:testLocalDevDebugUnitTest --offline`

```kotlin
// app/src/test/resources/robolectric.properties
sdk=35
```
