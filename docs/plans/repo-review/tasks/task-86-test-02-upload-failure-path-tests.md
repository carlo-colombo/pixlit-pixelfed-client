# Task 86 — TEST-02: Add upload failure-path tests

**Wave 4 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [03-upload-flow.md](../../../review/03-upload-flow.md) medium-test-gaps · [06-images-db-background.md](../../../review/06-images-db-background.md) low-test-gaps

## Problem
`UploadViewModelTest.kt` covers add/remove/shift, tag insertion, collections, place selection and the
two happy-path uploads, but nothing for upload failure (`_isError`/`_isUploading` reset,
`UploadViewModel.kt:573-578`), the 6-photo cap and duplicate handling (`:382-386`), the
`detectExifLocation` manual-selection race (`:158-192`), `updateMetadata` (`:249-266`), or page
clamping in `removeImageAt` (`:388-402`). `ImageUtilsTest.kt` uses only `Uri.fromFile(...)`
(`:9-30, 60-77`) — no `content://`, no oversized-source bound, no unreadable-URI error path.

## Scope
- `app/src/test/java/ovh/litapp/pixlit/ui/upload/UploadViewModelTest.kt`: add upload-failure tests
  asserting `_isUploading`/`_isError` reset, cap + `distinct()` behaviour, the EXIF/manual-location
  race, and `removeImageAt` page clamping, using the existing `StandardTestDispatcher` harness
  (`:36-76`).
- `app/src/test/java/ovh/litapp/pixlit/utils/ImageUtilsTest.kt`: add a bounded-pixel assertion for an
  oversized source (compressed `inSampleSize` / bounded decoded size), a `content://` fixture through
  Robolectric `ShadowContentResolver`, and an unreadable-URI error-path case.
- Add `androidTest` fixtures only under `app/src/test/resources/fixtures/**`; do not add instrumented tests.

## Acceptance criteria
- [ ] A failing upload leaves `isUploading == false` and `isError == true`, and a retry is possible.
- [ ] Selecting more than six photos keeps six and de-duplicates identical URIs.
- [ ] The oversized-source test fails against the current resize path and passes once UP-01 lands.
- [ ] `./gradlew :app:testLocalDevDebugUnitTest --tests '*UploadViewModelTest' --tests '*ImageUtilsTest'` is green.

## Dependencies
- UP-01, UP-03, UP-04, UP-06

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests 'ovh.litapp.pixlit.ui.upload.UploadViewModelTest' --tests 'ovh.litapp.pixlit.utils.ImageUtilsTest'`
