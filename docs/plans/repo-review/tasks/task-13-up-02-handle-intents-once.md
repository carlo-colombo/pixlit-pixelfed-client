# Task 13 — UP-02: Handle intents exactly once

**Wave 1 · Priority P1 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [06-images-db-background.md](../../../review/06-images-db-background.md) high (synthesis C2)

## Problem
`MainActivity.kt:53` calls `viewModel.handleIntent(intent)` from `onCreate` (which re-runs on every
configuration change) and again from `onNewIntent` (`MainActivity.kt:102-107`, with `setIntent`).
The share path keeps the URI resident in `MainViewModel._sharedImageUris` (`MainViewModel.kt:44-59`)
and `UploadScreen.kt:131-135` appends it once per emission, so rotating after a share appends the
same photo twice (`UploadViewModel.kt:382-385` → `.take(6)`), consuming two of six slots and
uploading the file twice; the OAuth branch re-exchanges an already-spent `code` (`MainViewModel.kt:76-99`).

## Scope
- `MainActivity.kt`: guard initial delivery with `savedInstanceState == null`; after handling, call
  `intent.removeExtra(Intent.EXTRA_STREAM)` and `intent.data = null` so Activity recreation sees nothing.
- `MainViewModel.kt`: treat the payload as one-shot — consume the share/OAuth extras on handling rather
  than leaving them resident for re-delivery.
- Add a `MainViewModelTest` case that calls `handleIntent(...)` twice with the same share intent and the
  same OAuth deep link and asserts no duplicate URI and no second token exchange.

```kotlin
if (savedInstanceState == null) {
    viewModel.handleIntent(intent)
    intent.removeExtra(Intent.EXTRA_STREAM)   // survives config changes with the Activity
    intent.data = null
}
```

## Acceptance criteria
- [ ] Sharing one photo and then rotating (or any config change) yields exactly one selected URI and one upload.
- [ ] A spent OAuth `code` is not exchanged a second time on recreation; no full-screen `isAuthProcessing`
      spinner appears on rotation.
- [ ] A regression test covers double `handleIntent` for both the share and OAuth paths.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests '*MainViewModelTest'`
