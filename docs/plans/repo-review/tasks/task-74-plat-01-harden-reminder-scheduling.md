# Task 74 — PLAT-01: Harden reminder scheduling

**Wave 4 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [06-images-db-background.md](../../../review/06-images-db-background.md) low-reminder-hygiene

## Problem
`MainActivity.kt:51` calls `reminderScheduler.scheduleAll()` unguarded in `onCreate`, and
`ReminderPreferences.kt:12-13` builds `LocalTime.of(prefs.getInt("friday_hour", 20), prefs.getInt("friday_minute", 0))`,
so a corrupt or out-of-range stored value throws `DateTimeException` on the main thread during
startup. The same file's review finding also flags `POST_NOTIFICATIONS` being re-requested on
every cold start (`MainActivity.kt:47-49`), `ArtShowNotifier.kt:18` recreating the channel per
notification, and `ArtShowNotifier.kt:24` using `R.drawable.ic_launcher_foreground` (adaptive
foreground) as `setSmallIcon`, which renders as a grey blob.

## Scope
- `data/reminder/ReminderPreferences.kt`: clamp stored values on read —
  `LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))` — for both `friday` and `saturday`.
- `data/reminder/ReminderScheduler.kt`: make `scheduleAll()` total by wrapping the body in
  `runCatching`, logging the failure instead of propagating it.
- `MainActivity.kt:47-51`: stop requesting `POST_NOTIFICATIONS` on cold start; request it from the
  action that needs it (reminder toggle / Settings) with a short rationale, and surface a
  "reminders need notification permission" state when denied.
- `data/reminder/ArtShowNotifier.kt:18`: create the channel once (e.g. from `PixlitApplication`),
  with a comment that importance is immutable after first creation.
- Add `app/src/main/res/drawable/ic_notification.xml` (white monochrome vector) and use it at
  `ArtShowNotifier.kt:24`.

```kotlin
// ReminderPreferences.kt
get() = LocalTime.of(
    prefs.getInt("friday_hour", 20).coerceIn(0, 23),
    prefs.getInt("friday_minute", 0).coerceIn(0, 59)
)
```

## Acceptance criteria
- [ ] `scheduleAll()` with stored `friday_hour = 99` / `friday_minute = 99` returns normally and a
      unit test asserts it (no `DateTimeException` escapes).
- [ ] No permission dialog appears on cold start; denial produces visible Settings state.
- [ ] The notification channel is created once and the small icon is a dedicated monochrome drawable.
- [ ] Alarm behaviour (inexact `setAndAllowWhileIdle`, Friday 20:00 / Saturday 08:00 defaults) is unchanged.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests 'ovh.litapp.pixlit.data.reminder.ReminderSchedulerTest'`
