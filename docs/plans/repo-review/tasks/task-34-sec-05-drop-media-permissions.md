# Task 34 — SEC-05: Drop unused media permissions

**Wave 3 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) medium-permissions

## Problem
`AndroidManifest.xml:9-10` declares `READ_MEDIA_IMAGES` and `READ_MEDIA_VISUAL_USER_SELECTED`, but the picker is `ActivityResultContracts.GetMultipleContents()` (`UploadScreen.kt:295-297`, i.e. `ACTION_GET_CONTENT`) and every read goes through the granted URI via `contentResolver.openInputStream(...)` (`ImageUtils.kt:93,113,183,240,368`); no `MediaStore` query is performed anywhere. The app therefore requests broad photo-library access it never uses — a privacy/Play-review liability that can also surface unnecessary permission UI on some OEMs.

## Scope
- Delete `READ_MEDIA_IMAGES` and `READ_MEDIA_VISUAL_USER_SELECTED` from `app/src/main/AndroidManifest.xml`.
- Switch the picker in `UploadScreen.kt` to `ActivityResultContracts.PickMultipleVisualMedia()`, which needs no storage permission.
- Decide `ACCESS_MEDIA_LOCATION` (`AndroidManifest.xml:10`): keep it only if geocoding from original GPS EXIF is confirmed required, otherwise remove it and its read path; record the decision in the PR.
- Do not change decoding, resize or upload logic.

## Acceptance criteria
- [ ] The manifest declares no `READ_MEDIA_*` permission.
- [ ] The picker is `PickMultipleVisualMedia`.
- [ ] Adding a picked photo (preview + upload) still works with no runtime permission prompt.

## Dependencies
- None

## Verification
- `grep -n "READ_MEDIA" app/src/main/AndroidManifest.xml` (no output)
- `./gradlew :app:assembleLocalDevDebug` plus a manual device upload run.

```kotlin
val pickMedia = rememberLauncherForActivityResult(
    ActivityResultContracts.PickMultipleVisualMedia(6)
) { uris -> onImagesPicked(uris) }
```
