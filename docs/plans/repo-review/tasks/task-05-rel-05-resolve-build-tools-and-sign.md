# Task 05 — REL-05: Signing depends on an unmaintained action + hardcoded build-tools `34.0.0` while `compileSdk 37`

**Wave 0 · Priority P1 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #6, [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) high-3

## Problem
`build.yml:77-86` signs with `r0adkll/sign-android-release` and `env: BUILD_TOOLS_VERSION: "34.0.0"`, while `app/build.gradle:10` compiles against `compileSdk 37`. Signing therefore depends on a build-tools line this repo does not control — the blueprint documents a real release failure from exactly this — and the action is unmaintained (it emits the deprecated `::set-output`). Combined with REL-01, that failure lands after the tag already exists.

## Scope
- `.github/workflows/build.yml`: replace the action with explicit `zipalign` + `apksigner sign` + `apksigner verify` steps.
- Add a "Resolve build-tools version from the SDK" step that picks the highest installed build-tools directory containing `apksigner` and exposes it as an output.
- Keep SHA-pin discipline for any remaining third-party action.

```yaml
- name: Resolve build-tools version from the SDK
  id: build_tools
  run: |
    set -euo pipefail
    sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    version=""
    while IFS= read -r candidate; do
      if [ -x "$sdk_root/build-tools/$candidate/apksigner" ]; then version="$candidate"; break; fi
    done < <(find "$sdk_root/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -Vr)
    [ -n "$version" ] || { echo "::error::no build-tools with apksigner"; exit 1; }
    echo "version=$version" >> "$GITHUB_OUTPUT"
```

## Acceptance criteria
- [ ] Signing succeeds on the runner image without build-tools `34.0.0` installed.
- [ ] `apksigner verify` passes on the produced APK.
- [ ] No reference to `r0adkll/sign-android-release` remains in the workflow.

## Dependencies
- None.

## Verification
- `apksigner verify --verbose app/build/outputs/apk/ci/prod/release/*.apk`
