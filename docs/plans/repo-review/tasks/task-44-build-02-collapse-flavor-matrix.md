# Task 44 — BUILD-02: Collapse the flavor matrix

**Wave 3 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) medium-flavors

## Problem
The 2×2 matrix (`flavorDimensions = ["distribution", "env"]`, `app/build.gradle:27-46`) produces 8
variants: every `assemble` runs 4× R8 (+4× `lintVitalRelease`, 4× resource shrink) and every
`check`/`test` runs the same 53 tests four times, each in a fresh Robolectric JVM. `local` and `ci`
differ only by `applicationIdSuffix ".local"`/`appLabel`; the README's only stated need is
"install alongside" (`README.md:73,77-79`), which the id suffix alone provides. Extra variants also
multiply KSP (Hilt + Room) and Hilt aggregation work, slowing incremental loops.

## Scope
- `app/build.gradle:27-46`: keep one `env` dimension (`dev`/`prod`); drop the `distribution` dimension and the `local`/`ci` flavors.
- Move the CI/local "install alongside" id suffix to a build-time property (mirror `neurhome`'s `-PdevHostSuffix` applied to `applicationIdSuffix`/`resValue`), not a flavor.
- Update `README.md` and any CI invocations that name `local*`/`ci*` variants.
- Confirm the resulting unit-test task name and that only the intended number of test JVMs run.

## Acceptance criteria
- [ ] Only `dev`/`prod` (× debug/release) variants remain; `./gradlew tasks` shows no `local`/`ci` variants.
- [ ] Two builds can still be installed side by side (via the build-time suffix).
- [ ] `assemble`/`test` no longer run the suite or R8 four times.
- [ ] Every documented Gradle command (`README.md`) still resolves.

## Dependencies
- None

## Verification
- `./gradlew :app:assembleDevDebug :app:testDevDebugUnitTest`
