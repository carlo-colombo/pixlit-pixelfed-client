# Task 59 — UX-04: Fix keyboard and large-font layout

**Wave 4 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) medium-IME · medium-fixed-heights

## Problem
The caption field is forced to `height(240.dp)` and scrolled with a timed hack (`UploadScreen.kt:269-276`, `UploadScreen.kt:486-490`), the bottom action bar has no IME padding (`UploadScreen.kt:618-655`) and the scaffold only applies `Modifier.systemBarsPadding()` (`:321`), which does not cover the keyboard — so the primary "Upload N Photos" button is covered while typing. With the keyboard open the character counters and the "Upload N Photos" label are covered too, so the user must dismiss the keyboard to submit. The same fixed-size pattern clips at large font scale: `RecentPostsSection.kt:84` (`height(420.dp)`) with `maxLines = 3` (`:118-122`), and `ImagePagerSection.kt:37` (`height(260.dp)`).

## Scope
- Give the scaffold `contentWindowInsets = WindowInsets.safeDrawing` (or apply `Modifier.imePadding()` to the bottom `Surface`) so the action bar rides above the keyboard.
- Replace the caption field's `.height(240.dp)` (`UploadScreen.kt:487`) with `heightIn(min = 120.dp)`.
- Delete the `delay(100)` + `bringIntoView()` focus hack (`UploadScreen.kt:269-276`); rely on the IME-safe layout instead.
- `RecentPostsSection.kt:84`: drop the fixed `420.dp` card height in favour of intrinsic/`heightIn` sizing and let the text flow (`:118-122`).
- Keep the pager's image area fixed (`ImagePagerSection.kt:37` is acceptable) but let its container wrap content.
- Check the result against a gesture-nav device so the bar clears IME and nav insets together, not one at a time.
- Ship no preview-only change here: IME behaviour depends on the platform, so this must be verified on a device or emulator.

## Acceptance criteria
- [ ] With the keyboard open, the Upload button and the character counter are visible and tappable.
- [ ] At 2.0× font scale, the recent-post card and caption field show their text without clipping.
- [ ] No `delay(...)` remains in the caption focus/scroll path.
- [ ] `RecentPostsSection`'s keyed list (`:53`) and the pager's swipe behaviour are unchanged.
- [ ] Removing the caption field's fixed height keeps its `maxLines`/scroll behaviour.
- [ ] `./gradlew :app:assembleLocalDevDebug` compiles.

## Dependencies
- None.

## Verification
- `./gradlew :app:assembleLocalDevDebug`, then manually at default and 2.0× font scale with the keyboard open.
