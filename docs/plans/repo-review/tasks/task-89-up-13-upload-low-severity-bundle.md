# Task 89 — UP-13: Upload low-severity fixes bundle

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [03-upload-flow.md](../../../review/03-upload-flow.md) LOW list

## Problem
Seven low-severity defects share the upload feature: `showTagPopup` never resets when the hashtag
context disappears (`UploadScreen.kt:498` gates on `showTagPopup && hashtagContext != null`, `:301`);
`_isSearchingPlaces` is shared by EXIF detection and the search box (`UploadViewModel.kt:169, 171,
194-216`); `fetchCollections` falls back to static defaults on an empty result so "you have none" is
unreachable (`:276-279`); `addImages` clears the message but not `_isError` (`:385`); the pagination
button always says "Load 10 more posts" (`RecentPostsSection.kt:60`); a caption edit triggers
`searchTagSuggestions` twice (`UploadViewModel.kt:439` + `UploadScreen.kt:304-306`); and the
repository swallows `CancellationException` (`PixelfedRepository.kt:247`, `:490`).

## Scope
- `ui/upload/UploadScreen.kt`: reset `showTagPopup` via `LaunchedEffect(hashtagContext) { if (hashtagContext == null) showTagPopup = false }`;
  drop the redundant `LaunchedEffect(tagSearchQuery)` tag search (`:304-306`).
- `ui/upload/UploadViewModel.kt`: split `_isDetectingExifLocation` out of `_isSearchingPlaces`; clear
  `_isError` in `addImages`; stop substituting static defaults for a genuinely empty collection list;
  make the "load more" label reflect whether new items are fetched or merely revealed.
- `data/repository/PixelfedRepository.kt:247, 490`: rethrow `CancellationException` before the generic
  `catch (e: Exception)`.
- Keep the change set inside these three files; REF-01 owns the larger split.

## Acceptance criteria
- [ ] Dismissing the hashtag popup and re-entering `#word` shows it only on demand.
- [ ] The place-search spinner does not light up during EXIF geocoding.
- [ ] A user with zero collections sees an empty state, not fabricated defaults.
- [ ] Cancelling an in-flight upload propagates cancellation rather than returning a failure result.
- [ ] `./gradlew :app:testLocalDevDebugUnitTest --tests '*UploadViewModelTest'` is green.

## Dependencies
- REF-01

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests 'ovh.litapp.pixlit.ui.upload.UploadViewModelTest'`
