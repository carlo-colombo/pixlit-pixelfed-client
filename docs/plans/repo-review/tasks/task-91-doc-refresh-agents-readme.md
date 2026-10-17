# Task 91 — DOC-01: Refresh `AGENTS.md` and `README.md` after the backlog is complete

**Wave 5 (final) · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Added by operator request on issue #30
(2026-10-17). Run this **after every other task**, so it documents the final shipped state.

## Problem

`AGENTS.md` and `README.md` predate the repo-review remediation. They do not describe the current
architecture (repository seams extracted from the 740-line `PixelfedRepository`), the CI/release
pipeline (validate-before-tag, SHA-pinned actions, Dependabot automerge, license-asset
verification), the license/copyright, the JVM/toolchain policy, or the current test suite and
commands. Documenting too early would bake in stale claims; this task is deliberately last.

## Scope

- `AGENTS.md`: refresh architecture (data seams; `UploadUiState` if REF-01 landed), build/test
  commands (dev + prod gates, Robolectric offline staging, `local.properties` requirement),
  CI/release invariants (tag pushed only after prod validation, SHA pins, `automerge` +
  `DEPENDABOT_TOKEN`, license/notice verification), JVM policy (JBR 21 everywhere), and the
  credential/secret model.
- `README.md`: features, tech stack, build/run instructions, testing, and the License section
  (already added by REL-16 — refresh if the implementation changed).
- Reconcile any doc that contradicts the code found during this work (e.g.
  `docs/bluesky-art-show-reminders.md` was fixed by PLAT-02; check for others).
- Do not document aspirational behaviour or anything not actually shipped.

## Acceptance criteria

- [ ] `AGENTS.md` and `README.md` match the shipped code and CI (no stale pipeline/JDK/license claims).
- [ ] Every command listed in the docs is copy-pasteable and works.
- [ ] The documented architecture names the actual extracted seams/classes.
- [ ] The README License section and `NOTICE`/`LICENSE` agree.

## Dependencies

- **All other tasks** (Wave 0–4 + REL-16 + this backlog's remaining items). Do not start earlier.

## Verification

- Run each documented command once (`./gradlew :app:assembleLocalDevDebug`,
  `:app:testCiDevDebugUnitTest`, `:app:lintCiDevDebug`, `:app:testCiProdDebugUnitTest`,
  `:app:lintCiProdRelease`) and confirm the docs describe what happens.
