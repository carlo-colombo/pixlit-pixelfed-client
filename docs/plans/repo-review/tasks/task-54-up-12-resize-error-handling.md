# Task 54 — UP-12: Harden resize error handling and stream cleanup

**Wave 3 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [06-images-db-background.md](../../../review/06-images-db-background.md) medium-IO

## Problem
`resizeImageDownToMaxBytes` (`ImageUtils.kt:294-352`) has no `try`/`catch` and does not `use {}` its
stream: `FileOutputStream(tempFile)` ... `scaledBitmap.compress(...)` ... `outStream.close()`
(`:331-334`). A disk-full, `FileNotFoundException` or decoder error throws straight out of the
callers' coroutines — `UploadViewModel.kt:258-261` (`withContext(Dispatchers.IO) { resizeImageDownToMaxBytes(...) }`)
has no handler, so it crashes the app. Early returns at `:325-326` leave the already-created temp file
(`:310`) behind, and a throw from `compress`/`close` leaks the file descriptor and leaves a partial file.

## Scope
- Make the public entry points total: wrap the body in `try { … } catch (t: Throwable) { Log.w(TAG, "resize failed", t); null }`.
- Use `FileOutputStream(tempFile).use { scaledBitmap.compress(JPEG, quality, it) }` so the stream always closes.
- Delete the temp file on every early-return/throw path.
- Callers (`UploadViewModel.kt:258-261`, `PixelfedRepository.kt:194-196`) render a clear error instead of a silent no-op.
- Recycle/drop the source and scaled bitmaps on the failure path to bound memory.

```kotlin
fun resizeImageDownToMaxBytes(context: Context, uri: Uri, maxSizeBytes: Long = MAX_BYTES_8MB): File? =
    try { /* … */ } catch (t: Throwable) { Log.w(TAG, "resize failed", t); null }
```

## Acceptance criteria
- [ ] A disk-full/decoder error returns `null` and surfaces an error, never crashes the app.
- [ ] No file descriptor leaks on `compress`/`close` failure.
- [ ] No partial `resized_upload_*` file remains after any failure.

## Dependencies
- UP-01 (the bounded decode path that this hardening wraps)

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests '*ImageUtilsTest'`
