# Task 45 — BUILD-03: Remove the `testDebugUnitTest` alias task

**Wave 3 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) medium-alias

## Problem
`app/build.gradle:82-85` registers `tasks.register("testDebugUnitTest") { dependsOn("testLocalDevDebugUnitTest") }`.
This is a plain `DefaultTask`, not a `Test` task, so `--tests` filtering fails with "Unknown
command-line option '--tests'", result aggregation/reporting does not attach, and it runs only the
local dev flavor — giving a false sense of coverage while `check` still launches four JVMs. It also
shadows the conventional AGP name, so once the `distribution` dimension is dropped AGP will fail to
register the real `testDebugUnitTest` with "Cannot add task … already exists".

## Scope
- Delete the hand-rolled alias block (`app/build.gradle:82-85`).
- Use AGP's own task names (`testLocalDevDebugUnitTest`) in docs and CI.
- If a short handle is wanted, register a distinct name (e.g. `fastTest`) that depends on the AGP task,
  never duplicating AGP's conventional name.
- Update `README.md:81` to match.

## Acceptance criteria
- [ ] `./gradlew :app:testLocalDevDebugUnitTest --tests '*UploadViewModelTest'` succeeds (real `Test` task).
- [ ] No task named `testDebugUnitTest` is registered by app code.
- [ ] Removing the block unblocks configuration once the flavor matrix is collapsed (BUILD-02).

## Dependencies
- BUILD-02 (alias removal must precede the collapse that frees the conventional name)

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests '*UploadViewModelTest'`
