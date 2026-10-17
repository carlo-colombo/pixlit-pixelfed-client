# Task 39 — DATA-07: Count the cache with a projection query

**Wave 3 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-14

## Problem
`getCacheSummary()` materialises every cached status row just to read its size —
`statusDao.getStatuses(it).size` at `PixelfedRepository.kt:578-580`. Worse, `searchTagSuggestions`
calls `refreshCacheSummary()` after every debounced tag search (`UploadViewModel.kt:453-460`), so a
single keystroke triggers a full `SELECT *` plus two JSON re-parses. The cost grows with the
unbounded cache (F-10) on a path the user hits by typing.

## Scope
- `StatusDao.kt`: add a projection query and stop loading rows to count them.
- `PixelfedRepository.kt:578-580`: call `countStatuses(...)` instead of `getStatuses(...).size`.
- `UploadViewModel.kt:453-460`: drop the eager `refreshCacheSummary()` from the suggestion path;
  refresh only when the Debug/cache panel is actually opened.

```kotlin
@Query("SELECT COUNT(*) FROM statuses WHERE instanceUrl = :instanceUrl")
suspend fun countStatuses(instanceUrl: String): Int
```

## Acceptance criteria
- [ ] Counting cached statuses issues no `SELECT *` and allocates no row objects.
- [ ] A debounced tag search performs no status-table scan and no JSON parse.
- [ ] The cache summary still reports the correct count when the panel is opened.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests '*PixelfedRepositoryTest'`
