# Task 65 — UX-10: Remember recent-post HTML parsing

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [03-upload-flow.md](../../../review/03-upload-flow.md) medium-`RecentPostsSection` re-parses HTML · [05-ux-compose.md](../../../review/05-ux-compose.md) low `RecentPostCard` recomputes HTML parsing

## Problem
`RecentPostCard` parses HTML and compiles a regex in its composable body on every recomposition: `RecentPostsSection.kt:75` `Html.fromHtml(rawText, Html.FROM_HTML_MODE_LEGACY)` and `:79` `Regex("(?i)(?<!\\w)${Regex.escape(tag)}(?!\\w)")`. `:71` also allocates a fresh `statuses.take(visibleRecentPostCount)` list each pass. The card is re-entered whenever the parent list recomposes (pager change, load-more), so scrolling the Recent tab does O(cards) parses per frame.

## Scope
- Wrap the HTML conversion in `remember(rawText) { ... }` inside `RecentPostCard` (`RecentPostsSection.kt:74-76`).
- Wrap the derived `missingTags` in `remember(text, tags) { ... }` so the `Regex(...)` compile at `:79` runs once per card content instead of per recomposition (the tag varies, so a single file-level pattern is not enough on its own).
- Derive `copyText` from the remembered `text`/`missingTags` instead of recomputing.
- Keep the rendered text, tag list and copy behaviour byte-identical.
- Keyed `itemsIndexed` (`:53`) already exists — don't touch it.

```kotlin
val text = remember(rawText) { Html.fromHtml(rawText, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
val missingTags = remember(text, tags) {
    tags.filterNot { tag -> Regex("(?i)(?<![\\w])${Regex.escape(tag)}(?![\\w])").containsMatchIn(text) }
}
```

## Acceptance criteria
- [ ] No `Html.fromHtml` or `Regex(...)` construction runs on recomposition of an unchanged card.
- [ ] The Recent tab renders the same text, missing-tag line and copy payload as before.
- [ ] `./gradlew :app:assembleLocalDevDebug` compiles.

## Dependencies
- None (UX-15 adds a `@Preview` to the same file — land sequentially).

## Verification
- `./gradlew :app:assembleLocalDevDebug`, then scroll the Recent tab and compare against the current build.
