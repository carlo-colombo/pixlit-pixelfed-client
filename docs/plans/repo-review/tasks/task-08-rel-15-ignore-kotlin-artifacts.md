# Task 08 — REL-15: Ignore `.kotlin` and `.artifacts`

**Wave 0 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #16, [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) low-14

## Problem
`.kotlin/` (with `errors/` and `sessions/` contents) exists at the repo root, but `.gitignore` ignores `.gradle/` (`:2`) and `build/` (`:3`) only. Kotlin build-session state therefore shows as untracked noise in every `git status` and a careless `git add -A` commits it. The repo-root `.artifacts/` directory is likewise unignored.

## Scope
- `.gitignore`: add `/.kotlin/` for Kotlin build-session state.
- `.gitignore`: add `/.artifacts/` for the generated artifact directory.

```gitignore
# Kotlin build session state
/.kotlin/
/.artifacts/
```

Note: `.gitignore` is otherwise correct (it already covers `.gradle/`, `build/`, `local.properties`, `*.jks`/`*.keystore`), so this is a two-line addition, not a rewrite.

## Acceptance criteria
- [ ] `git check-ignore .kotlin .artifacts` matches both.
- [ ] `git status --porcelain` is clean after a Gradle build with `.kotlin/` present.
- [ ] No previously ignored path loses its ignore rule.

## Dependencies
- None.

## Verification
- `git check-ignore -v .kotlin .artifacts`
