# Task 03 — REL-03: CI runs no unit tests and no full lint

**Wave 0 · Priority P1 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #3, [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) high-2

## Problem
`build.yml:52-75` is the only Gradle invocation in the workflow and runs only the assemble task (`assembleCiDevRelease` on PRs, `assembleCiProdRelease` on main). No `test*UnitTest` and no `lint*` task is ever requested, so the 53 existing tests never gate anything and only AGP's automatic fatal `lintVital*` subset runs. `README.md` advertises `testLocalDevDebugUnitTest`, but nothing in CI runs it.

## Scope
- `.github/workflows/build.yml`: add an "Assemble, test and lint" step running one parallel invocation:
  `./gradlew :app:assembleCiDevDebug :app:testCiDevDebugUnitTest :app:lintCiDevDebug --parallel`.
- Ensure the step runs on `pull_request` as well as `push`.
- Upload `app/build/reports/**` and `app/build/reports/lint-results-*.html` as artifacts (`if: always()`).

```yaml
- name: Assemble, test and lint
  run: >
    ./gradlew
    :app:assembleCiDevDebug
    :app:testCiDevDebugUnitTest
    :app:lintCiDevDebug
    --parallel
```

## Acceptance criteria
- [ ] A deliberately broken unit test fails the CI job.
- [ ] `lintCiDevDebug` findings appear in the workflow log.
- [ ] The invoked tasks are AGP's real `Test`/lint tasks (not the `testDebugUnitTest` alias).

## Dependencies
- None.

## Verification
- `./gradlew :app:testCiDevDebugUnitTest :app:lintCiDevDebug --parallel`
