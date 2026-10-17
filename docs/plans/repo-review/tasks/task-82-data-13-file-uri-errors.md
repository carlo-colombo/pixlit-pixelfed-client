# Task 82 — DATA-13: Handle file-URI errors properly

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-23

## Problem
`PixelfedRepository.kt:726-742` (`getFileFromUri`) opens the input and output streams without
`use { }`, then `catch (e: Exception) { e.printStackTrace(); null }`. The `null` becomes the
user-facing "Unable to process image file" (`:198`), so "permission revoked", "URI from a dead
provider" and "cacheDir full" are indistinguishable, and a mid-copy exception leaks descriptors.

## Scope
- `PixelfedRepository.kt:726-742`: replace `printStackTrace()` with `Log.e(TAG, "getFileFromUri failed for $uri", e)`
  and return `Result<File>` (or throw a typed `ImageAccessException`) so the message names the cause.
- Wrap both streams in `use { }` so a mid-copy failure cannot leak descriptors.
- Update the caller at `PixelfedRepository.kt:194-198` to map the failure to a specific message
  instead of the generic "Unable to process image file".
- Keep the temp-file creation semantics unchanged (deletion is UP-07's scope).

```kotlin
} catch (e: Exception) {
    Log.e(TAG, "getFileFromUri failed for $uri", e)
    null
}
```

## Acceptance criteria
- [ ] No `printStackTrace()` remains in `PixelfedRepository.kt`; the cause is logged with the URI and throwable.
- [ ] Streams are closed via `use { }` on both the success and failure paths.
- [ ] The upload caller distinguishes at least "cannot read the source" from other failures.
- [ ] `./gradlew :app:testLocalDevDebugUnitTest --tests '*PixelfedRepositoryTest'` passes.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests 'ovh.litapp.pixlit.data.repository.PixelfedRepositoryTest'`
