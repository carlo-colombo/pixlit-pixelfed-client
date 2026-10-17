# Task 52 — UP-10: Send per-image alt text, clamped

**Wave 3 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-16

## Problem
The caption is sent as the alt `description` of every image, with no length guard. Inside the
per-image loop (`PixelfedRepository.kt:203-209`) `caption.toRequestBody(...)` is passed as
`description` for each of up to 6 photos, and `upload()` appends the hashtags and location block to
the caption first (`UploadViewModel.kt:543-546`). Semantically this duplicates the caption instead of
describing the image; practically, Pixelfed validates media `description` length, so an over-long
caption turns `POST api/v1/media` into a 4xx and the whole post fails with `"Media upload failed: 422 …"`
(`:211`) before any status exists — orphaning the uploaded media.

## Scope
- Add an optional per-photo alt field in the UI, held as `altTexts: Map<Uri, String>` in the ViewModel.
- `PixelfedRepository.kt:203-209`: send `description` only when the user supplied alt text for that URI.
- Clamp defensively to a named constant (`MAX_ALT_CHARS`), verified against the target instance's limit
  in the Pixelfed API docs, and skip empty/blank values.
- Stop passing the caption as a description.

```kotlin
val alt = altTexts[uri]?.trim()?.take(MAX_ALT_CHARS)?.takeIf { it.isNotEmpty() }
val descRequestBody = alt?.toRequestBody("text/plain".toMediaTypeOrNull())
```

## Acceptance criteria
- [ ] The caption is never used as an image `description`.
- [ ] An over-long caption/alt text cannot 422 the upload after media is uploaded.
- [ ] Each photo can carry its own alt text, and blank alt sends no `description` part.

## Dependencies
- UP-05 (upload execution seam that owns the per-image request)

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests '*PixelfedRepositoryTest'`
