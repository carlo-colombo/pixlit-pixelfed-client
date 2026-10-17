# Task 46 — BUILD-05: Raise targetSdk with a Robolectric pin

**Wave 3 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) medium-targetSdk

## Problem
`targetSdk 35` (`app/build.gradle:15`) lags `compileSdk 37` (`:10`), so the app silently opts out of
every API 36/37 behaviour change (edge-to-edge enforcement, predictive back, permission and
foreground-service rules). Robolectric's default SDK is `targetSdk` and there is no
`robolectric.properties` pin (`app/build.gradle:72-78`), so the test SDK is 35 purely by accident of
`targetSdk` — bumping it without a pin will request an SDK the installed Robolectric (4.16.1) may not
ship, turning a config bump into a test-suite outage.

## Scope
- Add `app/src/test/resources/robolectric.properties` with an explicit `sdk=` pin (decouple test SDK from `targetSdk`) **before** any bump.
- Raise `targetSdk` to 36 now; move to 37 with `compileSdk` only when the API 36/37 behaviour changes are willing to be tested.
- Keep `minSdk 34` unchanged.
- Run a device pass on the upload flow (picker, deep-link, notifications) after the bump.

## Acceptance criteria
- [ ] Robolectric's SDK is pinned independently and does not follow `targetSdk`.
- [ ] `targetSdk` is raised without any test-suite outage when the pin lands first.
- [ ] The upload flow is exercised on-device against the new target behaviour.
- [ ] `minSdk 34` is unchanged.

## Dependencies
- BUILD-04 (Robolectric runtime/`includeAndroidResources` configuration lands first)

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest`
