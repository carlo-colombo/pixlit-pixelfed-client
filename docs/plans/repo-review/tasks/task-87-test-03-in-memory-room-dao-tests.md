# Task 87 — TEST-03: Add in-memory Room DAO tests

**Wave 4 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-19 · [06-images-db-background.md](../../../review/06-images-db-background.md) low-test-gaps

## Problem
There is no Room test at all: a grep for `inMemoryDatabaseBuilder`/`Room.` in `app/src/test` returns
nothing, so `StatusDao` and `StatusEntity` are untested. Their behaviour is already suspect —
`StatusDao.kt:11-15` filters by `instanceUrl` with no index and sorts by `ORDER BY createdAt DESC`,
while `PixelfedRepository.kt:465-471, 509-513` writes `System.currentTimeMillis()` as `createdAt`, so
cache order is effectively arbitrary.

## Scope
- `gradle/libs.versions.toml` + `app/build.gradle`: add `androidx.room:room-testing` (test-only, shared
  with TEST-01 if that lands first).
- Add `app/src/test/java/ovh/litapp/pixlit/data/db/StatusDaoTest.kt` on
  `Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries()`, matching neurhome's JVM DAO suite.
- Cover insert, replace on conflicting primary key, count, `instanceUrl` filtering, `createdAt`
  ordering, and the logout purge once REL-12 wires `clearStatuses`.
- Assert real `createdAt` values are persisted, not fetch time (pins the DATA-04 fix).

## Acceptance criteria
- [ ] `StatusDaoTest` exercises insert/replace/count/filter/order against real in-memory SQLite.
- [ ] A test inserts two rows with distinct `createdAt` values and asserts `ORDER BY createdAt DESC`.
- [ ] A purge test asserts `clearStatuses(instanceUrl)` removes only that instance's rows.
- [ ] `./gradlew :app:testLocalDevDebugUnitTest --tests '*StatusDaoTest'` is green.

## Dependencies
- REL-12

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests 'ovh.litapp.pixlit.data.db.StatusDaoTest'`
