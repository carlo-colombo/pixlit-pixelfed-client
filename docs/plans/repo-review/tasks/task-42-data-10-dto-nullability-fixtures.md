# Task 42 — DATA-10: Make DTO nullability explicit with fixtures

**Wave 3 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-18

## Problem
The API contract is unverified. `NetworkModule.kt:25-27` configures Gson with
`Strictness.LENIENT`, so malformed JSON is accepted rather than rejected, and Gson populates
non-null Kotlin collections with `null` (it instantiates via `Unsafe`, bypassing constructor
defaults): `PixelfedApi.kt:194-196` `HashtagSearchResponse.hashtags` and `BlueskyApi.kt:16`
`BlueskyPosts.posts`. Consumers survive only by accident (`?.`/`orEmpty()`), and there is no test
that parses a recorded server payload. Non-null assertions on bodies (`PixelfedRepository.kt:87, 130,
271, 380`, `response.body()!!`) sit one refactor away from an NPE.

## Scope
- Make the nullable boundary explicit: `PixelfedApi.kt:194-196` and `BlueskyApi.kt:16` become
  `List<…>? = null`, with consumers handling null deliberately.
- Replace the `response.body()!!` sites (`PixelfedRepository.kt:87, 130, 271, 380`) with explicit
  null handling that maps to a typed failure.
- Add `app/src/test/resources/fixtures/{verify_credentials,statuses,media,search_v2,places_search,collections}.json`.
- Add parsing tests using the injected `Gson`, including a missing-collection and a malformed-body case.

```kotlin
data class HashtagSearchResponse(@SerializedName("hashtags") val hashtags: List<TagItem>? = null)
data class BlueskyPosts(val posts: List<BlueskyPost>? = null)
```

## Acceptance criteria
- [ ] Each fixture parses into its DTO, asserting the fields the repository reads.
- [ ] A response omitting a collection yields an empty/typed-failure result, never an NPE.
- [ ] No `response.body()!!` remains in the repository.

## Dependencies
- TEST-01 (MockWebServer + `room-testing` and the repository seams land first)

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest`
