# Task 12 — UP-01: Bound the image downscale to avoid OOM

**Wave 0 · Priority P0 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [06-images-db-background.md](../../../review/06-images-db-background.md) critical-C1

## Problem
`utils/ImageUtils.kt:389-406` (`calculateInSampleSize`) only doubles while the sampled image still exceeds the target, so a 48 MP source requesting the 8 MB ratio `√(8/24)=0.577` still decodes at `inSampleSize = 1` → 8000×6000×4 B ≈ 192 MB, plus a 64 MB scaled copy (`:325-331`) ≈ 256 MB peak. `catch (e: Exception)` at `:378-386` cannot catch the resulting `OutOfMemoryError`, so the process crashes on exactly the photos the app exists to upload. Reached from `UploadViewModel.kt:258-261` and `PixelfedRepository.kt:194-196`.

## Scope
- `utils/ImageUtils.kt`: decode via `ImageDecoder` + `setTargetSize(w, h)` (`minSdk 34` guarantees it), replacing the manual sample+rotate dance; use `ALLOCATOR_SOFTWARE` (required before `compress()`) and `MEMORY_POLICY_LOW_RAM`.
- If `BitmapFactory` is kept: bound decoded pixels (e.g. ≤ 12 MP) and allow `inSampleSize` to overshoot, and use `RGB_565` for opaque JPEG sources.
- `utils/ImageUtils.kt:378-386`: add a defensive `catch (e: OutOfMemoryError)` returning `null` so callers surface "Image too large to process" instead of crashing.
- `utils/ImageUtilsTest.kt`: add a regression test on an oversized fixture asserting a bounded decoded pixel count / `inSampleSize >= 2`.

```kotlin
private fun decodeToTarget(context: Context, uri: Uri, w: Int, h: Int): Bitmap? =
    context.contentResolver.openInputStream(uri)?.use { input ->
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(input)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(w, h)
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    }
```

## Acceptance criteria
- [ ] An oversized (e.g. 48 MP) fixture resizes without OOM and yields a bounded bitmap.
- [ ] A regression test asserts the decoded-size bound.
- [ ] `catch (OutOfMemoryError)` prevents a process crash on pathological input.

## Dependencies
- None (UP-12 depends on this).

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests "*ImageUtils*"`
