# Task 14 — UP-03: Stop the EXIF location race

**Wave 1 · Priority P1 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [03-upload-flow.md](../../../review/03-upload-flow.md) HIGH EXIF

## Problem
`UploadViewModel.detectExifLocation` (`UploadViewModel.kt:158-192`) checks the manual-selection guard
only at launch (`UploadViewModel.kt:152`), then writes `_customLocationName`/`_selectedPlace`/
`_isExifAutoDetected` (`:178-180`) after a multi-second blocking `Geocoder` round-trip with no re-check.
If the user picks or types a location meanwhile, their choice is silently overwritten — and because
`upload()` concatenates `_customLocationName` into the caption when `placeId == null`
(`UploadViewModel.kt:536-542`), the wrong city is published in the post body.

## Scope
- `UploadViewModel.kt`: after the geocode completes, re-assert `isUserLocationSelectionManual` (and the
  empty place/custom checks) on `Dispatchers.Main.immediate` before writing any location state.
- Hold detection in a `Job` (`exifDetectionJob`) cancelled by `selectPlace`/`setCustomLocation`/
  `clearLocation` so a superseded detection cannot land.
- Only set `_isExifAutoDetected = true` when a detection value is actually applied.

```kotlin
val cityName = ImageUtils.cityNameFromLocation(context, lat, lng)
withContext(Dispatchers.Main.immediate) {
    if (isUserLocationSelectionManual) return@withContext   // user won meanwhile
    _customLocationName.value = cityName ?: "%.2f, %.2f".format(lat, lng)
    _selectedPlace.value = null
    _isExifAutoDetected.value = true
}
```

## Acceptance criteria
- [ ] Selecting or typing a location while EXIF detection is in flight is never overwritten by the detected city.
- [ ] The published caption `_customLocationName` matches the user's last explicit choice.
- [ ] A unit test drives the manual-selection race and asserts the manual value survives.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests '*UploadViewModelTest'`
