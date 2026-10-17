# Task 40 — DATA-08: Move the tag-search cache into Room

**Wave 3 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-17

## Problem
The tag-search cache is a JSON blob in SharedPreferences: the whole map is read at
`PixelfedRepository.kt:528-541` and written back at `:558-559` via a blocking
`prefs.edit().putString(...).commit()` (`TokenManager.kt:36-41`). Keys are `"${instanceUrl}|$query"`
and entries are never evicted, so the map grows without bound and can lose entries when two searches
interleave (read-modify-write on a `String`, not a transaction). Each write also rewrites the prefs
file that holds the access token. The never-written `cachedTagsJson`/`tagsCacheTime` keys
(`TokenManager.kt:23-34`) leave a permanently-zero "Legacy cached tags" row (`PixelfedRepository.kt:581`).

## Scope
- Add `TagSearchCacheEntity(cacheKey, namesJson, fetchedAt)` + DAO `get(key, minFetchedAt)` / `put(entry)` (REPLACE), with a TTL predicate.
- Register the entity in `AppDatabase` and commit the schema export (REL-12).
- Replace the prefs read-modify-write in `PixelfedRepository.kt:528-559` with the DAO calls.
- Delete `cachedTagsJson`/`tagsCacheTime` (`TokenManager.kt:23-34`) and the "Legacy cached tags" diagnostic row.

```kotlin
@Query("SELECT namesJson FROM tag_search_cache WHERE cacheKey = :key AND fetchedAt > :minFetchedAt")
suspend fun get(key: String, minFetchedAt: Long): String?
@Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(entry: TagSearchCacheEntity)
```

## Acceptance criteria
- [ ] Cache reads/writes are transactional and honour a TTL; stale entries are evicted.
- [ ] No `commit()` (or any prefs JSON blob) remains on the tag-search path.
- [ ] The prefs file that stores the token is no longer rewritten per search.

## Dependencies
- REL-12 (Room schema export must exist to version the new table)

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest`
