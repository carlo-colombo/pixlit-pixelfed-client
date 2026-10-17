# Task 30 — REL-11: Generate artifact checksums and set retention

**Wave 2 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #9, #14

## Problem
`build.yml:88-104` renames and uploads the APK with no hash, and the release step attaches only the APK (`build.yml:106-110`). A downloaded release APK therefore cannot be integrity-verified and there is no evidence trail if an artifact is swapped. The upload step also omits `retention-days` (`build.yml:100-104`), unlike the blueprint (14 days PR / 90 days release).

## Scope
- Add a "Generate checksum" step: `sha256sum pixelfed-client-<tag>.apk > pixelfed-client-<tag>.apk.sha256`.
- Include the `.sha256` file in the `upload-artifact` `path` glob and in `softprops/action-gh-release` `files:`.
- Set `retention-days` on the artifact upload step (14 for the PR lane, 90 for the release lane) per `neurhome`.
- Reuse the existing single tag/version value; leave the single-sourcing refactor to REL-14.

## Acceptance criteria
- [ ] The release attaches both the APK and `pixelfed-client-<tag>.apk.sha256`.
- [ ] `sha256sum -c pixelfed-client-<tag>.apk.sha256` passes against a downloaded APK.
- [ ] The upload step declares `retention-days`.

## Dependencies
- None

## Verification
- `grep -nE 'sha256sum|\.sha256|retention-days' .github/workflows/build.yml`
- `actionlint .github/workflows/build.yml`

```yaml
- name: Generate checksum
  run: sha256sum "pixelfed-client-${TAG_VERSION}.apk" > "pixelfed-client-${TAG_VERSION}.apk.sha256"

- uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7
  with:
    name: pixelfed-client-apk
    path: |
      pixelfed-client-*.apk
      pixelfed-client-*.apk.sha256
    retention-days: 90
```
