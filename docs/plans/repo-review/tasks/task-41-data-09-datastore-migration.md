# Task 41 — DATA-09: Migrate preferences to DataStore

**Wave 3 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [06-images-db-background.md](../../../review/06-images-db-background.md) medium-prefs

## Problem
`TokenManager` (`TokenManager.kt:12-13`) and `ReminderPreferences` (`ReminderPreferences.kt:9-22`)
are SharedPreferences stores where every setter uses the blocking `prefs.edit()….commit()`
(`TokenManager.kt:26, 32, 38, 44, 50, 56, 62, 70`). `MainViewModel.logout()` calls
`tokenManager.clear()` on the main thread (`MainViewModel.kt:103-104`), and the first read loads the
XML synchronously during ViewModel construction (`MainViewModel.kt:33`) — a StrictMode violation.
There is no reactive token state, so a second consumer cannot observe login/logout without polling.
The tag-search JSON (potentially large, `PixelfedRepository.kt:544-554`) is also rewritten with `commit()`.

## Scope
- Add `androidx.datastore:datastore-preferences` to `gradle/libs.versions.toml` and `app/build.gradle`.
- Migrate `TokenManager.kt:26-70` to a `Flow`-based preferences DataStore; drop all `commit()` calls.
- Migrate `ReminderPreferences.kt:9-22` to the same DataStore (or its own).
- Update `MainViewModel.kt:103-104` (logout) and `:33` (init read) to suspend/collect off the main thread.
- Expose token/reminder values as `Flow`s so state changes are observable.

## Acceptance criteria
- [ ] No `prefs.edit()...commit()` remains; no main-thread disk write at logout or first read.
- [ ] Login state is exposed reactively (a collector sees logout without polling).
- [ ] `TokenManagerTest` and `ReminderSchedulerTest` still pass against the new store.

## Dependencies
- SEC-02 (encrypted/excluded credential storage shape decided before the store is swapped)

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests '*TokenManagerTest'`
