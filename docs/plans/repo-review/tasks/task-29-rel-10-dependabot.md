# Task 29 — REL-10: Add Dependabot configuration

**Wave 2 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #10 · [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) medium-supply-chain

## Problem
`.github/` contains only `workflows/` — there is no `dependabot.yml`, so AGP/Kotlin/Compose/Coil versions and (once REL-06 lands) the action SHA pins drift with no automated bump PRs. The review already documents that drift (`AGP 9.3.2` vs `9.4.1`, `Kotlin 2.4.10` vs `2.4.21`, wrapper `9.5.0` vs `9.8.1`) and notes the blueprint's own history of updates stalling at the default limit. The blueprint ships a grouped weekly config for both ecosystems (`neurhome/.github/dependabot.yml`).

## Scope
- Add `.github/dependabot.yml` (`version: 2`) copied from `neurhome/.github/dependabot.yml`.
- `gradle` ecosystem at `directory: "/"`: weekly, `open-pull-requests-limit: 10`, grouped `minor`/`patch` updates under one group.
- `github-actions` ecosystem at `directory: "/"`: weekly, `open-pull-requests-limit: 5` (keeps the REL-06 SHA pins current).
- No other files in this task; the version bumps themselves arrive as Dependabot PRs.

## Acceptance criteria
- [ ] `.github/dependabot.yml` is valid Dependabot v2 config and declares both ecosystems.
- [ ] The `gradle` entry groups minor/patch updates and sets a limit ≥ 10.
- [ ] The `github-actions` entry is present so pinned action SHAs receive update PRs.

## Dependencies
- REL-06 (Dependabot is what keeps those SHA pins fresh)

## Verification
- `cat .github/dependabot.yml`
- `python3 -c "import yaml; yaml.safe_load(open('.github/dependabot.yml'))"`

```yaml
version: 2
updates:
  - package-ecosystem: "gradle"
    directory: "/"
    schedule: { interval: "weekly" }
    open-pull-requests-limit: 10
    groups:
      gradle-minor-patch:
        patterns: ["*"]
        update-types: ["minor", "patch"]
  - package-ecosystem: "github-actions"
    directory: "/"
    schedule: { interval: "weekly" }
    open-pull-requests-limit: 5
```
