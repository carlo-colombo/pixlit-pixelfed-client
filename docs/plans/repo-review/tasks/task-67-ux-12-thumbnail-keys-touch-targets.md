# Task 67 — UX-12: Key the thumbnail list and enlarge touch targets

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) low Thumbnail row has no item keys

## Problem
`OrderPreviewList.kt:48` uses `itemsIndexed(selectedImageUris) { index, uri -> ... }` with no `key`, while shift-left/right (`UploadViewModel.kt:311-335`) and removal mutate the same list, so Compose reuses slots by position and thumbnail state can stick to the wrong photo. The touch target is `screenWidthDp * 0.15f` (`OrderPreviewList.kt:35`) ≈ 54 dp on a 360 dp screen — barely above the 48 dp minimum, and `contentDescription = "Thumbnail ${index + 1}"` (`:64`) is the only affordance.

## Scope
- Add an item key: `itemsIndexed(selectedImageUris, key = { _, uri -> uri })` (`OrderPreviewList.kt:48`).
- Enforce a real minimum touch target on the thumbnail `Box` (`.size(thumbnailSize)`): keep the visual size but raise it to at least 56 dp via `heightIn(min = ...)`/`sizeIn`, or apply `Modifier.minimumInteractiveComponentSize()` so the clickable area clears 48 dp.
- Keep the focus border (`:55-60`) and click callback (`:62`) behaviour unchanged.
- Keep `maxPhotos` and the header count text (`:36`) driven by the URI list, not by the slot index.

```kotlin
itemsIndexed(selectedImageUris, key = { _, uri -> uri }) { index, uri ->
    Box(modifier = Modifier.size(thumbnailSize).minimumInteractiveComponentSize()...)
}
```

## Acceptance criteria
- [ ] Shift-left/right reorders thumbnails without state carried over from the previous slot.
- [ ] Each thumbnail's clickable area is at least 48 dp (target 56 dp) in both axes.
- [ ] `contentDescription` still numbers thumbnails by their current position after a shift.
- [ ] `./gradlew :app:assembleLocalDevDebug` compiles.

## Dependencies
- None.

## Verification
- `./gradlew :app:assembleLocalDevDebug`, then add 3 photos and shift them with a slow tap sequence.
