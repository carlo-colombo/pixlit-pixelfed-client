# Priority Review — Pixlit Core Upload Flow

Scope: `ui/upload/**` (`UploadViewModel.kt`, `UploadScreen.kt`, all 7 `components/*`), `utils/ImageUtils.kt`, `UploadViewModelTest.kt`, plus the repository/CI seams that the flow depends on. Review mode: repository state (no diff was supplied; `watchdog_diff` shows only an unrelated untracked `docs/bluesky-art-show-reminders.md`), so findings are current-state, evidenced by file:line.

---

## What is already good (evidence)

- **Read-only image handling is off the main thread.** Metadata probing and resize run under `withContext(Dispatchers.IO)` in `UploadViewModel.kt:250-266` (`updateMetadata`) and `UploadViewModel.kt:160` (`detectExifLocation`), and the whole network/encode path runs in `withContext(Dispatchers.IO)` at `PixelfedRepository.kt:182`. Bitmap decode (`BitmapFactory.decodeStream` / `.decodeFile`) at `ImageUtils.kt:77,101,300` is therefore never on the main thread. No main-thread decode found in the upload flow.
- **Debounce/duplicate-search de-duplication is correct for tag and place search.** `tagSearchJob?.cancel()` before relaunch (`UploadViewModel.kt:433-441, 445-448`) and `placeSearchJob?.cancel()` (`UploadViewModel.kt:194-197`) prevent stale suggestion races.
- **Hashtag-context parsing is well tested and correct for the boundary cases** — `findHashtagContext` (`UploadViewModel.kt:588-612`) rejects mid-word `#`, and both the parser and `insertTagSuggestion` replacement have direct tests (`UploadViewModelTest.kt:105-124`).
- **Coil sizing is implicit but correct**: `rememberAsyncImagePainter` (`ImagePagerSection.kt:56`, `OrderPreviewList.kt:62`) resolves its size from layout constraints, so the pager/thumbnails do not decode full-resolution bitmaps.
- **Collection toggle / create / selection-pass-through and place-id pass-through are covered** (`UploadViewModelTest.kt:126-143, 163-206, 275-306`).

---

## HIGH

### [SEVERITY: high] EXIF auto-detection races and clobbers a user's manual location / bakes a wrong city into the published caption
- **Evidence:** `UploadViewModel.kt:158-192` — the guard is only checked *at launch* (`UploadViewModel.kt:152`: `} else if (!isUserLocationSelectionManual && _selectedPlace.value == null && _customLocationName.value == null) {`), while the writes happen asynchronously after a blocking `Geocoder` round-trip (`UploadViewModel.kt:170` `ImageUtils.cityNameFromLocation`, seconds on cold start) with no re-check: `UploadViewModel.kt:178-180` `_customLocationName.value = locationName; _selectedPlace.value = null; _isExifAutoDetected.value = true`. `isUserLocationSelectionManual` is read but never re-read on the IO dispatcher (written on main at `:224, :237, :246`).
- **Impact:** If the user types/selects a location while EXIF detection is in flight, their choice is silently overwritten by the detected city. Worse, `upload()` (`UploadViewModel.kt:536-542`) concatenates `_customLocationName` into the caption when `placeId == null`, so the *wrong location text is published into the post body*.
- **Smallest fix:** Re-assert the guard inside the IO block after the geocode and before writing, and cancel superseded detection:
  ```kotlin
  val cityName = ImageUtils.cityNameFromLocation(context, lat, lng)
  withContext(Dispatchers.Main.immediate) {
      if (isUserLocationSelectionManual) return@withContext   // user won meanwhile
      _customLocationName.value = cityName ?: "%.2f, %.2f".format(lat, lng)
      _selectedPlace.value = null
      _isExifAutoDetected.value = true
  }
  ```
  and hold the detection in a `Job` cancelled by `selectPlace/setCustomLocation/clearLocation`.

### [SEVERITY: high] `updateMetadata` launches an uncancelled coroutine per emission → stale metadata and stuck "Calculating…"
- **Evidence:** `UploadViewModel.kt:134-142` — `combine(_currentPage, _selectedImageUris, _resizeTo8Mb).onEach { ... updateMetadata(uri, resize) }.launchIn(viewModelScope)`, and `UploadViewModel.kt:249-266` where `updateMetadata` itself does `viewModelScope.launch { ... }`. There is no `collectLatest`, no job tracking, and no key check on write (`:251-266` sets `_originalMetadata/_resizedMetadata/_isCalculatingResized` unconditionally).
- **Impact:** Flinging the pager (or rapid add/remove) starts N concurrent decodes; an older, slower page can finish last and overwrite `_originalMetadata` with the wrong photo's dimensions/size. `_isCalculatingResized` can also be left `true` after the last emitter's block overwrites it, showing a permanent "Resized: Calculating…" (`MetadataDisplay.kt:41-46`).
- **Smallest fix:** Make the metadata step a single `suspend` function and collect it with `collectLatest`, tagging writes with the uri:
  ```kotlin
  combine(_currentPage, _selectedImageUris, _resizeTo8Mb) { p, u, r -> Triple(p, u, r) }
      .collectLatest { (page, uris, resize) -> val uri = uris.getOrNull(page) ?: return@collectLatest run { clearMetadata() }; updateMetadata(uri, resize) }
  ```
  with `updateMetadata` reduced to a `private suspend fun` (no inner `launch`).

### [SEVERITY: high] Upload has no per-image progress, no cancellation, and no partial-failure recovery; retry re-orphans media
- **Evidence:** `UploadViewModel.kt:525-580` sets only `_isUploading = true/false` and reports one terminal string. `PixelfedRepository.kt:194-227` uploads media in a bare `for` loop and `return@withContext Result.failure(...)` on the first failure (`:211, :222, :226`), leaving already-uploaded media orphaned on the server with no rollback and no "which image failed" info. There is no cancel button anywhere (`UploadScreen.kt:645-655` renders only a spinner).
- **Impact:** The core feature of the app gives zero feedback during a multi-minute, multi-MB upload; a single flaky request loses work with an opaque "Upload failed: …" and a retry re-uploads all 6 images (duplicating orphans). Users cannot abort.
- **Smallest fix:** Add `uploadProgress: Int`/`uploadedCount` to the VM, emit it from a callback the repository loop invokes per image, keep an `uploadJob: Job?` with a "Cancel" affordance calling `uploadJob?.cancel()`, and wrap the flag reset in `try/finally`. Report partial success explicitly:
  ```kotlin
  // repository: onProgress(completed, total) invoked after each mediaId is collected
  _statusMessage.value = "Uploaded $done/$total photos; failed on '${name}'"
  ```

### [SEVERITY: high] Images added or removed during an in-flight upload are silently discarded / the 6-cap is silent; duplicates allowed
- **Evidence:** `addImages` (`UploadViewModel.kt:382-386`) allows picking while uploading — the gallery button `enabled = selectedImageUris.size < maxPhotos` (`UploadScreen.kt:447`) does **not** consider `isUploading`. On success the VM wipes everything: `UploadViewModel.kt:558-572` `_selectedImageUris.value = emptyList()`. The cap is also silent (`UploadViewModel.kt:383` `...take(6)`), the launcher is `GetMultipleContents` with no limit (`UploadScreen.kt:295-299`), and there is no `distinct()` so the same URI can be selected twice and uploaded twice.
- **Impact:** A user who keeps adding photos during a slow upload loses those additions on completion with no warning; picking 10 photos silently keeps 6.
- **Smallest fix:** `enabled = selectedImageUris.size < maxPhotos && !isUploading` on the add button; `distinct()` + a snackbar when `uris.size` exceeds the remaining slots; and only clear the URIs that were actually submitted:
  ```kotlin
  val submitted = uris.toSet()
  _selectedImageUris.value = _selectedImageUris.value.filterNot { it in submitted }
  ```

---

## MEDIUM

### [SEVERITY: medium] Architecture: an 845-line screen and 606-line ViewModel with 27 independent `MutableStateFlow`s and a 45-parameter composable
- **Evidence:** 27 `private val _… = MutableStateFlow` at `UploadViewModel.kt:33-119` (plus 2 mutable `var` jobs and `isUserLocationSelectionManual` at `:41, :118, :119`). Every one is read eagerly at the top of `UploadScreen` (`UploadScreen.kt:94-122`) and passed positionally into `UploadContent` (`UploadScreen.kt:200-256`), which is also the entry point for 5 unrelated tabs (`UploadScreen.kt:270` — `listOf("Upload", "Social", "Last", "Debug", "Settings")`), including `SocialScreen` (`:655-660`) and a Debug tab (`:676`) in the production build.
- **Impact:** State is "tiled" in the worst way: no single source of truth for a screen state, no `UiState`, and any one field changing recomposes the whole tree. The view hosts feed-browsing (explicitly out of scope per `AGENTS.md`) and debug tooling, which is architecture drift away from the stated MVVM/UDF goal.
- **Concrete seams (keep minimal, idiomatic):**
  - `ui/upload/UploadUiState.kt` — one immutable `data class UploadUiState(...)`; VM exposes a single `StateFlow<UploadUiState>`. Collapses `UploadScreen.kt:94-122` to one `collectAsState()` and `UploadContent`'s signature (`UploadScreen.kt:200-256`) to `(state: UploadUiState, callbacks: UploadCallbacks)`.
  - `ui/upload/image/ImagePipeline.kt` — extract `addImages/removeImageAt/shiftLeft/shiftRight/onPageChanged/updateMetadata` (`UploadViewModel.kt:382-431, 249-266`); owns `_selectedImageUris`, `_currentPage`, metadata/resize flags.
  - `ui/upload/location/LocationController.kt` — `detectExifLocation/onPlaceSearchQueryChanged/selectPlace/setCustomLocation/clearLocation` (`UploadViewModel.kt:158-247`).
  - `ui/upload/caption/CaptionController.kt` — `onCaptionChanged/searchTagSuggestions/insertHash/insertTag/insertTagSuggestion/prefillArtShowTags` + top-level `findHashtagContext` (`UploadViewModel.kt:432-523, 584-614`).
  - `ui/upload/collection/CollectionController.kt` — `fetchCollections/toggle/create` (`UploadViewModel.kt:269-314`).
  - `ui/upload/UploadSubmitter.kt` — `upload()` (`UploadViewModel.kt:525-580`) with progress + cancel from the finding above.
  - Screen split: `UploadScreen.kt` keeps only Scaffold/tabs; extract `upload/UploadTabContent.kt` (tab 0, `UploadScreen.kt:327-654`), `upload/CaptionField.kt` (`:465-545`), move Debug tab to `ui/debug/DebugTab.kt` (`:676-787`), move `SettingsContent` to `ui/settings/SettingsTab.kt` (`:793-812`), and move `SocialScreen` out of the Upload feature entirely.

### [SEVERITY: medium] Rotation/process death loses the whole in-progress post
- **Evidence:** No `SavedStateHandle` in the VM constructor (`UploadViewModel.kt:27-31`); selected URIs, caption, location and collections live only in `StateFlow`s. UI-local state uses plain `remember`: `selectedTab` (`UploadScreen.kt:258`), `showTagPopup`/`captionFocused` (`:263-264`), `tagSearchQuery` (`:302`). Picking photos via `GetMultipleContents` (`:295`) sends the app to the background — the highest-risk moment for process death.
- **Impact:** Launching the gallery can kill the process; on return the user gets an empty upload screen. Rotating the device silently resets the tab to "Upload".
- **Smallest fix:** `rememberSaveable` for `selectedTab`/`tagSearchQuery`, and `SavedStateHandle` for the caption text + selected URIs (a `List<String>` of uri strings).

### [SEVERITY: medium] Performance: every keystroke recomposes the entire screen and re-derives tag suggestions
- **Evidence:** All 27 states are read in `UploadScreen`'s body (`UploadScreen.kt:94-122`), so a caption keystroke (`_captionState`) recomposes `UploadScreen` → the 45-arg `UploadContent`. Inside, `findHashtagContext(captionState)` (`:301`) and `tagSuggestions` (`:315-318` `(remoteTagSuggestions + topTags.map { it.name }).filter{…}.distinctBy{…}.take(30)`) are re-allocated on every pass with no `remember`/`derivedStateOf`; the cursor math (`:462-467`) recomputes too.
- **Impact:** Per-character allocation and full-tree recomposition on the app's primary screen; wasted work scales with caption length.
- **Smallest fix:** `val suggestions by remember { derivedStateOf { … } }` keyed on `remoteTagSuggestions/topTags/tagSearchQuery`, and push the collected state down into the leaf composables (or adopt the single-`UiState` from above so only consumers of the changed field recompose).

### [SEVERITY: medium] `RecentPostsSection` re-parses HTML and recompiles a regex per card per recomposition
- **Evidence:** `RecentPostsSection.kt:75` `val text = Html.fromHtml(rawText, …)` and `:79` `Regex("(?i)(?<!\\w)${Regex.escape(tag)}(?!\\w)").containsMatchIn(text)` are inside the `RecentPostCard` composable body, so they run on every recomposition of every visible card; `:71` `statuses.take(visibleRecentPostCount)` allocates a fresh list on every recomposition; `Html.fromHtml` is also main-thread work.
- **Impact:** Scrolling/refreshing the "Last" tab does O(cards) HTML parses + regex compilations.
- **Smallest fix:** `val text = remember(rawText) { Html.fromHtml(rawText, Html.FROM_HTML_MODE_LEGACY).toString().trim() }`, hoist a constant `private val TAG_WORD_REGEX = "…".toRegex()` (or match with a single precompiled pattern), and pass an already-sliced list from the caller.

### [SEVERITY: medium] Redundant work and leaks around image processing
- **Evidence:** Resize is computed for the preview (`UploadViewModel.kt:258-263` `ImageUtils.resizeImageDownToMaxBytes`) and then computed **again** inside the upload path (`PixelfedRepository.kt:194-198`). Both that function (`ImageUtils.kt:255-320`) and `getFileFromUri` (`PixelfedRepository.kt:726-732`) write `File.createTempFile(...)` into `cacheDir` and never delete them. Additionally, `refreshCacheSummary()` is invoked after **every** debounced tag search (`UploadViewModel.kt:454`), doing JSON+DB work per keystroke.
- **Impact:** Up to 6 full decode→scale→re-encode passes are done twice per upload; unbounded cache growth; needless IO on each typed character.
- **Smallest fix:** Reuse the already-resized temp file (pass a `preparedFile` into the repository), delete temp files in a `finally`/`use{}`, and drop `refreshCacheSummary()` from the suggestion path (it is a Debug-tab concern, `UploadScreen.kt:288-290`).

### [SEVERITY: medium] Test coverage gaps for the parts that matter most, and CI never runs the tests
- **Evidence:** `UploadViewModelTest.kt` covers add/remove/shift, tag insertion, collections, place selection and the two happy-path uploads — but there is **no** test for: upload failure (`_isError`/`_isUploading` reset, `UploadViewModel.kt:573-578`), the 6-photo cap/duplicate handling (`:382-386`), `detectExifLocation` and the manual-selection race (`:158-192`), `updateMetadata` (`:249-266`), `insertHash` (`:465`), `prefillArtShowTags` (`:513`), `loadMoreRecentPosts` (`:362`), and `removeImageAt` page clamping (`:388-402`). The only CI workflow, `.github/workflows/build.yml`, runs `assemble…Release` and signing only — no `testUnitTest` task.
- **Impact:** Regressions in the core flow's failure paths ship silently; the existing tests are a safety net that CI does not pull on.
- **Smallest fix (blueprint reference):** neurhome runs `./gradlew :app:assembleDevDebug :app:testDevDebugUnitTest :app:lintDevDebug --parallel` in a dedicated `.github/workflows/pr.yml` (`pr.yml:78-82`) with a required-checks job (`pr.yml:227-230`). Add an equivalent test+lint step to Pixlit's PR path. Add the VM tests above using the existing `StandardTestDispatcher` harness (`UploadViewModelTest.kt:36-76`).

---

## LOW

- **[SEVERITY: low] `showTagPopup` never resets when the hashtag context disappears.** `UploadScreen.kt:498` gates the popup on `showTagPopup && hashtagContext != null`, but `hashtagContext` becoming null hides it without setting `showTagPopup = false`. The next time the cursor enters a `#word`, the popup reappears unprompted. Fix: `LaunchedEffect(hashtagContext) { if (hashtagContext == null) showTagPopup = false }` (`:301`).
- **[SEVERITY: low] `_isSearchingPlaces` is shared** by EXIF auto-detection (`UploadViewModel.kt:169, 171`) and the place search box (`:194-216`), so the search field's spinner lights up during EXIF geocoding. Use a separate `_isDetectingExifLocation` flag.
- **[SEVERITY: low] Silent fallback masks the real empty state.** `fetchCollections` falls back to static defaults on an empty result (`UploadViewModel.kt:276-279`), so a user with zero collections can never see "you have none".
- **[SEVERITY: low] `addImages` clears the message but not `_isError`** (`UploadViewModel.kt:385`), leaving the error flag `true` for the next status render.
- **[SEVERITY: low] Misleading pagination copy**: the button always says "Load 10 more posts" (`RecentPostsSection.kt:60`) even when the click merely reveals already-fetched items (`UploadViewModel.kt:363-366`).
- **[SEVERITY: low] Double tag-search per keystroke.** A caption edit triggers `searchTagSuggestions` from `onCaptionChanged` (`UploadViewModel.kt:439`) *and* from the screen's `LaunchedEffect(tagSearchQuery)` (`UploadScreen.kt:304-306`). The cancel-dedupe saves it, but the second path is redundant.
- **[SEVERITY: low] Repository catches `CancellationException`.** `catch (e: Exception)` at `PixelfedRepository.kt:247` (and `:490`) swallows coroutine cancellation, so cancelling an upload mid-request returns a failure `Result` instead of propagating cancellation. Prefer rethrowing `CancellationException` before the generic catch.

---

## Top 5 actions

1. **Fix the two stale-state races** (HIGH): re-guard and job-cancel EXIF detection (`UploadViewModel.kt:158-192`) and convert `updateMetadata` to a `collectLatest` suspend step (`UploadViewModel.kt:124-142, 249-266`) — these can publish a wrong location and show wrong photo metadata.
2. **Make the upload observable and interruptible** (HIGH): add per-image progress + cancel + partial-failure messaging, and only clear the URIs actually submitted (`UploadViewModel.kt:525-580`, `UploadScreen.kt:645-655`, `PixelfedRepository.kt:194-227`).
3. **Gate the add-photos button on `!isUploading`, dedupe, and surface the 6-cap** (HIGH) — `UploadScreen.kt:447`, `UploadViewModel.kt:382-386`.
4. **Introduce `UploadUiState` + split the 845-line screen / 606-line ViewModel along the seams above** (MEDIUM), starting with the single-`StateFlow` state object and relocating `SocialScreen`/Debug out of the upload feature.
5. **Wire unit tests into PR CI and close the failure-path coverage gaps** (MEDIUM), mirroring neurhome's `pr.yml` `assemble…:app:testDevDebugUnitTest :app:lintDevDebug` invocation.

**Merge verdict:** OK with notes — no crash/compile blocker found, but the HIGH items (wrong published location, stale metadata, unobservable/uninterruptible upload, silent loss of in-flight additions) should be addressed before the upload flow is called release-ready.