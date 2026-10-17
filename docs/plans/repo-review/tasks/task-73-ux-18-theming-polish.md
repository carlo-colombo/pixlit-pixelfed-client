# Task 73 — UX-18: Theming polish notes

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) low Theming/spec-fidelity notes

## Problem
`PixlitTheme` defaults `dynamicColor = true` (`Theme.kt:46-53`), so on API 34+ (minSdk is 34) the `Purple/Pink` palette in `Color.kt` is never used and the brand never shows. `Theme.kt:63-67` sets only `isAppearanceLightStatusBars`, leaving the navigation-bar icon appearance to the default scrim under `enableEdgeToEdge()` (`MainActivity.kt:45`), and `AndroidManifest.xml:22-38` has no `android:enableOnBackInvokedCallback` so a targetSdk-35 build gets no predictive-back animation.

## Scope
- `Theme.kt:46-53`: decide and encode the intent — either default `dynamicColor = false` so the `Color.kt` palette is used, or document Material You as intentional in a KDoc on the parameter. Do not leave it undecided.
- Land the decision in `Theme.kt` with a short KDoc why, so the next theme change does not re-litigate it.
- `Theme.kt:63-67`: add `isAppearanceLightNavigationBars = !darkTheme` alongside the status-bar flag.
- `AndroidManifest.xml:22-38`: add `android:enableOnBackInvokedCallback="true"`.
- The notification small icon (`ArtShowNotifier.kt:24`, adaptive foreground) is also claimed by PLAT-01 — implement it once there; do not duplicate it here.
- Keep `Color.kt`'s `Purple/Pink` palette as-is; the change is which scheme is selected, not the palette values.
- Skip the `Type.kt:9-17` `headlineMedium` note: the review marks it harmless, so it is not part of this change.

## Acceptance criteria
- [ ] The `dynamicColor` default is either flipped to `false` (brand palette visible on API 34+) or explicitly documented as an intentional Material You choice.
- [ ] Navigation-bar icons use the correct light/dark appearance in both modes.
- [ ] Predictive back is opted in on the manifest.
- [ ] Both theme decisions are recorded in code (KDoc), not only in this task file.
- [ ] The `@Preview` in `Theme.kt:72` reflects the chosen scheme.
- [ ] `./gradlew :app:assembleLocalDevDebug` compiles.

## Dependencies
- None (notification-icon item is PLAT-01's).

## Verification
- `./gradlew :app:assembleLocalDevDebug`, then inspect both themes with the gesture nav bar visible.
