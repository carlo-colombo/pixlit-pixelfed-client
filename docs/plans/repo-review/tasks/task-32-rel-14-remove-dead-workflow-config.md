# Task 32 — REL-14: Remove dead workflow config

**Wave 2 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #15

## Problem
`build.yml:3-4` declares an env `main_project_module: app` that is never referenced anywhere in the workflow. `FLAVOR` is written at `build.yml:54,60` but never read. Worst, the PR fallback version is computed twice with different values — `1.0.0-pr` at `:71` versus `1.0.0-pr-${{ github.run_number }}` at `:92` — so the name the APK is stamped with (once REL-02 lands) and the name it is uploaded under would disagree on PRs.

## Scope
- Delete the unused `env:` block (`main_project_module`) from the workflow top level.
- Stop writing `FLAVOR`; keep only the values actually consumed (`ASSEMBLE_TASK`, `RELEASE_DIR`, `APK_PREFIX`).
- Derive the PR fallback version once as an `id:` output from the "Set Build Config" step and reuse it in both the assemble and prepare steps.
- Touch only these lines; the tag-ordering fix is REL-01 and the signing rework is REL-05/REL-08.

## Acceptance criteria
- [ ] No reference to `main_project_module` or `FLAVOR` remains in `.github/workflows/build.yml`.
- [ ] Exactly one PR fallback version expression exists, used by both the assemble and prepare steps.
- [ ] `actionlint` still passes and a PR run produces an APK whose name matches the stamped `versionName`.

## Dependencies
- None

## Verification
- `grep -nE 'main_project_module|FLAVOR|1\.0\.0-pr' .github/workflows/build.yml`
- `actionlint .github/workflows/build.yml`

```yaml
- name: Set Build Config
  id: build_config
  run: |
    if [ "${{ github.event_name }}" = "pull_request" ]; then
      echo "tag_version=1.0.0-pr-${{ github.run_number }}" >> "$GITHUB_OUTPUT"
      # ...
```
