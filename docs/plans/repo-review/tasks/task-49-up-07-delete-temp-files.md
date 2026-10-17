# Task 49 — UP-07: Delete temporary upload files

**Wave 3 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-13 and [06-images-db-background.md](../../../review/06-images-db-background.md) high (temp files)

## Problem
Every processed photo is copied into `cacheDir` and never deleted. `getFileFromUri` creates
`File.createTempFile("upload_", ".jpg", context.cacheDir)` (`PixelfedRepository.kt:727-740`) and
`ImageUtils` returns `resized_upload_*.jpg` (`ImageUtils.kt:310`) and `original_upload_*.jpg`
(`:412`) for the caller to own — but no `delete()` exists anywhere in the upload path. The file used
for the multipart body (`PixelfedRepository.kt:194-199`) is dropped immediately after use. A
6-photo post can leave ~10 files / tens of MB, and the "small enough" branch copies the original
bytes verbatim (`ImageUtils.kt:300-302`), so un-stripped EXIF (GPS, timestamps) sits in cache indefinitely.

## Scope
- `PixelfedRepository.kt:194-199`: delete the per-image file in a `finally` once the multipart request completes.
- Write all temp files into a dedicated `File(context.cacheDir, "uploads")` and clear it at process start (`PixlitApplication.onCreate`).
- Fix the forced extension: `createTempFile("upload_", ".jpg")` (`PixelfedRepository.kt:731`) and the
  `ImageUtils.kt:310,412` names must reflect the real source format (see UP-11 for the mime/filename).
- Do not leak EXIF originals: clean the no-resize copy too.

```kotlin
val uploadDir = File(context.cacheDir, "uploads").apply { mkdirs() }
// PixlitApplication.onCreate(): uploadDir.listFiles()?.forEach { it.delete() }
try { /* resize + upload */ } finally { tempFiles.forEach { it.delete() } }
```

## Acceptance criteria
- [ ] Cache holds no leftover upload/resize files after a completed or failed upload.
- [ ] Leftovers from a previous process are cleared at startup.
- [ ] Un-stripped EXIF originals do not persist in cache.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest` and manual: upload, then `adb shell run-as ovh.litapp.pixlit ls cache/uploads`
