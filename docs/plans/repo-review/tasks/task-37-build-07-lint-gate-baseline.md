# Task 37 — BUILD-07: Add a lint gate with a baseline

**Wave 2 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) medium-lint

## Problem
`app/build.gradle` has no `lint { }` block, and no CI invocation ever requests a lint task. Only AGP's automatic `lintVital<Flavor>Release` runs, so just the *fatal* subset gates anything; every other warning is unowned and unbounded. Lint debt the review already identified — `allowBackup="true"` with no `dataExtractionRules`, the dead `androidTest` dependency block, `targetSdk` drift — has nowhere to surface, and a future AGP bump adding a fatal check can break the release build unexpectedly.

## Scope
- Add a `lint { }` block in `app/build.gradle` with `warningsAsErrors = true`, `abortOnError = true`, `baseline = file("lint-baseline.xml")` and `checkDependencies = true`.
- Generate and commit `app/lint-baseline.xml` once, freezing current debt rather than fixing it here.
- Wire `:app:lintCiDevDebug` into the PR Gradle invocation (REL-03/REL-09) and upload `app/build/reports/lint-results-*.html` as a CI artifact.
- Do not fix the baselined warnings in this task; new warnings must fail the build.
- Follow `neurhome/app/build.gradle.kts:177-183` for the block shape.

## Acceptance criteria
- [ ] The `lint { }` block is present with `warningsAsErrors`/`abortOnError`/`baseline`/`checkDependencies`.
- [ ] `app/lint-baseline.xml` is committed and the current build is clean against it.
- [ ] A newly introduced lint warning fails the build.
- [ ] CI runs `lintCiDevDebug` and uploads the HTML report.

## Dependencies
- REL-03

## Verification
- `./gradlew :app:lintCiDevDebug`
- `./gradlew :app:lintCiDevDebug --rerun-tasks` after injecting a deliberate warning (must fail)

```groovy
lint {
    warningsAsErrors = true
    abortOnError = true
    baseline = file("lint-baseline.xml")
    checkDependencies = true
}
```
