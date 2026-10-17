# Task 83 — DATA-14: Keep CachedTagSearch under R8

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-24

## Problem
`proguard-rules.pro:21` keeps only `ovh.litapp.pixlit.data.api.**`, while `CachedTagSearch`
(`PixelfedRepository.kt:39-41`) is a **private** class in `data.repository` that is deserialised
reflectively through `TypeToken<Map<String, CachedTagSearch>>` (`:528-534`, `:565-571`) with no
`@SerializedName` on its `names` field. Release builds run R8 (`app/build.gradle:52-56`) and the
file's own comment at `proguard-rules.pro:13` records a previous `TypeToken` release crash. A
renamed/deduped private class makes the cache silently parse to an empty map, so every search
re-fetches.

## Scope
- `proguard-rules.pro`: add `-keep class ovh.litapp.pixlit.data.repository.CachedTagSearch { *; }`
  next to the existing Gson keep block, with a one-line reason comment.
- Alternatively (and preferably once DATA-08 moves the cache to Room) annotate the field with
  `@SerializedName("names")` and drop the keep rule; choose one, not both.
- Add the minified prod variant to the verification path so the reflection is actually exercised.

```proguard
# CachedTagSearch is deserialised reflectively via TypeToken<Map<String, CachedTagSearch>>.
-keep class ovh.litapp.pixlit.data.repository.CachedTagSearch { *; }
```

## Acceptance criteria
- [ ] A release build containing the keep rule (or `@SerializedName`) succeeds.
- [ ] A test or manual step confirms a cached tag-search blob written by one build reads back
      non-empty from the minified variant.
- [ ] `./gradlew :app:assembleCiProdRelease` is green after the rule is added.

## Dependencies
- DATA-08

## Verification
- `./gradlew :app:assembleCiProdRelease`
