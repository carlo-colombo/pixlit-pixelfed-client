# Task 15 — UP-04: Cancel superseded metadata work

**Wave 1 · Priority P1 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [03-upload-flow.md](../../../review/03-upload-flow.md) HIGH updateMetadata

## Problem
`UploadViewModel.kt:134-142` runs `combine(_currentPage, _selectedImageUris, _resizeTo8Mb).onEach { … }`
and `updateMetadata` (`UploadViewModel.kt:249-266`) launches a fresh uncancelled coroutine per emission
with no `collectLatest`, no job tracking and no URI key on write. Flinging the pager starts N concurrent
decodes; a slower older page can finish last and overwrite `_originalMetadata` with the wrong photo's
dimensions/size, and `_isCalculatingResized` can be left `true` forever, showing a permanent
"Resized: Calculating…" (`MetadataDisplay.kt:41-46`). The same shape affects the EXIF/location state
(06-images-db-background.md, medium).

## Scope
- `UploadViewModel.kt`: convert `updateMetadata` to a `private suspend fun` (remove the inner
  `viewModelScope.launch`) and collect the combine with `collectLatest` so superseded pages are cancelled.
- Tag every metadata write with the URI it belongs to (or key state by URI) so a stale completion cannot
  apply; clear `_isCalculatingResized` per terminator, not with one shared flag.
- Update the `combine` block to handle a missing URI at `currentPage` by clearing metadata.

```kotlin
combine(_currentPage, _selectedImageUris, _resizeTo8Mb) { p, u, r -> Triple(p, u, r) }
    .collectLatest { (page, uris, resize) ->
        val uri = uris.getOrNull(page) ?: return@collectLatest clearMetadata()
        updateMetadata(uri, resize)          // private suspend fun, no inner launch
    }
```

## Acceptance criteria
- [ ] Rapid pager flings never leave metadata from a different photo on screen.
- [ ] "Resized: Calculating…" clears once the newest request finishes.
- [ ] A unit test swipes to a slow page then a fast page and asserts the fast page's metadata wins.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests '*UploadViewModelTest'`
