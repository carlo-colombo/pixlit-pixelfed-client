# Task 57 — REF-03: Repository seams (alias of DATA-06)

**Wave 4 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-12

## Problem
REF-03 has no work of its own: the backlog row points at DATA-06 and exists only so the ID is reserved. The defect it tracks is the one described in F-12 — `PixelfedRepository.kt` is 740 lines with 14 responsibilities, from OAuth app registration (`:65-162`) to tag aggregation as a companion-object utility (`:595-720`), and it rebuilds a `Retrofit` per request (`:55-62`, 8 call sites, F-15). No single concern can be changed or tested without touching auth, caching, parsing and upload at once, and nothing in it is testable without Robolectric plus a real `Context` (F-19).

## Scope
- Implement nothing separately: DATA-06 owns the six ordered seams and their order (see F-12's table).
- When DATA-06 lands, close REF-03 as a duplicate pointing at DATA-06's merge, with no code delta of its own.
- Do not renumber or re-scope: `data/tags/TagAggregator.kt`, `data/auth/OAuthParsers.kt`, `data/session/SessionRepository.kt`, `data/media/MediaUploader.kt`, `data/status/StatusRemoteSource.kt` + `StatusCache`, `data/api/PixelfedApiFactory.kt` are DATA-06's deliverables.
- If the two tasks are ever scheduled in the same wave, DATA-06 is the only writer to `PixelfedRepository.kt`.
- Record the duplicate resolution in the close-out note with a link to the F-12 table, so the ID's emptiness is traceable.
- Do not split the repository here: if DATA-06 slips a wave, REF-03 stays open rather than landing a partial seam.
- If DATA-06 is rejected in review, reopen REF-03 against the same F-12 evidence instead of re-deriving the seams.

## Acceptance criteria
- [ ] DATA-06's seams are extracted in the order given in `04-data-network.md` F-12 (pure helpers first, `PixelfedApiFactory` last).
- [ ] This file records the duplicate resolution (same deliverable as DATA-06) and adds no code of its own.
- [ ] No file is modified by both DATA-06 and REF-03 in the same wave.
- [ ] After DATA-06 lands, no per-request `Retrofit`/Gson converter/API proxy construction remains in `PixelfedRepository.kt`.
- [ ] Closing REF-03 adds no diff beyond DATA-06's commits.
- [ ] `grep -n 'getRetrofit(' PixelfedRepository.kt` returns nothing after DATA-06.

## Dependencies
- DATA-06 (this item is its alias).

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest` (run against the DATA-06 branch, not a separate REF-03 change).
