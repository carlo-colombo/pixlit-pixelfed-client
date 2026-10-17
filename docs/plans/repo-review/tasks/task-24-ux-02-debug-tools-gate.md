# Task 24 — UX-02: Gate debug tools out of prod

**Wave 1 · Priority P1 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) "Shipping internals: Debug tab and Simulate Art Show Notification ship in prod"

## Problem
The tab list `listOf("Upload", "Social", "Last", "Debug", "Settings")` (`UploadScreen.kt:259`), the Debug tab body (`UploadScreen.kt:684-786`) and its "Simulate Art Show Notification" button (`:736-741`) plus the `onSimulateNotification` callback (`MainActivity.kt:97`) all ship in the prod APK. Only `DevBanner` is flavor-gated (`src/dev/DevBanner.kt` vs `src/prod/DevBanner.kt`), and the cache counts the Debug tab exposes (`UploadScreen.kt:713-724`) leak internal state (05-ux-compose.md).

## Scope
- `app/build.gradle`: enable `buildConfig = true` in `buildFeatures` (currently `compose` only) and add `buildConfigField "boolean", "DEBUG_TOOLS", "true"` for `dev` / `"false"` for `prod` (and the matching value for `local` / `ci`).
- `ui/upload/UploadScreen.kt`: build `tabs` from `BuildConfig.DEBUG_TOOLS`, so the Debug entry and its `selectedTab == 3` branch do not exist in prod.
- `MainActivity.kt:97`: pass `onSimulateNotification` only when debug tools are enabled.
- Confirm the prod release flavor assembles and shrinks with the debug branch removed.

```gradle
buildFeatures {
    compose = true
    buildConfig = true
}
```

## Acceptance criteria
- [ ] No "Debug" tab and no "Simulate Art Show Notification" in a prod APK.
- [ ] Dev/local builds still expose both.
- [ ] `:app:assembleCiProdRelease` builds and R8 removes the dead debug branch.
- [ ] Tab indices stay consistent in both variants (Settings reachable in prod).

## Dependencies
- None

## Verification
- `./gradlew :app:assembleCiProdRelease`
