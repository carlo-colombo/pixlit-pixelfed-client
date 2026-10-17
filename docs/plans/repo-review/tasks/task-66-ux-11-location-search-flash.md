# Task 66 — UX-11: Stop the location-search flash

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) low Location search flashes

## Problem
`onPlaceSearchQueryChanged` sets `_placeSearchQuery` immediately but sets `_isSearchingPlaces.value = true` only after `delay(300)` (`UploadViewModel.kt:187-203`). `PositionSelectionSection.kt:180-186` renders the "Use \"…\" as location name" fallback whenever the query is non-blank and `!isSearchingPlaces`, so the affordance appears for the whole debounce window and then disappears — a flicker that invites mis-taps.

## Scope
- In `onPlaceSearchQueryChanged` (`UploadViewModel.kt:187-203`), set `_isSearchingPlaces.value = true` synchronously before launching the debounce job; keep the blank-query branch resetting it to `false`.
- Leave the search field's spinner bound to the same flag, and do not merge it with the EXIF flag (`_isSearchingPlaces` sharing is a separate low-severity item, UP-13).
- No change to `PositionSelectionSection.kt`'s gating logic.

```kotlin
_placeSearchQuery.value = query
placeSearchJob?.cancel()
if (query.isBlank()) { _placeSearchResults.value = emptyList(); _isSearchingPlaces.value = false; return }
_isSearchingPlaces.value = true           // before the debounce
placeSearchJob = viewModelScope.launch { delay(300); ... }
```

## Acceptance criteria
- [ ] While typing a location, the "Use \"…\" as location name" fallback is not shown during the debounce.
- [ ] The fallback still appears once a search completes with no results.
- [ ] Clearing the field leaves `isSearchingPlaces` false.
- [ ] `./gradlew :app:testLocalDevDebugUnitTest` is green.

## Dependencies
- UP-03 (the EXIF/location race fix touches the same search flag path).

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest` and a manual typing pass in the location field.
