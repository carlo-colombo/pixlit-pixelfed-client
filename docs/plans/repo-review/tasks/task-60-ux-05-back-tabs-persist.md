# Task 60 — UX-05: Handle back within tabs and persist the tab

**Wave 4 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) medium-back

## Problem
Tab selection is local state with no back handling (`UploadScreen.kt:258` — `var selectedTab by remember { mutableStateOf(0) }`) and there is no `BackHandler` anywhere in the module, so system back from Social/Recent/Debug/Settings finishes the Activity instead of returning to Upload. Because the state uses plain `remember` (`UploadScreen.kt:258`, `:302`), the tab and the tag-search query are also lost on rotation or process death.

## Scope
- Add `BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }` in the tab host; keep it disabled on tab 0 so back still leaves the app.
- If REF-01/REF-02 have landed, add the handler and the saveable state in the extracted tab host rather than back in `UploadScreen`.
- Change `selectedTab` (`UploadScreen.kt:258`) and `tagSearchQuery` (`:302`) to `rememberSaveable`.
- Do not add a navigation library; this stays a single-screen tab host (explicit in the review's recommendation).
- Photo/caption survival across process death is UP-09's `SavedStateHandle` work — out of scope here.
- Persist the tab/query via `rememberSaveable` only; do not duplicate them into the ViewModel.
- Do not touch `MainActivity`'s back handling; this is screen-local.

## Acceptance criteria
- [ ] Back from any non-Upload tab returns to the Upload tab; from Upload it still exits the app.
- [ ] Rotating on the Recent/Settings tab keeps that tab selected.
- [ ] A non-empty tag-search query survives rotation.
- [ ] `./gradlew :app:assembleLocalDevDebug` compiles.
- [ ] No `BackHandler` intercepts back while the Upload tab is selected.
- [ ] The restored tab comes from saved instance state, with no second copy in the ViewModel.
- [ ] Restoring the state does not re-run tab side effects such as `onRefreshCacheSummary` (`UploadScreen.kt:292`).

## Dependencies
- UP-09 (the `SavedStateHandle` work; share the same persistence approach rather than inventing a second one).

## Verification
- `./gradlew :app:assembleLocalDevDebug`, then manually: switch tab → back; rotate on a non-Upload tab.
