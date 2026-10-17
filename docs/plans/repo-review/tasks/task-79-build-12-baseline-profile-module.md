# Task 79 — BUILD-12: Add a baseline profile module

**Wave 4 · Priority P3 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) low-baseline

## Problem
No `:baselineprofile` module exists (`settings.gradle:23` includes only `:app`) and no
`baseline-prof.txt` lives in `app/src`, so the merged profile carries only library entries: the
report notes `app/build/intermediates/merged_art_profile/ciProdRelease/mergeCiProdReleaseArtProfile/baseline-prof.txt`
starts with androidx library sections and a search for `ovh/litapp` finds no app-class entries.
Cold start therefore relies entirely on library profiles.

## Scope
- Add a `:baselineprofile` module (`com.android.test` + `androidx.benchmark.macro:benchmark-macro-junit4`)
  and include it in `settings.gradle`; keep it out of the normal `assemble` path.
- Record a profile covering Activity/Application/Compose entry points and commit it as
  `app/src/main/baseline-prof.txt`.
- Do **not** add `androidx.profileinstaller`: `minSdk 34` means ART handles profiles natively, so the
  dependency would be cargo culting (explicit review guidance).
- Leave Room schema export out of this task — REL-12 owns `exportSchema`/`app/schemas`.

## Acceptance criteria
- [ ] The merged baseline profile for a prod variant contains `ovh/litapp` entries.
- [ ] `:baselineprofile` is not built by a plain `./gradlew assemble` and has no `profileinstaller` dependency.
- [ ] `./gradlew :app:assembleCiProdRelease` remains green with the committed profile.

## Dependencies
- None

## Verification
- `./gradlew :app:assembleCiProdRelease && grep -c 'ovh/litapp' app/build/intermediates/merged_art_profile/ciProdRelease/mergeCiProdReleaseArtProfile/baseline-prof.txt`
