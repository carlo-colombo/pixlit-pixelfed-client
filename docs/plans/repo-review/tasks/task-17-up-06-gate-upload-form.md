# Task 17 — UP-06: Gate the upload form and dedupe picks

**Wave 1 · Priority P1 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [03-upload-flow.md](../../../review/03-upload-flow.md) HIGH images-added-during-upload

## Problem
`addImages` (`UploadViewModel.kt:382-386`) accepts picks while an upload is running — the gallery button's
`enabled = selectedImageUris.size < maxPhotos` (`UploadScreen.kt:447`) ignores `isUploading` — and on
success the ViewModel wipes everything (`UploadViewModel.kt:558-572`), so photos added during a slow
upload are silently discarded. The 6-cap is silent (`.take(6)`, `:383`), the launcher is
`GetMultipleContents` with no limit (`UploadScreen.kt:295-299`), and there is no `distinct()`, so the same
URI can be selected twice and uploaded twice.

## Scope
- `UploadScreen.kt:447`: gate the add-photos button on `selectedImageUris.size < maxPhotos && !isUploading`.
- `UploadViewModel.addImages` (`:382-386`): `distinct()` incoming URIs and clear `_isError` with the message.
- Surface the cap with a message/snackbar when `uris.size` would exceed the remaining slots.
- On success, clear only the submitted URIs rather than the whole list:
  `_selectedImageUris.value = _selectedImageUris.value.filterNot { it in submitted }`.

## Acceptance criteria
- [ ] Photos added during an in-flight upload are retained, not discarded on completion.
- [ ] The add button is disabled while uploading; the 6-cap is communicated when exceeded.
- [ ] Selecting the same URI twice keeps a single entry and uploads it once.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests '*UploadViewModelTest'`
