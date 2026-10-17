# Task 01 — REL-01: Tag is pushed before the APK is built, signed or validated (orphan tags)

**Wave 0 · Priority P0 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #1

## Problem
`build.yml:30-39` creates and pushes the release tag first; assemble/sign/release only run afterwards at `:67-110`. Any failure in between (R8/lint, missing secret, upload error) — or a second push cancelled by `cancel-in-progress: true` (`build.yml:14-16`) — leaves a pushed tag with no release. The tag is also resolved with no dry run, so tag and built version can disagree.

## Scope
- `.github/workflows/build.yml`: split into two phases — a dry-run resolution step (`DRY_RUN: true`, `id: version`) and a later push step using `CUSTOM_TAG: ${{ steps.version.outputs.new_tag }}`.
- Pass `-Pandroid.versionName="${{ steps.version.outputs.new_tag }}"` to the assemble step so the built version equals the tag (together with REL-02).
- Move the tag push to after the prod-variant validation step (REL-04) and before signing/release.
- Keep the tag step gated to `if: github.event_name == 'push' && github.ref == 'refs/heads/main'`.

```yaml
- name: Resolve release version
  id: version
  uses: anothrNick/github-tag-action@4ed44965e0db8dab2b466a16da04aec3cc312fd8 # 1.75.0
  env:
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
    WITH_V: false
    PRERELEASE: true
    PRERELEASE_SUFFIX: build
    INITIAL_VERSION: 1.0.0
    DRY_RUN: true
```

## Acceptance criteria
- [ ] A failing prod-variant validation leaves no new tag on the repository.
- [ ] The pushed tag equals the `steps.version.outputs.new_tag` used to stamp the APK.
- [ ] A second push mid-release cannot cancel a run after its tag exists.

## Dependencies
- None (REL-04 depends on this).

## Verification
- `git ls-remote --tags origin` after a branch push with a deliberately broken validation step (expect no new tag).
