# Task 53 — UP-11: Preserve source format and define the EXIF policy

**Wave 3 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [06-images-db-background.md](../../../review/06-images-db-background.md) medium-format and [04-data-network.md](../../../review/04-data-network.md) F-13

## Problem
The multipart part always claims `.jpg` + `image/*` regardless of the source format
(`PixelfedRepository.kt:200-201`), because every temp name ends in `.jpg`
(`ImageUtils.kt:310, 412`; `PixelfedRepository.kt:729`). A HEIC/AVIF source (shared from an iPhone or
several Android cameras) is uploaded as a `.jpg`-named `image/*` part with HEIC content — Laravel's
`mimes:` validation sniffs the real type and rejects it after the whole file has transferred, and a
server trusting the type stores a mislabelled asset. In the other direction, when the file is ≤ 8 MB
nothing is converted (`ImageUtils.kt:300-302`), so "resize" is not "normalise to JPEG"; when it does
convert it always uses JPEG (`:333`), dropping all EXIF (DateTimeOriginal, camera data) and flattening
transparent PNGs onto black.

## Scope
- `PixelfedRepository.kt:200-201`: resolve the real mime and display name from the `ContentResolver` and pass them to `asRequestBody`/the multipart filename.
- `ImageUtils.kt:300-302, 333`: decide and document the policy — convert anything that is not JPEG/PNG to JPEG, flatten alpha onto white (or keep PNG).
- Decide explicitly whether EXIF survives; if the server needs orientation/DateTimeOriginal, copy it through `ExifInterface`, otherwise document the drop.
- Give temp files the real extension (`ImageUtils.kt:310, 412`; `PixelfedRepository.kt:729`).

```kotlin
val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
val name = queryDisplayName(uri) ?: file.name
val requestFile = file.asRequestBody(mime.toMediaTypeOrNull())
```

## Acceptance criteria
- [ ] A HEIC/AVIF/PNG source is not mislabelled as `.jpg`/`image/*` on the wire.
- [ ] The EXIF and alpha policy is explicit in code and comments.
- [ ] Filename and `Content-Type` agree with the actual bytes.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests '*ImageUtilsTest'`
