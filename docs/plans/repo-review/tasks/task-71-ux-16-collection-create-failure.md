# Task 71 — UX-16: Surface collection-create failures

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) low AlertDialog for collection creation swallows failures

## Problem
The create-collection dialog closes and clears its fields as soon as `onCreateCollection` is called (`CollectionSelectionSection.kt:126-133`), regardless of outcome, and the ViewModel's failure branch only logs: `UploadViewModel.kt:296-315` (`createAndSelectCollection`, `onFailure = { ex -> Log.e(...) }`). A failed create therefore looks like a dropped tap: no new chip, no message — the review's own wording is "the user assumes the tap was lost".

## Scope
- Keep the dialog open and preserve the typed title/description until creation is known to have succeeded; dismiss only on success (via the typed result that DATA-05 introduces).
- The dialog's local `showCreateDialog`/`newTitle`/`newDescription` state (`CollectionSelectionSection.kt:120-133`) must be keyed to the pending create so a failure does not clear it.
- Surface the failure through the snackbar added in UX-03, with the collection title in the message.
- Leave the success path as-is: the new collection is prepended and auto-selected (`UploadViewModel.kt:301-308`).
- Do not change `fetchCollections`'s static-default fallback (`UploadViewModel.kt:269-282`) — that is UP-13's low-severity item.
- Do not dismiss the dialog optimistically: dismissal must follow the create result, not the tap.
- Keep the success path's auto-select behaviour (`UploadViewModel.kt:301-308`) intact when the dialog does dismiss.

## Acceptance criteria
- [ ] A failed create leaves the dialog open with the user's input intact and shows an error message.
- [ ] Re-tapping Create after a failure retries with the preserved input, without retyping.
- [ ] The dialog's dismissal is driven by the create result, never by the button tap alone.
- [ ] `./gradlew :app:assembleLocalDevDebug` compiles.
- [ ] A successful create closes the dialog, clears the fields and selects the new collection.
- [ ] `./gradlew :app:testLocalDevDebugUnitTest` is green.

## Dependencies
- DATA-05 (typed result instead of `Result.success`/swallowing, so the dialog can react to a real failure).

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest`, then manually force a create failure (offline) and confirm the dialog survives.
