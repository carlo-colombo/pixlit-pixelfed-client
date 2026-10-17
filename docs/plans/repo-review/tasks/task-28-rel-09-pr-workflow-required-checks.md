# Task 28 — REL-09: Add a PR workflow with always-on required checks

**Wave 2 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #12 · [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) low-CI-hygiene

## Problem
The repository has a single workflow, `build.yml`, which runs the full signed-release assembly on PRs (`build.yml:9-11`) with no path filter, no always-on `required-checks` job and no `workflow_dispatch`. Docs-only PRs therefore pay for a full release assembly, and a branch-protection rule pointed at this workflow can hang "pending" because the job can be skipped or cancelled. The blueprint splits this into `pr.yml` with a `changes` path filter, a `build` job and a `required-checks` job (`if: always()`).

## Scope
- Add `.github/workflows/pr.yml` ported from `neurhome/.github/workflows/pr.yml`: a `changes` job with `paths-ignore` for docs-only PRs, a `build` job, and a `required-checks` job gated `if: always()` that fails only when `build`/`changes` actually failed.
- Run assemble, unit tests and lint in one `--parallel` invocation: `:app:assembleCiDevDebug :app:testCiDevDebugUnitTest :app:lintCiDevDebug`.
- Add `workflow_dispatch` to `pr.yml` and default its permissions to `contents: read`.
- Restrict `build.yml` to `push` on `main` (drop its `pull_request` trigger) so PRs no longer run the release assembly.
- Keep the emulator/connected-test job out of scope (there are no instrumented tests; add it with the first one).

## Acceptance criteria
- [ ] `.github/workflows/pr.yml` exists with `changes`, `build` and always-on `required-checks` jobs.
- [ ] A docs-only PR skips `build` but `required-checks` still reports success.
- [ ] A PR that breaks a unit test or lint fails `build` and, in turn, `required-checks`.
- [ ] `build.yml` no longer triggers on `pull_request`.

## Dependencies
- REL-03 (CI must actually run unit tests + full lint for the build job to gate)

## Verification
- `actionlint .github/workflows/*.yml`
- `grep -nE 'paths-ignore|required-checks|workflow_dispatch|if: always' .github/workflows/pr.yml`
- `grep -n 'pull_request' .github/workflows/build.yml` (no match)

```yaml
required-checks:
  needs: [changes, build]
  if: always()
  runs-on: ubuntu-latest
  steps:
    - run: |
        [ "${{ needs.build.result }}" = "failure" ] && exit 1
        [ "${{ needs.changes.result }}" = "failure" ] && exit 1
        exit 0
```
