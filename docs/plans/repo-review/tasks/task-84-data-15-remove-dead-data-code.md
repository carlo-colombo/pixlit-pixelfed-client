# Task 84 — DATA-15: Remove dead data-layer code

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-21

## Problem
Several declarations have no reference outside their own definition: `RegisterAppResponse`
(`PixelfedApi.kt:128-135`) and `TokenResponse` (`:137-144`), `StatusDao.getStatusesFlow` (`:12`) and
`clearStatuses` (`:21`), `PixelfedRepository.getUserTopTags` (`:521-523`), and
`TokenManager.cachedTagsJson` / `tagsCacheTime` (`:23-34`). Unused imports `GsonBuilder`
(`PixelfedRepository.kt:17`) and `HttpLoggingInterceptor` (`:28`) remain too. `getStatusesFlow`
existing but unused is the worst of these: it implies the repository exposes reactive cached reads
when every screen re-triggers imperative fetches (F-07/F-08).

## Scope
- `data/api/PixelfedApi.kt`: delete `RegisterAppResponse` and `TokenResponse` (parsing is manual at
  `PixelfedRepository.kt:601-661`).
- `data/db/StatusDao.kt`: delete `clearStatuses` and `getStatusesFlow` **unless** REL-12 wires the
  logout purge / reactive cache; in that case keep and wire them instead of deleting.
- `PixelfedRepository.kt`: delete `getUserTopTags` and the unused `GsonBuilder` /
  `HttpLoggingInterceptor` imports; `TokenManager.kt`: delete `cachedTagsJson`/`tagsCacheTime` once
  DATA-08 has moved the tag cache.
- Mark `DatabaseModule.provideStatusDao` `@Singleton` to match the database scope (same finding).

## Acceptance criteria
- [ ] `grep -rn 'RegisterAppResponse\|TokenResponse\|getUserTopTags\|cachedTagsJson\|tagsCacheTime' app/src/main` returns nothing.
- [ ] Deleted DAO members are either removed or demonstrably wired to a caller.
- [ ] `./gradlew :app:assembleLocalDevDebug :app:testLocalDevDebugUnitTest` passes.

## Dependencies
- REL-12

## Verification
- `./gradlew :app:assembleLocalDevDebug :app:testLocalDevDebugUnitTest`
