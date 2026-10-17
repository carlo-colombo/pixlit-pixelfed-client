# Task 18 — DATA-01: Parse the status asset off the main thread

**Wave 1 · Priority P1 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-03 · [06-images-db-background.md](../../../review/06-images-db-background.md) medium (asset on main thread)

## Problem
`UploadViewModel.kt:58` initialises `_topTags = MutableStateFlow(repository.getDefaultStaticTagCounts())`
as a property initializer, so it runs synchronously on the main thread during construction. That calls
`PixelfedRepository.getStaticStatuses()` (`:286-306`), which reads the ≈1 MB / 24 128-line
`app/src/main/assets/pixelfed-statuses.json`, parses it with a fresh `Gson()` (`:300`) and regex-scans it —
repeated at `UploadViewModel.kt:345` and `:354` on the API-failure fallback path.

## Scope
- `PixelfedRepository.kt`: make `getStaticStatuses()` a lazy, memoized parse using the injected `Gson` with
  a hoisted `TypeToken`; make `getDefaultStaticTagCounts()` `suspend` and run under `Dispatchers.Default`.
- `UploadViewModel.kt`: initialise `_topTags` to `emptyList()` and fill it from the `init` fetch
  (`:123`) or a `viewModelScope.launch`, updating the fallback call sites (`:345`, `:354`).
- Add a unit test asserting the asset is parsed once and that tag extraction does not run on the main thread.

## Acceptance criteria
- [ ] Navigating to Upload performs no asset read/JSON parse on the main thread.
- [ ] The asset is read and parsed at most once per process; a single injected `Gson` is reused.
- [ ] The fallback path (`:345`, `:354`) uses the off-main, memoized load.
- [ ] The Upload screen renders immediately without waiting on the asset parse.

## Dependencies
- None

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest --tests '*PixelfedRepositoryTest'`
