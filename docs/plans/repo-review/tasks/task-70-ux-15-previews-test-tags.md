# Task 70 — UX-15: Add previews and test tags

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) low Preview coverage is uneven

## Problem
`@Preview` exists only in `Theme.kt:72`, `LoginScreen.kt:200/208`, `ImagePagerSection.kt:87/100`, `MetadataDisplay.kt:70/90`, `OrderPreviewList.kt:85`, `TagSelectionCloud.kt:77/97` and `UploadScreen.kt:821`. Nothing exists for `SocialScreen`, `RecentPostsSection`, `CollectionSelectionSection`, `PositionSelectionSection`, `MetadataDisplay`'s empty state, the non-Upload `UploadContent` tabs, `SettingsContent`, or `MainActivity`'s auth-processing/logged-out states. There is no `testTag` and no `Modifier.semantics { testTagsAsResourceId = true }` anywhere, so UI automation cannot address stable nodes.

## Scope
- Add `@Preview` composables for `SocialScreen`, `RecentPostsSection`, `CollectionSelectionSection`, `PositionSelectionSection`, the `MetadataDisplay` empty state and `SettingsContent`; they are already stateless per the review.
- Add `@PreviewParameter` sample providers for recent statuses, collections and place-search results, following the pattern in `neurhome` (`ui/components/Calendar.kt:53`).
- Set `Modifier.semantics { testTagsAsResourceId = true }` on the root container in `MainActivity.kt` and add a root `testTag` so Espresso/UIAutomator can find the tree.
- Keep the previews stateless and data-driven; no new dependency is needed (`ui-tooling-preview` is already a dependency, `app/build.gradle:110`).
- No user-visible behaviour change; this is tooling-only.
- Previews must not need a `Context`, ViewModel or Hilt: keep them driven by plain parameters.
- Keep the sample providers next to the composables they feed; no shared fixtures module.

## Acceptance criteria
- [ ] Every component named above renders a preview in Android Studio with representative data, including the empty state.
- [ ] The root container exposes `testTagsAsResourceId = true`.
- [ ] No new library is added for previews or test tags.
- [ ] The additions are annotation-only plus the root semantics block; no production code path changes.
- [ ] `./gradlew :app:lintLocalDevDebug` reports no new unused-resource or preview errors.
- [ ] `./gradlew :app:assembleLocalDevDebug` compiles with previews enabled.

## Dependencies
- None (UX-10 and REF-02 touch the same files — land sequentially).

## Verification
- `./gradlew :app:assembleLocalDevDebug`, then confirm each preview renders in the IDE preview pane.
