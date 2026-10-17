# Task 63 — UX-08: Add a night theme to stop the white flash

**Wave 4 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [05-ux-compose.md](../../../review/05-ux-compose.md) medium-night

## Problem
`app/src/main/res/values/themes.xml:3` is `<style name="Theme.PixelfedClient" parent="android:Theme.Material.Light.NoActionBar" />` and there is no `values-night/themes.xml` in the tree. With `enableEdgeToEdge()` (`MainActivity.kt:45`), dark-mode users see a light window background/splash before Compose draws, and the status/nav bar transition is visible. Compose itself follows the system (`Theme.kt:47`).

## Scope
- Add `app/src/main/res/values-night/themes.xml` with the same style name parented from the dark platform theme (`android:Theme.Material.NoActionBar`), keeping the app dependency set unchanged.
- Do **not** adopt `Theme.Material3.DayNight.NoActionBar` as the review suggests: `com.google.android.material` is not on the classpath (`gradle/libs.versions.toml` has no `material` library) and adding it is a separate decision.
- Set an explicit `android:windowBackground` in both files so the pre-Compose window matches the Compose background instead of flashing.
- Leave the splash-screen (`core-splashscreen`) alternative out of scope.

```xml
<!-- app/src/main/res/values-night/themes.xml -->
<resources>
    <style name="Theme.PixelfedClient" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@android:color/background_dark</item>
    </style>
</resources>
```

## Acceptance criteria
- [ ] Cold start in system dark mode shows no light flash before the first Compose frame.
- [ ] The manifest's `android:theme="@style/Theme.PixelfedClient"` still resolves in both configurations.
- [ ] No new dependency is added.
- [ ] `./gradlew :app:assembleLocalDevDebug` compiles and packages `values-night`.

## Dependencies
- None.

## Verification
- `./gradlew :app:assembleLocalDevDebug`, install, and cold-start with dark mode enabled.
