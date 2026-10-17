# Task 55 — REF-01: Introduce UploadUiState and split the ViewModel/screen

**Wave 4 · Priority P2 · Size L · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [03-upload-flow.md](../../../review/03-upload-flow.md) medium-architecture · [05-ux-compose.md](../../../review/05-ux-compose.md) medium-`UploadContent` is a ~600-line composable

## Problem
`UploadViewModel` holds 27 independent `MutableStateFlow`s (`UploadViewModel.kt:33-119`) and `UploadScreen` reads every one of them eagerly at the top of its body (`UploadScreen.kt:94-122`), then forwards them positionally into a 45-parameter `UploadContent` (`UploadScreen.kt:200-256`). There is no single source of truth for screen state, so any one field changing recomposes the whole tree, and the two files total ~1450 lines with 5 unrelated tabs inlined.

## Scope
- Add `ui/upload/UploadUiState.kt`: one immutable `data class` (defaults for every field) plus a `UploadCallbacks` holder for the screen's lambdas.
- `UploadViewModel` exposes exactly one `StateFlow<UploadUiState>` and updates it via `update { it.copy(...) }`; delete the public per-field flows.
- Extract collaborators along the review's seams, each independently shippable: `ui/upload/image/ImagePipeline.kt` (`UploadViewModel.kt:249-266, 382-431`), `ui/upload/location/LocationController.kt` (`:158-247`), `ui/upload/caption/CaptionController.kt` (`:432-523, 584-614`), `ui/upload/collection/CollectionController.kt` (`:269-314`), `ui/upload/UploadSubmitter.kt` (`:525-580`).
- Rewrite `UploadScreen`'s state reads as a single `collectAsState()` and pass `(state, callbacks)` into `UploadContent`.
- Keep tab extraction (REF-02) and behavioural fixes (UP-03/UP-04) out of this task; the UI must render and behave exactly as today.

```kotlin
// ui/upload/UploadUiState.kt
data class UploadUiState(
    val selectedImageUris: List<Uri> = emptyList(),
    val currentPage: Int = 0,
    val resizeTo8Mb: Boolean = true,
    val isUploading: Boolean = false,
    // one field per MutableStateFlow currently declared at UploadViewModel.kt:33-119
)
```

## Acceptance criteria
- [ ] `UploadViewModel` exposes one `StateFlow<UploadUiState>`; no remaining public `MutableStateFlow` fields.
- [ ] `UploadContent`'s signature is `(state, callbacks)` plus stable dependencies only; no positional field list.
- [ ] No behaviour change: existing `UploadViewModelTest` assertions pass with only constructor/state-access wiring updated.
- [ ] `./gradlew :app:testLocalDevDebugUnitTest` is green.

## Dependencies
- UP-03, UP-04 (land the stale-state fixes before moving the state into one object).

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest :app:assembleLocalDevDebug`
