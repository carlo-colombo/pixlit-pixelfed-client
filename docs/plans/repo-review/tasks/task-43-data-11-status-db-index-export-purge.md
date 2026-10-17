# Task 43 — DATA-11: Index, export and purge the status database

**Wave 3 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-20 and [06-images-db-background.md](../../../review/06-images-db-background.md) medium (cache/DB)

## Problem
`StatusEntity` has a single-column `@PrimaryKey val id: String` (`StatusEntity.kt:9`) while rows are
scoped by `instanceUrl`, and status ids are numeric and reused across instances — so re-pointing the
app at another instance silently REPLACEs rows (`PixelfedRepository.kt:465-471, 509-513`).
`StatusDao.kt:11-15` filters by `instanceUrl` with no index; `AppDatabase.kt:6` sets
`exportSchema = false`; and `logout()` only calls `tokenManager.clear()` (`MainViewModel.kt:103-109`),
so the previous instance's content survives logout. `StatusDao.clearStatuses` (`:20-21`) has no caller.

## Scope
- `StatusEntity.kt`: add `indices = [Index(value = ["instanceUrl", "createdAt"])]` and a composite
  identity (`instanceUrl` + `id`), bumping the DB version with a migration.
- `AppDatabase.kt:4-7`: set `exportSchema = true`; commit `app/schemas` (per REL-12).
- `MainViewModel.kt:103-109`: on logout call `statusDao.clearStatuses(instanceUrl)` (making the
  currently-dead DAO method live) and drop the tag-search cache.
- Prune on insert — keep the newest N per instance instead of growing forever.

## Acceptance criteria
- [ ] `EXPLAIN`/schema shows the `instanceUrl`(+`createdAt`) index used by the filter+sort.
- [ ] Logout purges that instance's cached statuses and the tag cache; nothing survives.
- [ ] The exported schema is committed and the version/migration path is valid.
- [ ] The table does not grow without bound across repeated fetches.

## Dependencies
- REL-12 (schema export configured so the migration can be versioned and diffed)

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest` and `./gradlew :app:assembleLocalDevDebug`
