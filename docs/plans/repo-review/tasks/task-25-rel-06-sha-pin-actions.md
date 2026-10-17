# Task 25 — REL-06: SHA-pin all GitHub Actions

**Wave 2 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #4 · [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) medium-supply-chain

## Problem
Every third-party action in the release workflow is pinned to a mutable major tag, so the code that runs in a job holding `SIGNING_KEY`/`KEY_STORE_PASSWORD` and `contents: write` can change without any commit in this repository. The floating pins are `build.yml:25` (`checkout@v4`), `:33` (`github-tag-action@v1`), `:42` (`setup-java@v5`), `:48` (`setup-gradle@v6`), `:101` (`upload-artifact@v4`) and `:108` (`action-gh-release@v2`). The only SHA-pinned action (`r0adkll/sign-android-release`, `build.yml:79`) is the deprecated one, so the maintained actions are the least protected.

## Scope
- Replace every `uses: owner/name@vN` in `.github/workflows/build.yml` with the full 40-char commit SHA plus a trailing `# vN` comment.
- Reuse the SHAs already resolved in `neurhome`'s workflows (`neurhome/.github/workflows/build.yml`, `neurhome/.github/actions/setup-jdk-gradle/action.yml`) instead of resolving new ones.
- Leave the existing `r0adkll` SHA and `env.BUILD_TOOLS_VERSION` untouched; signing rework is REL-05/REL-08.
- Change no step name, `if:`, `with:` value or env value.

## Acceptance criteria
- [ ] No `uses:` line in `.github/workflows/**` references a tag or branch.
- [ ] Every `uses:` ref is a 40-char SHA followed by a `# vN` comment whose tag matches the pinned SHA.
- [ ] The workflow YAML still parses and the diff touches only the ref/comment of each `uses:` line.

## Dependencies
- None

## Verification
- `grep -nE 'uses:' .github/workflows/build.yml`
- `actionlint .github/workflows/build.yml`
- `! grep -nE 'uses: .*@v[0-9]+' .github/workflows/build.yml`

```yaml
# SHAs already resolved in neurhome; reuse verbatim.
- uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7
- uses: anothrNick/github-tag-action@4ed44965e0db8dab2b466a16da04aec3cc312fd8 # 1.75.0
- uses: actions/setup-java@b843ebf9b913146b38da2eaa7aeddf76074ae1ad # v6
- uses: gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6 # v6
- uses: actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7
- uses: softprops/action-gh-release@efb35369e0ad2afab669f228072c1b0d510eae64 # v3
```
