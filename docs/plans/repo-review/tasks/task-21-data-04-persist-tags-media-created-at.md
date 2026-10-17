# Task 21 — DATA-04: Persist tags, media and the real created_at

**Wave 1 · Priority P1 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-10 · [06-images-db-background.md](../../../review/06-images-db-background.md) "Cached status timestamps are 'now'"

## Problem
The cache is lossy: `StatusEntity.kt:8-16` has no `tags`/`media_attachments` columns, and the entity→item mapping restores only `id`, `content`, `text`, `description` (`PixelfedRepository.kt:422-430`), while the live aggregator reads `status.tags` and `status.mediaAttachments` (`:701-706`) — cached tag clouds are therefore not the live ones. `createdAt` is written as `System.currentTimeMillis()` at insert time (`:465-471`, `:509-513`) while `StatusItem` (`data/api/PixelfedApi.kt:191-202`) discards the API's `created_at`, so `ORDER BY createdAt DESC` (`StatusDao.kt:11`) is effectively arbitrary and reshuffles on every REPLACE.

## Scope
- Extend `data/db/StatusEntity.kt` with `tagsJson` and `mediaDescriptionsJson` columns plus a real `createdAt`, keeping `System.currentTimeMillis()` only as a separate `fetchedAt` for staleness/pruning.
- Add the real timestamp to `StatusItem` (`data/api/PixelfedApi.kt:191-202`), e.g. `@SerializedName("created_at") val createdAt: String? = null`, and convert it once.
- Add a `toEntity()`/`toItem()` mapper pair and use it at `PixelfedRepository.kt:422-430, 465-471, 509-513`, so the cache read rebuilds exactly the fields the aggregator consumes.
- Bump `AppDatabase` to version 2 with a hand-written `Migration` adding the columns and backfilling `createdAt` from `fetchedAt` (schema export is REL-12, so the migration must be written by hand now).
- Add a mapper round-trip JUnit test asserting cached tag counts equal live tag counts for a fixture status set.

```kotlin
@Entity(tableName = "statuses")
data class StatusEntity(
    @PrimaryKey val id: String,
    val content: String?, val text: String?, val description: String?,
    val createdAt: Long,   // ← status.created_at, not now()
    val fetchedAt: Long,   // ← was createdAt (System.currentTimeMillis())
    val instanceUrl: String,
    val tagsJson: String?, val mediaDescriptionsJson: String?
)
```

## Acceptance criteria
- [ ] A cache read yields the same top-tag counts as the live fetch for the same statuses.
- [ ] `createdAt` persisted for a status equals the API `created_at`, not insert time.
- [ ] `ORDER BY createdAt DESC` returns posts in real post order after a re-fetch.
- [ ] Migration from v1 to v2 runs without data loss on an existing install.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest`
