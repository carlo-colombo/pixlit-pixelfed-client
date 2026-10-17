# Task 31 — REL-12: Configure and gate the Room schema export

**Wave 2 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #8 · [06-images-db-background.md](../../../review/06-images-db-background.md) medium-Room

## Problem
Room is used with KSP (`app/build.gradle:117-118`) but the schema export is not configured: there is no `room { schemaDirectory(...) }` / `room.schemaLocation` argument, no `app/schemas` directory exists, and `AppDatabase.kt:4-7` sets `exportSchema = false`. There is also no `addMigrations`/`fallbackToDestructiveMigration` in `di/DatabaseModule.kt:21-26`, so the first `version = 2` will throw `IllegalStateException` at open with no committed schema to diff against or test. The blueprint-standard "schema committed" gate is impossible until this lands.

## Scope
- Configure the export path in `app/build.gradle` (`room { schemaDirectory("$projectDir/schemas") }`, or the equivalent `ksp { arg('room.schemaLocation', …) }`).
- Set `exportSchema = true` in `app/src/main/java/ovh/litapp/pixlit/data/db/AppDatabase.kt:5`.
- Generate and commit the version-1 schema JSON under `app/schemas/**`.
- Add the CI dirtiness gate (`git status --porcelain -- app/schemas`) to the PR workflow from REL-09.
- Do not add migrations, indexes or entity changes; those are DATA-11.

## Acceptance criteria
- [ ] Building the app writes `app/schemas/<db-class>/1.json` and it is committed.
- [ ] `AppDatabase.kt` declares `exportSchema = true`.
- [ ] CI fails when a Room schema change is left uncommitted.

## Dependencies
- BUILD-12

## Verification
- `./gradlew :app:assembleLocalDevDebug && git status --porcelain -- app/schemas`
- `grep -n "schemaDirectory\\|room.schemaLocation\\|exportSchema" app/build.gradle app/src/main/java/ovh/litapp/pixlit/data/db/AppDatabase.kt`

```kotlin
@Database(entities = [StatusEntity::class], version = 1, exportSchema = true)
```
