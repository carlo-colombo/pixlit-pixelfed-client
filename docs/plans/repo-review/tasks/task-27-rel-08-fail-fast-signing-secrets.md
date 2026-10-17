# Task 27 — REL-08: Fail fast on missing signing secrets

**Wave 2 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #7

## Problem
The release step passes `alias: ${{ secrets.ALIAS || 'key' }}` and `keyStorePassword: ${{ secrets.KEY_STORE_PASSWORD || 'password' }}` (`build.yml:83-84`). A missing or renamed secret therefore does not fail the run: it attempts to sign with a guessable default password and a guessed alias, producing a confusing failure at best and normalising "the signing password is `password`" at worst. The blueprint passes `ALIAS`/`KEY_STORE_PASSWORD` straight through and fails loudly with an explicit decode check.

## Scope
- Remove the `|| 'key'` and `|| 'password'` fallbacks; pass `secrets.ALIAS` and `secrets.KEY_STORE_PASSWORD` unmodified.
- Add an explicit precondition step that emits `::error::missing signing secret` and exits non-zero when `ALIAS`, `KEY_STORE_PASSWORD` or `SIGNING_KEY` is empty or unset.
- Verify the decoded keystore is non-empty (`[ -s "$keystore" ]`) before `zipalign`/`apksigner` run.
- Implement inside the signing block reworked by REL-05; do not re-introduce `r0adkll/sign-android-release`.

## Acceptance criteria
- [ ] No `|| 'key'` / `|| 'password'` (or any other secret default) remains in `.github/workflows/build.yml`.
- [ ] A run with an unset `ALIAS`/`KEY_STORE_PASSWORD`/`SIGNING_KEY` fails before any APK is signed, with the `::error::` message.
- [ ] A run with all three secrets set still signs and verifies the APK.

## Dependencies
- REL-05 (signing is reworked to explicit `zipalign`/`apksigner` there)

## Verification
- `grep -n "||" .github/workflows/build.yml` (no secret fallbacks)
- `actionlint .github/workflows/build.yml`

```yaml
- name: Require signing secrets
  run: |
    set -euo pipefail
    for name in SIGNING_KEY ALIAS KEY_STORE_PASSWORD; do
      [ -n "${!name:-}" ] || { echo "::error::missing signing secret: $name"; exit 1; }
    done
```
