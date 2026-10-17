# Task 50 — UP-08: Prepare each image exactly once

**Wave 3 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [03-upload-flow.md](../../../review/03-upload-flow.md) medium-redundant and [06-images-db-background.md](../../../review/06-images-db-background.md) high (double resize)

## Problem
Each photo is decoded/resized twice: once for the preview metadata (`UploadViewModel.kt:249-266`
runs the full `resizeImageDownToMaxBytes` on `Dispatchers.IO` whenever the current page or resize
toggle changes) and again inside the upload path (`PixelfedRepository.kt:194-198`). The metadata
`combine(...).onEach { updateMetadata(...) }.launchIn(viewModelScope)` (`UploadViewModel.kt:128-142`)
launches an uncancelled job per emission, so swiping 6 pages eagerly re-processes each image and an
older job can finish last and overwrite the shown dimensions. That is seconds of duplicated CPU and
I/O on the critical path, plus the extra cache files of UP-07.

## Scope
- Cache prepared files in the ViewModel (`Map<Uri, PreparedFile>` keyed by `uri + resize`) and pass them into `uploadPhotosAndCreateStatus(imageFiles = …)`.
- Remove the second `resizeImageDownToMaxBytes` from `PixelfedRepository.kt:194-198`; the repository uploads the file it is given.
- Reduce `updateMetadata` to a `private suspend fun` and collect the page flow with `collectLatest` so superseded metadata jobs are cancelled (`UploadViewModel.kt:128-142, 249-266`).
- Invalidate a cached file when the resize toggle changes for that URI.

```kotlin
combine(_currentPage, _selectedImageUris, _resizeTo8Mb) { p, u, r -> Triple(p, u, r) }
    .collectLatest { (page, uris, resize) ->
        val uri = uris.getOrNull(page) ?: return@collectLatest clearMetadata()
        updateMetadata(uri, resize)
    }
```

## Acceptance criteria
- [ ] Each photo is decoded/resized once per `(uri, resize)` for the whole preview+upload cycle.
- [ ] Paging past an image does not re-run the resize and does not start an uncancelled job.
- [ ] The displayed metadata always belongs to the currently shown photo.

## Dependencies
- UP-05 (upload execution seam that consumes the prepared file must exist first)

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests '*UploadViewModelTest'`
