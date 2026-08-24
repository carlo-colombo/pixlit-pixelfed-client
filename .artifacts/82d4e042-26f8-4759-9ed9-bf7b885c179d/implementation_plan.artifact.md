# Implementation Plan - Dynamic App Labels based on Build Environment

This plan updates the build configuration to provide different app labels for `dev` and `prod` variants, while also distinguishing between CI Main builds and Local/PR builds.

## Proposed Changes

### Build Configuration

#### [MODIFY] [app/build.gradle](file:///home/carlo/projects/my-pixelfed/app/build.gradle)
- Add logic to detect if the build is running on CI from the `main` branch.
- Define a `buildSuffix` based on this detection (empty for CI Main, " (Internal)" for others).
- Update `productFlavors` to set `appLabel` via `manifestPlaceholders`.
- The `dev` flavor will have " Dev" in its label.

### Android Manifest

#### [MODIFY] [app/src/main/AndroidManifest.xml](file:///home/carlo/projects/my-pixelfed/app/src/main/AndroidManifest.xml)
- Update `android:label` to use the `${appLabel}` placeholder.

### Resources

#### [MODIFY] [app/src/main/res/values/strings.xml](file:///home/carlo/projects/my-pixelfed/app/src/main/res/values/strings.xml)
- Remove the static `app_name` resource as it will be managed dynamically via build placeholders.

## Verification Plan

### Automated Tests
- Since this is a build configuration change, I will verify the `manifestPlaceholders` logic by inspecting the expected values for different environment variable simulations (mental check).
- I will run `./gradlew :app:assembleDevDebug` and check if it builds successfully.

### Manual Verification
- The user can verify by running the app locally and checking the app name on the launcher (should be "Pixelfed Dev (Internal)").
- When built on CI Main, the label should be "Pixelfed" or "Pixelfed Dev" (depending on flavor).
