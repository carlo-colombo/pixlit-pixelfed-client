# Task 72 — UX-17: Rename tabs and relocate logout

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) low Tab naming and Logout placement

## Problem
The tab list at `UploadScreen.kt:259` labels the recent-posts tab `"Last"`, which gives no hint that the tab holds recent posts, and a prominent `Logout` text button sits in the primary app bar (`UploadScreen.kt:326-329`) next to the task title. Both hurt discoverability and put a session-destroying action one mis-tap away from the upload flow.

## Scope
- Rename the `"Last"` tab label to `"Recent"` (`UploadScreen.kt:259`); no index changes, so the Debug/Settings branches keep their positions.
- Remove the `Logout` action from the `TopAppBar` (`UploadScreen.kt:326-329`).
- Add a `Logout` entry inside the Settings tab (`UploadScreen.kt:793-812`), routed through the confirmation dialog introduced by UX-03.
- Keep the existing `onLogout` callback plumbing (`MainActivity.kt:95`) unchanged.
- Update only the label string and the Logout placement; the `selectedTab` branch comments (`// Debug Tab`, `UploadScreen.kt:684`) stay accurate.
- UX-07 also touches `UploadScreen.kt:259`; if it has landed, rename the extracted string instead of adding a second literal.

## Acceptance criteria
- [ ] The tab reads "Recent" and still shows the recent-posts body.
- [ ] No logout affordance is present in the app bar; Settings contains Logout and it still requires confirmation.
- [ ] Tab indices for the remaining tabs are unchanged.
- [ ] UX-15's `SettingsContent` preview renders the relocated Logout entry.
- [ ] The tab label resolves through `strings.xml` when UX-07 has landed.
- [ ] Settings (and therefore Logout) is still reachable in a prod build.
- [ ] `./gradlew :app:assembleLocalDevDebug` compiles.

## Dependencies
- UX-03 (the confirmation dialog must exist before logout moves).

## Verification
- `./gradlew :app:assembleLocalDevDebug`, then manually: open each tab, log out from Settings.
