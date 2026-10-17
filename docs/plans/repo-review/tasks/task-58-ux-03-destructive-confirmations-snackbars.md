# Task 58 — UX-03: Confirm destructive actions and add snackbars

**Wave 4 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) medium-destructive · medium-feedback

## Problem
Logout is a bare app-bar `TextButton` (`UploadScreen.kt:327-329`) that calls `MainViewModel.logout()` (`MainViewModel.kt:88-92`) and wipes the token, instance URL and cached tags with no confirmation; removing a photo is equally unguarded (`UploadScreen.kt:384-390`). Upload feedback is a small inline `Text` (`UploadScreen.kt:626-635`) that persists until the next action, and no `SnackbarHost` exists anywhere in the app. The only `AlertDialog` today is the collection-create dialog (`CollectionSelectionSection.kt:94`), so there is no existing confirm pattern to reuse.

## Scope
- Add an `AlertDialog` confirm for logout before `onLogout()` fires (`UploadScreen.kt:326-329`).
- Add a `SnackbarHost` to the Upload `Scaffold` (`UploadScreen.kt:321` onwards) and a `SnackbarHostState` owned by the screen.
- Remove-photo becomes an Undo snackbar: keep the removed `Uri` and its index and re-insert it via a VM call when Undo is tapped.
- Route upload success/failure through the snackbar; keep the inline text only for genuinely persistent state.
- Undo re-insertion needs a VM entry point (e.g. `insertImageAt(index, uri)`) next to `removeImageAt` (`UploadViewModel.kt:388-402`).
- Keep `statusMessage`/`isError` in the ViewModel as the source of truth; the snackbar renders what the VM emits and can later read `UploadUiState` (REF-01).
- Keep the change inside `UploadScreen.kt` plus the one VM undo entry point; no other component gains dialog/snackbar state.

## Acceptance criteria
- [ ] Logout cannot complete without an explicit confirm tap.
- [ ] Removing a photo shows a snackbar whose Undo restores it at the same index.
- [ ] Upload success and failure are surfaced via the `SnackbarHost`, and the success message no longer lingers inline.
- [ ] The `SnackbarHostState` is owned by the screen, not created inside a leaf component.
- [ ] Undo restores the removed photo at its original index and keeps the pager on an existing page.
- [ ] `./gradlew :app:assembleLocalDevDebug` compiles.

## Dependencies
- None.

## Verification
- `./gradlew :app:assembleLocalDevDebug`, then manually: tap Logout, dismiss; remove a photo and Undo; run a failing upload.
