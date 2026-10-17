# Task 51 — UP-09: Preserve the draft across process death

**Wave 3 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [03-upload-flow.md](../../../review/03-upload-flow.md) medium-rotation

## Problem
An in-progress post is lost on rotation or process death. `UploadViewModel` has no `SavedStateHandle`
(`UploadViewModel.kt:27-31`); selected URIs, caption, location and collections live only in
`StateFlow`s. UI-local state uses plain `remember`: `selectedTab` (`UploadScreen.kt:258`),
`showTagPopup`/`captionFocused` (`:263-264`) and `tagSearchQuery` (`:302`). Picking photos via
`GetMultipleContents` (`:295`) sends the app to the background — the highest-risk moment for process
death — so returning from the gallery can show an empty upload screen, and rotating silently resets
the tab to "Upload".

## Scope
- Add `SavedStateHandle` to the `UploadViewModel` constructor (`UploadViewModel.kt:27-31`) and persist the caption text plus selected URIs (a `List<String>` of URI strings).
- Restore those values into the backing flows on init.
- Use `rememberSaveable` for `selectedTab` and `tagSearchQuery` (`UploadScreen.kt:258, 302`).
- Clamp/validate restored URIs (a dead URI should be dropped, not crash).

## Acceptance criteria
- [ ] Rotating the device keeps the caption, selected photos, location and selected tab.
- [ ] After process death (e.g. "Don't keep activities"), the draft is restored.
- [ ] A stale/revoked URI is dropped gracefully rather than crashing.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests '*UploadViewModelTest'`
