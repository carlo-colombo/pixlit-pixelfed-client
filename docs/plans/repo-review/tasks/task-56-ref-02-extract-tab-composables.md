# Task 56 — REF-02: Extract tab content composables

**Wave 4 · Priority P2 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [03-upload-flow.md](../../../review/03-upload-flow.md) architecture-seams (screen split) · [05-ux-compose.md](../../../review/05-ux-compose.md) medium-`UploadContent` is a ~600-line composable

## Problem
`UploadContent` is a single ~600-line composable (`UploadScreen.kt:255-790`) that owns the tab row and inlines all five bodies: Upload (`:330-660`), Social (`:661-671`), Recent (`:672-683`), Debug (`:684-786`) and Settings (`:787`), plus two private helpers at `:790-812`. Any tab change recomposes from one root, and no tab can be previewed or tested on its own. The bodies are selected by an inline `selectedTab == N` chain, which is also why a renamed or added tab risks shifting a branch (`:684`, `:793`).

## Scope
- Extract `ui/upload/UploadTabContent.kt` (tab 0, `UploadScreen.kt:327-654`) and `ui/upload/CaptionField.kt` (`:465-545`) into their own files taking only the state/callbacks they use.
- Extract `RecentPostsTabContent` (`:672-683`) and `SettingsTabContent` (`:793-812`).
- Extract `ui/debug/DebugTab.kt` (`:676-787`) and keep it reachable only when `BuildConfig.DEBUG_TOOLS` is true (UX-02 gates the tab entry).
- Move `SocialScreen` out of the upload feature: `ui/social/SocialScreen.kt:655-660` call site stays in the tab host, the composable does not belong to `ui/upload`.
- Leave `UploadContent` as the scaffold + `PrimaryScrollableTabRow` host only; `selectedTab` state and the tab row stay there.
- Migration is mechanical: bodies move verbatim, and any bug spotted while moving is filed against UP-13 rather than fixed here.
- Each extracted body must be stateless (state + callbacks only) so UX-15 can preview it without the ViewModel.

## Acceptance criteria
- [ ] Each extracted composable takes only its own state/callbacks (no 40-parameter pass-through).
- [ ] Tab row, tab order and per-tab rendering are unchanged in a `localDev` build.
- [ ] Each extracted tab body can be `@Preview`-ed independently.
- [ ] `UploadScreen.kt` shrinks to the scaffold + tab host, with no tab body left inlined.
- [ ] The moved ranges contain no new logic (diff against the original `:327-790` ranges while reviewing).
- [ ] `./gradlew :app:assembleLocalDevDebug` compiles with no unused-parameter warnings introduced.

## Dependencies
- UX-02 (Debug tab must be gated before it moves into its own file).

## Verification
- `./gradlew :app:assembleLocalDevDebug` then open the app and switch through all tabs.
