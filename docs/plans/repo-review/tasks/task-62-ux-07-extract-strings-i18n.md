# Task 62 — UX-07: Extract strings for i18n

**Wave 4 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) medium-i18n

## Problem
`app/src/main/res/values/strings.xml` contains only `<resources></resources>` and the app has exactly two `R.string` uses (`MainViewModel.kt:66`, `LoginScreen.kt:31`, both `redirect_uri`). All user-facing copy is inlined in composables — `LoginScreen.kt:104` `Text("Connect to Pixlit")`, `UploadScreen.kt:323` `Text("Upload to Pixlit")`, `:452` `"Select Photos from Gallery (up to 6)"`, plus every `contentDescription` — so there is no localization path and copy edits require touching layout code.

## Scope
- Add named `<string>` entries to `app/src/main/res/values/strings.xml` and read them with `stringResource(...)`.
- Start with the screens users hit first, per the review: Login (`LoginScreen.kt` title/buttons/error/empty), the Upload header, tab labels, gallery button, remove/shift buttons, resize label, upload button and status/empty states, and the `contentDescription`s in `ui/upload/components/**`.
- Parameterise counts with `<plurals>` where the copy interpolates a number (e.g. "Upload N Photos", `UploadScreen.kt:648`).
- Leave the flavor override files (`app/src/dev/res/values/strings.xml`, `app/src/prod/res/values/strings.xml`) alone; they carry the redirect scheme and app label.
- Do not extract debug-only copy or strings that legitimately differ per flavour into the shared file.
- Keep `stringResource` reads inside composables; no copy moves into the ViewModel.
- Keep the existing wording exactly; this task relocates copy, it does not rewrite it.
- Keep `R.string.redirect_uri` working, and do not move VM-constructed error text (that is not layout copy) in this pass.

## Acceptance criteria
- [ ] The Login and Upload screens contain no literal user-visible copy (labels, buttons, error/empty states, content descriptions).
- [ ] Existing flavor string overrides still resolve; no missing/duplicate resource names.
- [ ] `res/values/strings.xml` has no unused entries for the extracted set (lint `UnusedResources`), and no duplicate names across flavours.
- [ ] A second locale file (e.g. `values-it/`) can be added without touching Kotlin.
- [ ] The displayed copy is byte-identical to the current literals.
- [ ] `./gradlew :app:assembleLocalDevDebug` and `./gradlew :app:lintLocalDevDebug` pass.

## Dependencies
- None.

## Verification
- `./gradlew :app:assembleLocalDevDebug :app:lintLocalDevDebug`
