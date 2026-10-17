# Task 22 — DATA-05: Return typed fetch results instead of swallowed errors

**Wave 1 · Priority P1 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-11

## Problem
Domain errors are laundered into success. `getUserTopTagsAndPosts` always ends with `Result.success` (the only failure is `instanceUrl == null`, `PixelfedRepository.kt:445`), which makes the `onFailure` branch at `UploadViewModel.kt:351-356` dead code. `searchPlaces` maps any non-2xx to `Result.success(emptyList())` (`:271-273`), and `getUserCollections`/`createCollection` convert any error into `Result.success` of fabricated local data (`:349-352`, `:390-398`) whose `local_…` ids 404 silently on assignment (`:238-240`), while the UI still reports the collection as added (04 F-11).

## Scope
- Add a `Fetch<T>` result type (`Fresh`/`Cached(cause)`/`Failed(cause)`) and one HTTP-code → typed-cause mapping.
- Return `Fetch` from `getUserTopTagsAndPosts`, `searchPlaces`, `getUserCollections` and `createCollection` in `data/repository/PixelfedRepository.kt`; stop fabricating `Result.success` payloads.
- For collections, either fail honestly or mark fallback items `isLocal = true` and exclude them from `addStatusToCollection`.
- Surface the outcomes in `ui/upload/UploadViewModel.kt`: `Failed` shows an error, `Cached` a "showing cached data" affordance, empty result stays empty.

```kotlin
sealed interface Fetch<out T> {
    data class Fresh<T>(val value: T) : Fetch<T>
    data class Cached<T>(val value: T, val cause: Throwable) : Fetch<T>
    data class Failed(val cause: Throwable) : Fetch<Nothing>
}
```

## Acceptance criteria
- [ ] A 500 from `searchPlaces` surfaces as `Failed`, not as an empty success.
- [ ] A failed `createCollection` is not shown to the user as a created collection.
- [ ] The Upload UI distinguishes "no results" from "fetch failed".
- [ ] `result.fold(onFailure = …)` paths are reachable, not dead code.
- [ ] HTTP failures carry a typed cause (`HttpException`/`PixelfedError(code, message)`) instead of `Exception("… failed: 404")`.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest`
