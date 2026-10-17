# Task 02 — REL-02: `-Pandroid.versionName` is a no-op; every released APK claims `1.0.0` / `versionCode 1`

**Wave 0 · Priority P0 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #2, [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) critical-1

## Problem
`app/build.gradle:16-17` hardcodes `versionCode 1` / `versionName "1.0.0"` and never reads the property the workflow passes: `build.yml:74` sets `-Pandroid.versionName="$TAG_VERSION"` but an exhaustive search finds no `findProperty("android.versionName")` anywhere. Every signed APK reports `1.0.0`, so the release asset name (`pixelfed-client-<tag>.apk`) and the binary's own metadata disagree, blocking any version-driven updater.

## Scope
- `app/build.gradle` `defaultConfig`: `versionName = (findProperty("android.versionName") ?: "1.0.0").toString()`.
- `app/build.gradle` `defaultConfig`: read `versionCode` from `findProperty("android.versionCode")` with a `1` fallback.
- `.github/workflows/build.yml`: derive `VERSION_CODE` from the tag (`1.2.3-build.4` → `100020003` via `sed`/`awk`, suffix stripped) and pass `-Pandroid.versionCode="$VERSION_CODE"` to the assemble step.

```groovy
def stampedName = (findProperty("android.versionName") ?: "1.0.0").toString()
def stampedCode = (findProperty("android.versionCode") ?: "1") as int

defaultConfig {
    versionCode stampedCode
    versionName stampedName
    // ...
}
```

## Acceptance criteria
- [ ] `./gradlew :app:assembleCiProdRelease -Pandroid.versionName=9.9.9 -Pandroid.versionCode=90009` yields `versionName=9.9.9`, `versionCode=90009`.
- [ ] Without the properties the build still yields `1.0.0` / `1`.

## Dependencies
- None.

## Verification
- `./gradlew :app:assembleCiProdRelease -Pandroid.versionName=9.9.9 && aapt dump badging app/build/outputs/apk/ci/prod/release/*.apk | grep versionName`
