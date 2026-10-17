# Task 04 — REL-04: No prod-variant validation before signing/release

**Wave 0 · Priority P1 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #3

## Problem
Because CI only assembles (`build.yml:52-75`), a prod-flavor/unit/lint breakage flows straight into the signed release. The blueprint (`neurhome`) guards this with a "Validate prod variant before signing" step, and its `AGENTS.md` records that a release already failed at exactly that gate while the dev suite was green. For this repo the prod variant is `ciProd` (`app/build.gradle:27-46`).

## Scope
- `.github/workflows/build.yml`: add a "Validate prod variant before signing" step running `./gradlew :app:testCiProdDebugUnitTest :app:lintCiProdRelease`.
- Place it after the build and before the tag push / signing (pairs with REL-01).
- Pass `-Pandroid.versionName="${{ steps.version.outputs.new_tag }}"` so validation matches the stamped build.

```yaml
- name: Validate prod variant before signing
  run: |
    ./gradlew \
      -Pandroid.versionName="${{ steps.version.outputs.new_tag }}" \
      :app:testCiProdDebugUnitTest \
      :app:lintCiProdRelease
```

## Acceptance criteria
- [ ] A failing `ciProd` unit test blocks signing and release.
- [ ] Step order is: build → validate prod → push tag → sign → release.
- [ ] Validation uses the same resolved tag as the assembled APK.

## Dependencies
- REL-01 (tag push moves after validation).

## Verification
- `./gradlew :app:testCiProdDebugUnitTest :app:lintCiProdRelease`
