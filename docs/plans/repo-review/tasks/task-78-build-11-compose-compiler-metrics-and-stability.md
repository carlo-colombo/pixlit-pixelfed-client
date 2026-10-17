# Task 78 — BUILD-11: Add Compose compiler metrics and stability config

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) low-composeCompiler

## Problem
`app/build.gradle:62-64` enables `buildFeatures { compose = true }` and nothing else: there is no
`composeCompiler { }` block anywhere and no stability configuration file in `app/`, so there is no
data on skippability/recomposition and no way to declare (and audit) types the compiler cannot prove
stable. For an upload-heavy Compose app with a pager, image-metadata rows and tag clouds, this is
the first measurement a startup/jank review needs.

## Scope
- `app/build.gradle`: add a `composeCompiler { }` block with `metricsDestination` and
  `reportsDestination` under `layout.buildDirectory`.
- Add `app/compose_stability.conf` listing the unstable `data.*` holder(s) the compiler cannot
  prove stable, each with a one-line "why" comment (blueprint: neurhome `app/build.gradle.kts:186-191`).
- Wire `stabilityConfigurationFiles` only if the file lists at least one real type; otherwise leave
  it commented and note the omission.
- Follow the review's note: keep the destinations out of the default build if the artifacts are noisy.

```groovy
composeCompiler {
    metricsDestination = layout.buildDirectory.dir("compose-metrics")
    reportsDestination = layout.buildDirectory.dir("compose-reports")
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose_stability.conf"))
}
```

## Acceptance criteria
- [ ] A build emits `app/build/compose-metrics/**` and `app/build/compose-reports/**`.
- [ ] Every type in `compose_stability.conf` carries a short justification comment.
- [ ] No runtime behaviour change; `./gradlew :app:testLocalDevDebugUnitTest` stays green.

## Dependencies
- None

## Verification
- `./gradlew :app:assembleLocalDevDebug && ls app/build/compose-metrics app/build/compose-reports`
