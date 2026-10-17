# Task 75 — PLAT-02: Fix the reminder design doc and add tests

**Wave 4 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [06-images-db-background.md](../../../review/06-images-db-background.md) low-doc-contradiction

## Problem
`docs/bluesky-art-show-reminders.md:24` says "Scheduling | WorkManager one-shot works with computed
delay (not exact alarms)" and `:40-49` repeats the WorkManager greenfield decision, but the shipped
code schedules with `AlarmManager.setAndAllowWhileIdle` plus `ArtShowReminderReceiver`
(`ReminderScheduler.kt:33-43`, `ArtShowReminderReceiver.kt:17-33`) and uses WorkManager only for the
theme fetch. `README.md:33` matches the code, not the doc. The doc also promises a
"Worker shows fallback notification when Bluesky fails (MockK)" test (`:85-89`) that does not exist
in `app/src/test`.

## Scope
- `docs/bluesky-art-show-reminders.md`: rewrite the Scheduling row (`:24`) and the greenfield
  decision block (`:40-49`) to the alarm-based design (`AlarmManager` + `ArtShowReminderReceiver`),
  keeping the "not exact alarms" rationale and noting the accepted Doze drift (`README.md:33`).
- Replace the promised-test claim (`:85-89`) with the actual test file/name delivered by TEST-04.
- Document the reboot/time-change reschedule path (`ReminderRescheduleReceiver.kt:12-16`, manifest
  `:52-61`) as implemented, and keep the accurate "no already-posted suppression" row.
- No production-code change is expected; if the doc review finds a real behaviour gap, escalate
  before changing code.

## Acceptance criteria
- [ ] No statement in the doc claims WorkManager performs the scheduling; `AlarmManager` +
      `setAndAllowWhileIdle` is described, with the inexactness rationale retained.
- [ ] Every doc claim about scheduling/rescheduling points at a real `file:line` in
      `ReminderScheduler.kt` / `ArtShowReminderReceiver.kt` / `ReminderRescheduleReceiver.kt`.
- [ ] The promised fallback test reference resolves to the test added by TEST-04 (no "planned" wording).
- [ ] `README.md:33` and the doc no longer contradict each other.

## Dependencies
- TEST-04

## Verification
- `grep -n 'WorkManager\|AlarmManager\|TestListenableWorkerBuilder' docs/bluesky-art-show-reminders.md README.md`
