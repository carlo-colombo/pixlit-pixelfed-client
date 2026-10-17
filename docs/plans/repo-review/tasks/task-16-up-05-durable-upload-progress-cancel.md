# Task 16 — UP-05: Make uploads durable with progress and cancel

**Wave 1 · Priority P1 · Size L · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [03-upload-flow.md](../../../review/03-upload-flow.md) HIGH upload · [04-data-network.md](../../../review/04-data-network.md) F-05

## Problem
`UploadViewModel.kt:537` runs the upload in `viewModelScope`, which is cancelled the moment the Upload
destination leaves composition; `PixelfedRepository.kt:193-243` uploads media in a bare loop and
`return@withContext Result.failure(...)` on the first failure, leaving already-uploaded media orphaned
and giving only one terminal string (`UploadViewModel.kt:525-580`). There is no per-photo progress, no
Cancel affordance (`UploadScreen.kt:645-655`), no persisted job state and no `Idempotency-Key`
(04 F-05), so back-press/process death silently drops a post and a retry re-uploads/duplicates it.

## Scope
- Add a `@HiltWorker` (WorkManager is already wired: `app/build.gradle:116-118`, `androidx.hilt.work`,
  initializer removed in `AndroidManifest.xml:91-99`) that calls the repository seam; the ViewModel only
  enqueues and observes.
- `PixelfedRepository.kt`: accept/emit per-photo progress and send a stable `Idempotency-Key` on
  `createStatus` (`PixelfedApi.kt` `@HeaderMap`); keep `uploadPhotosAndCreateStatus` the single worker seam.
- `UploadViewModel.kt`: expose `uploadProgress`/`uploadedCount` and an `uploadJob`-backed Cancel;
  reset `_isUploading` in `try/finally`; report partial success ("Uploaded N/M; failed on 'name'").
- Foreground notification with progress while the worker runs.

## Acceptance criteria
- [ ] Back-press or process death mid-upload does not lose the post; it resumes/retries via the worker.
- [ ] Retrying a post whose `createStatus` response was lost does not create a duplicate status or re-upload media.
- [ ] The UI shows per-photo progress and can cancel an in-flight upload; `_isUploading` always resets.

## Dependencies
- REL-03 (CI must run unit tests before this lands)

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest` (unit + worker tests) and a manual foreground-service smoke run
