# Task 88 — TEST-04: Add reminder scheduling tests

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [06-images-db-background.md](../../../review/06-images-db-background.md) low-test-gaps

## Problem
`ReminderSchedulerTest.kt:7-18` covers only `nextRunDelay` (two UTC cases). Nothing covers
`schedule`/`cancel`/`rescheduleAll`, `PendingIntent` identity/flags, DST transitions,
`ReminderPreferences` defaults, or the `ArtShowNotifier` / `ArtShowReminderWorker` fallback path that
`docs/bluesky-art-show-reminders.md:85-89` claims is tested. `ReminderPreferences.kt:12-13` reads
raw ints with no validation, so the untested boundary is exactly where a startup crash lives.

## Scope
- `app/src/test/java/ovh/litapp/pixlit/data/reminder/ReminderSchedulerTest.kt`: add a DST case for
  `nextRunDelay` using a `ZonedDateTime` around the spring-forward boundary, and assert `cancel()`
  resolves the same `PendingIntent` created by `schedule()` (request code `day.value`,
  `FLAG_IMMUTABLE`, `FLAG_NO_CREATE`).
- Add a `ReminderPreferences` test for the documented defaults (Friday 20:00, Saturday 08:00) and for
  out-of-range stored values — the latter must agree with PLAT-01's clamping.
- Add a `TestListenableWorkerBuilder` test for the theme-fetch fallback notification
  (`ArtShowReminderReceiver.kt:26-31`) so the doc's promised test exists.
- No production change is expected; escalate if a test exposes a real defect.

## Acceptance criteria
- [ ] A DST-boundary case for `nextRunDelay` passes (no `Duration` drift across spring-forward).
- [ ] `schedule()` then `cancel()` operates on the same `PendingIntent`.
- [ ] Defaults are asserted; out-of-range stored hour/minute do not throw.
- [ ] The fallback-notification test exists and is referenced by `docs/bluesky-art-show-reminders.md`.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests 'ovh.litapp.pixlit.data.reminder.*'`
