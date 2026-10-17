# Task 69 — UX-14: Measure the hashtag popup position

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) low Hashtag popup is positioned from an assumed 8 dp glyph width

## Problem
The suggestion popup's x position is computed from an assumed monospace glyph width: `UploadScreen.kt:461-469` does `(16.dp.toPx() + lineCharacterCount * 8.dp.toPx()).toInt()` and feeds it to `CaptionSuggestionPositionProvider` (`UploadScreen.kt:59-79`). For proportional fonts, wide characters, indentation or a large font scale the popup detaches from the caret; because the provider coerces the anchor into the field bounds (`UploadScreen.kt:72`), the failure is visual rather than a crash.

## Scope
- Replace the magic `8.dp` character-width estimate (`UploadScreen.kt:461-469`) with a measured position: capture the `TextLayoutResult` from the caption field's `onTextLayout` and use `getHorizontalPosition(caretOffset)` (plus the field's horizontal padding) for the anchor.
- Keep the `CaptionSuggestionPositionProvider` contract (`UploadScreen.kt:59-79`) intact — it should receive a real pixel anchor, not a recomputed one; `:497` already passes `cursorX` into it.
- Recompute the anchor only when the layout or the caret moves (`derivedStateOf`/`remember`), so typing does not add per-character work.
- Keep the popup's width/coercion logic (`UploadScreen.kt:72`) untouched.
- If measurement is unavailable before the first layout, fall back to anchoring the popup to the field's leading edge rather than to a guessed offset.
- Do this after/with REF-01, which moves the caption field into `CaptionField.kt`; the anchor must live with the field, not the tab host.

## Acceptance criteria
- [ ] The popup's leading edge tracks the caret for non-monospace text, wide characters and 1.5× font scale.
- [ ] No hardcoded per-character width remains.
- [ ] When layout has not happened yet, the popup is anchored to the field edge and never mis-positioned.
- [ ] The anchor does not re-trigger the tab host's recomposition on each keystroke.
- [ ] No per-character-width constant remains in `UploadScreen.kt`/`CaptionField.kt`.
- [ ] At 2.0× font scale the popup still anchors to the caret.
- [ ] `./gradlew :app:assembleLocalDevDebug` compiles.

## Dependencies
- REF-01 (the caption field is extracted into `CaptionField.kt` first).

## Verification
- `./gradlew :app:assembleLocalDevDebug`, then type `#` mid-caption after wide characters and at large font scale.
