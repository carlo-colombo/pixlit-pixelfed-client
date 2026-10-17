# Task 26 — REL-07: Least-privilege workflow permissions

**Wave 2 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #5 · [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) low-CI-hygiene

## Problem
`build.yml:17-22` grants `contents: write` at job level with no top-level default, and the same single job also runs on `pull_request` (`build.yml:9-11`). Internal-branch PR runs therefore get a write-capable `GITHUB_TOKEN` for the whole job, including the third-party signing step. The blueprint defaults to `contents: read` at workflow level and opts into write only on the push-only release path (`neurhome/.github/workflows/build.yml:4-6`, `pr.yml:3-5`).

## Scope
- Add a workflow-level `permissions: contents: read` to `.github/workflows/build.yml`.
- Grant `contents: write` only to the push-only path that pushes the tag and creates the release (separate job gated to `github.event_name == 'push'`, or equivalent), so PR runs never see a write token.
- Keep every other permission implicit/absent (no `actions`/`id-token`/`packages` grants).
- Do not change step behaviour, task names or the signing block; the tag ordering is REL-01.

## Acceptance criteria
- [ ] `.github/workflows/build.yml` declares `permissions: contents: read` at workflow level.
- [ ] No job that can run on `pull_request` declares any `write` permission.
- [ ] The push-only job that tags and releases still declares `contents: write`, and a `main` push still publishes a release.

## Dependencies
- None

## Verification
- `grep -nE 'permissions:|contents: (read|write)' .github/workflows/build.yml`
- `actionlint .github/workflows/build.yml`

```yaml
permissions:
  contents: read

jobs:
  build:
    # PR + main build; inherits read-only token
  release:
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    permissions:
      contents: write
```
