# Task 68 — UX-13: Fix the dev-banner double inset

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) low Dev builds double-apply the status-bar inset

## Problem
`src/dev/DevBanner.kt:22-27` applies `.statusBarsPadding()` to the banner, and `UploadScreen.kt:321` separately applies `Modifier.systemBarsPadding()` to the scaffold. They are siblings in `MainActivity`'s root `Column` (`MainActivity.kt:72-74`), and Compose consumers do not share insets between siblings, so the top status-bar inset is applied twice and the "Upload to Pixlit" header sits one status-bar height too low in `dev` (`localDev`) builds. Prod is unaffected because `src/prod/DevBanner.kt` is `Unit`, which is why the defect survived the flavour split.

## Scope
- Make the top inset single-owner: apply `Modifier.statusBarsPadding()` on the root `Column` in `MainActivity.kt:72-74`.
- Remove `.statusBarsPadding()` from `src/dev/DevBanner.kt:22-27`.
- In `UploadScreen.kt:321`, replace `Modifier.systemBarsPadding()` with bottom-only inset handling (`Modifier.navigationBarsPadding()`) so no top inset is applied a second time; leave IME handling to UX-04.
- Verify the prod (`localProd`/`ciProd`) header position is unchanged, since the banner there contributes no inset.
- Keep the banner's background, padding and text so the strip still renders as before in `localDev`.
- Limit the change to inset ownership: do not restructure `MainActivity`'s `Column` or the banner's content.

## Acceptance criteria
- [ ] In a `localDev` build, "Upload to Pixlit" sits directly below the "DEV BUILD" banner with no extra status-bar gap.
- [ ] In a prod build, the header position matches today.
- [ ] Exactly one place in the tree applies the top status-bar padding.
- [ ] The "DEV BUILD" strip still sits below the status bar and remains fully legible.
- [ ] Dev-vs-prod screenshots differ only by the banner's height, not by an extra inset.
- [ ] `git grep statusBarsPadding src/dev` returns no match.
- [ ] `./gradlew :app:assembleLocalDevDebug :app:assembleLocalProdDebug` compiles.

## Dependencies
- None (UX-04 also edits `UploadScreen.kt:321` — land the two sequentially).

## Verification
- `./gradlew :app:assembleLocalDevDebug :app:assembleLocalProdDebug`, then screenshot both and compare header offsets.
