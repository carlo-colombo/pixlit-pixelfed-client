# Task 64 — UX-09: Request notification permission in context

**Wave 4 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) medium-notification-permission · [06-images-db-background.md](../../../review/06-images-db-background.md) low Reminder/notification hygiene

## Problem
`POST_NOTIFICATIONS` is requested on every cold start with the legacy `requestPermissions(int)` API and no rationale, before login and before any reminder exists: `MainActivity.kt:47-50` (`SDK_INT >= 33 && checkSelfPermission(...) != GRANTED → requestPermissions(arrayOf(POST_NOTIFICATIONS), 100)`). A denial is never surfaced and cannot be re-requested, while `ArtShowNotifier.kt:22` silently skips the notification when notifications are disabled — so the reminder feature fails invisibly after the one prompt users dismiss.

## Scope
- Remove the cold-start request block (`MainActivity.kt:47-50`).
- Request from the first action that needs it — enabling a reminder in the Settings tab — via `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`, preceded by a short rationale.
- Add a Settings-tab state for the denied case: "Notifications disabled — reminders will not fire" with a route into system settings.
- Keep the existing check in `ArtShowNotifier.kt:18-22` as the last line of defence; do not change channel/icon behaviour (PLAT-01 owns the channel-once and small-icon fixes).
- Leave the `ACCESS_MEDIA_LOCATION` request (`MainActivity.kt:64-68`) untouched unless SEC-05's picker change rewrites it.
- The Settings state must read `areNotificationsEnabled()` (system truth), not a cached "already asked" flag.
- Leave the reminder scheduling call (`MainActivity.kt:52`) alone; that is PLAT-01's scope.

## Acceptance criteria
- [ ] A cold start on API 33+ shows no permission dialog and no `requestPermissions(...)` call for notifications.
- [ ] Enabling a reminder is the only path that prompts, and it shows a rationale first.
- [ ] When denied, the Settings tab states that reminders will not fire instead of failing silently.
- [ ] The Settings entry re-triggers the launcher on tap, so a denial can be reversed without reinstalling.
- [ ] No `requestPermissions(int, ...)` call remains for `POST_NOTIFICATIONS`.
- [ ] The rationale appears before the system dialog, and dismissing the rationale does not prompt.
- [ ] `./gradlew :app:assembleLocalDevDebug` compiles.

## Dependencies
- None (interacts with PLAT-01 and UX-18; do not duplicate their notification changes).

## Verification
- `./gradlew :app:assembleLocalDevDebug`, then manually: fresh install, confirm no cold-start prompt; enable a reminder and confirm the prompt + rationale; deny and check the Settings state.
