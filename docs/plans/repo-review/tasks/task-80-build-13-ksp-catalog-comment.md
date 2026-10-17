# Task 80 — BUILD-13: Fix the KSP catalog comment

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) medium-supply-chain (KSP catalog-comment bullet)

## Problem
`gradle/libs.versions.toml:25` reads `ksp = "2.3.11" # Assuming matching version for Kotlin 2.4.10`.
The build succeeds, so the KSP/Kotlin pair is in fact compatible; the comment is the only wrong
thing — it tells a future maintainer that the pin is an unverified guess and invites an unnecessary
or harmful bump.

## Scope
- `gradle/libs.versions.toml:25`: delete the "Assuming …" comment, or replace it with the actual rule
  (KSP releases track the Kotlin release; this pin was verified by a green KSP build) — one line, no
  version change.
- If BUILD-09 lands first and bumps Kotlin/KSP, write the comment for the pair that BUILD-09 leaves
  behind rather than re-deriving it here.
- Do not touch any other catalog entry; unrelated bumps belong to BUILD-09.

## Acceptance criteria
- [ ] `gradle/libs.versions.toml:25` no longer claims the pairing is a guess.
- [ ] No version value changes in this task (`git diff gradle/libs.versions.toml` shows one comment line).
- [ ] KSP still runs: `./gradlew :app:assembleLocalDevDebug` is green.
- [ ] `:app:assembleCiProdDebug` still generates the Hilt/Room/KSP sources without a version error.

## Dependencies
- None

## Verification
- `grep -n 'ksp =' gradle/libs.versions.toml && ./gradlew :app:assembleLocalDevDebug`
