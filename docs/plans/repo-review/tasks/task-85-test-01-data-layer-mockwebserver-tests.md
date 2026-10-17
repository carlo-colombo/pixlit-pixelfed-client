# Task 85 — TEST-01: Add data-layer tests with MockWebServer

**Wave 4 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-19

## Problem
`PixelfedRepositoryTest.kt` (18 tests) covers only companion pure functions plus a simulated list
shift that tests the test, not production code; `gradle/libs.versions.toml` has `mockk`, `turbine`
and `kotlinx-coroutines-test` but no `okhttp.mockwebserver` and no `androidx.room:room-testing`.
`PixelfedRepository`'s constructor takes `OkHttpClient`, `Gson`, `StatusDao` and `Context`, so a
fake server can be substituted today — but nothing exercises the behaviour found in F-08
(self-contradictory TTL), F-10 (lossy mapping), F-11 (swallowed failures) or F-04 (no `state`).

## Scope
- `gradle/libs.versions.toml` + `app/build.gradle`: add `okhttp.mockwebserver` and
  `androidx.room:room-testing` (test-only).
- Add mapper tests (pure, no server): `StatusItem → StatusEntity → StatusItem` round trip,
  `toSafeString` on null/primitive/object, `PlaceItem`/`CollectionItem` `getDisplayName` fallbacks
  (`PixelfedApi.kt:159-164, 198-201`), `parseChallengeTags` `"#A + #B"` grouping.
- Add `MockWebServer` tests through the injected `OkHttpClient`: 401 maps to `SessionState.Expired`
  once SEC-04's interceptor exists, a 500 from `searchPlaces` is a `Failed` (not an empty success),
  and `forceRefresh = false` within TTL issues **zero** requests.
- Keep the existing pure-function tests as the regression baseline.

## Acceptance criteria
- [ ] New tests fail against the pre-fix behaviour and pass once DATA-04 / DATA-05 / SEC-04 land.
- [ ] The 500-from-`searchPlaces` test asserts request count and failure type, not just "not empty".
- [ ] No production file changes outside DI seams needed to inject a base URL.
- [ ] `./gradlew :app:testLocalDevDebugUnitTest` is green.

## Dependencies
- DATA-04, DATA-05, SEC-04

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests 'ovh.litapp.pixlit.data.*'`
