I have all the evidence I need. Here is the completed artifact.

---

# CI/CD, Release & Repo Automation Gap Analysis — `my-pixelfed` vs. `neurhome` blueprint

**Scope:** `/home/carlo/projects/my-pixelfed/.github/workflows/build.yml` (only workflow), root Gradle files, app build config, `.gitignore`; benchmarked against `/home/carlo/projects/neurhome/{.github/workflows/build.yml,.github/workflows/pr.yml,.github/actions/setup-jdk-gradle/action.yml,.github/dependabot.yml,gradle/gradle-daemon-jvm.properties,AGENTS.md}`.
**Method:** read-only source inspection; every finding cites `file:line`. No CI was executed.

## What's already fine
- `concurrency` + `cancel-in-progress` and `on: pull_request/main` coverage exist (`build.yml:6-16`).
- The unmaintained `r0adkll/sign-android-release` action **is** SHA-pinned (`build.yml:79`), and the tag push is correctly gated to `push`+`main` so PR runs don't tag (`build.yml:31`).
- Release step is gated to `push`+`main` (`build.yml:107`), so PRs never publish.
- Key store material is never written to the working tree by repo code, and `*.jks`/`*.keystore` are git-ignored (`.gitignore:27-28`).

---

## Findings

### [SEVERITY: critical] 1. The tag is pushed *before* the APK is assembled/signed/released → orphan tags
**Evidence**
- `build.yml:30-39` — tag is created and pushed first, with no dry run:
  ```yaml
  - name: Bump version and push tag (main only)
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    id: bump_and_tag
    uses: anothrNick/github-tag-action@v1
    env:
      ...
      PRERELEASE: true
      PRERELEASE_SUFFIX: build
      INITIAL_VERSION: 1.0.0
  ```
- Only afterwards come `Assemble APK` (`build.yml:67-75`), `Sign Android release` (`build.yml:77-86`), `Prepare Signed APK artifact` (`build.yml:88-98`), `Upload` (`build.yml:100-104`) and `Release (Main only)` (`build.yml:106-110`).
- `cancel-in-progress: true` on the main ref (`build.yml:14-16` → `group: ci-${{ github.ref }}`) can cancel a running release *after* the tag exists.

**Impact:** Any failure between the tag push and the GitHub release — R8/lintVital failure in `assembleCiProdRelease`, missing signing secret, upload failure, or a cancelling second push to `main` — leaves a **pushed tag with no release**. Neurhome's comments document this exact failure as having happened. A tag pointing at a commit with no artifact, no fewer recoverable than a renamed/retried release.

**Recommendation:** Copy the blueprint's two-phase approach: compute the tag with `DRY_RUN: true` and stamp the build with it, push it only after the prod variant validates, using `CUSTOM_TAG` to pin tag == built version.
```yaml
- name: Resolve release version
  id: version
  uses: anothrNick/github-tag-action@4ed44965e0db8dab2b466a16da04aec3cc312fd8 # 1.75.0
  env:
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
    WITH_V: false
    PRERELEASE: true
    PRERELEASE_SUFFIX: build
    INITIAL_VERSION: 1.0.0
    DRY_RUN: true          # <-- dry run first
```

**Blueprint reference:** `neurhome/.github/workflows/build.yml:26-51` (DRY_RUN `id: version`) and `:60-72` (`CUSTOM_TAG: ${{ steps.version.outputs.new_tag }}`), with the explicit rationale comment "a failure anywhere between a pushed tag and the GitHub release leaves an orphan tag with no release - which is what a past release run did."

---

### [SEVERITY: critical] 2. `-Pandroid.versionName` is a no-op — the APK's versionName never matches the published tag
**Evidence**
- `build.yml:73-75` passes the property:
  ```yaml
  ./gradlew \
    -Pandroid.versionName="$TAG_VERSION" \
    "${ASSEMBLE_TASK}"
  ```
- But `app/build.gradle:17` hardcodes the value and never reads the property:
  ```groovy
  versionCode 1
  versionName "1.0.0"
  ```
  A repo-wide search for `android.versionName` matches **only** the workflow (`build.yml:74`); nothing in `app/build.gradle` or elsewhere consumes it.
- The blueprint wires it: `neurhome/app/build.gradle.kts:73` → `versionName = findProperty("android.versionName").toString()`.

**Impact:** Every signed APK reports `versionName = "1.0.0"` regardless of the tag (`1.0.1-build.3`, …). The GitHub release is named `pixelfed-client-<tag>.apk` but the artifact inside says `1.0.0`. The version the workflow clearly *intends* to stamp never reaches the APK, so installed builds are indistinguishable and release↔artifact provenance is broken. (`versionCode 1` is likewise never bumped; the blueprint also pins it statically at `300`, so that alone is not a regression.)

**Recommendation:** Read the property in `app/build.gradle` so the flag actually takes effect:
```groovy
defaultConfig {
    versionCode 1
    versionName = project.findProperty("android.versionName") ?: "1.0.0"
}
```
and verify with `aapt dump badging` / `apkanalyzer manifest version-name` in CI.

**Blueprint reference:** `neurhome/app/build.gradle.kts:73`.

---

### [SEVERITY: critical] 3. CI runs **no** tests and **no** lint, and never validates the prod variant before signing
**Evidence**
- The full step list of `build.yml` is: checkout (`:25`), tag (`:30`), setup JDK (`:42`), setup Gradle (`:48`), `Set Build Config` (`:50-65`), `Assemble APK` (`:67-75`), `Sign Android release` (`:77-86`), `Prepare Signed APK artifact` (`:88-98`), upload (`:100`), release (`:106`). There is **no** `test*UnitTest` and **no** `lint*` invocation anywhere.
- `README.md` advertises `./gradlew testLocalDevDebugUnitTest`, but no CI step runs it, so the described test suite is never a gate.
- The blueprint explicitly gates this: `neurhome/.github/workflows/build.yml` step "Validate prod variant before signing" runs `:app:testProdDebugUnitTest :app:lintProdRelease`, and its `AGENTS.md` records: *"a release run has already failed at the prod-variant validation step while the dev suite was green, which is why that step exists."*

**Impact:** A prod-flavor/unit/lint breakage flows straight into the **signed release** (and, per Finding 1, after the tag has already been pushed). This is the documented real-world failure mode the blueprint guards against.

**Recommendation:** Insert a validation step **before** the tag push, and add PR coverage. For this repo the prod variant is `ciProd`, so the tasks are `:app:testCiProdDebugUnitTest` and `:app:lintCiProdRelease` (AGP creates unit-test tasks only for `debug`, hence `...DebugUnitTest`):
```yaml
- name: Validate prod variant before signing
  run: |
    ./gradlew \
      -Pandroid.versionName="${{ steps.version.outputs.new_tag }}" \
      :app:testCiProdDebugUnitTest \
      :app:lintCiProdRelease
```
For PRs, fold assemble+test+lint into one invocation (see Finding 12).

**Blueprint reference:** `neurhome/.github/workflows/build.yml` ("Validate prod variant before signing"); `neurhome/.github/workflows/pr.yml` ("Assemble, test and lint" — single `--parallel` invocation).

---

### [SEVERITY: high] 4. Actions pinned to mutable version tags (supply-chain exposure)
**Evidence** — every third-party action uses a moving tag:
- `build.yml:25` `actions/checkout@v4`
- `build.yml:33` `anothrNick/github-tag-action@v1`
- `build.yml:42` `actions/setup-java@v5`
- `build.yml:48` `gradle/actions/setup-gradle@v6`
- `build.yml:101` `actions/upload-artifact@v4`
- `build.yml:108` `softprops/action-gh-release@v2`

The blueprint SHA-pins **every** action (`neurhome/.github/workflows/build.yml` `@3d3c42e5…# v7`, `@4ed44965…# 1.75.0`, `neurhome/.github/actions/setup-jdk-gradle/action.yml` `@b843ebf9…# v6`, `@cf430e030…# v7`, `@efb35369…# v3`).

**Impact:** A compromised or force-moved tag executes arbitrary code in a job that holds `secrets.SIGNING_KEY`, `secrets.KEY_STORE_PASSWORD` and `contents: write`. The only SHA-pinned action (`r0adkll`, `build.yml:79`) is the one that is otherwise deprecated — the maintained ones are the least protected.

**Recommendation:** Pin all actions to full commit SHAs with a trailing `# vX` comment, and add `.github/dependabot.yml` (Finding 10) so the pins stay current.

**Blueprint reference:** `neurhome/.github/workflows/build.yml`, `neurhome/.github/actions/setup-jdk-gradle/action.yml`.

---

### [SEVERITY: high] 5. `contents: write` is granted to a job that also runs on `pull_request`
**Evidence**
- `build.yml:17-22` — job-level write permission with no top-level default:
  ```yaml
  jobs:
    build:
      runs-on: ubuntu-latest
      permissions:
        contents: write
  ```
- The same job serves PRs (`build.yml:9-11` `on.pull_request.branches: [main]`).
- The blueprint sets a least-privilege default and opts in only in the push-only job: `neurhome/.github/workflows/build.yml:4-6` `permissions: contents: read`, with the comment "Least privilege by default; the build/release job opts into contents: write below". Its `pr.yml:3-5` also defaults to `contents: read`.

**Impact:** PR runs (same-realm branches) receive a write-capable token for the whole job, including the third-party signing action. Fork PRs are auto-downgraded by GitHub, but the least-privilege intent is still violated for internal branches.

**Recommendation:** Add a top-level `permissions: contents: read` and grant `contents: write` only to the release path. Since the tag/release steps are already `if: github.event_name == 'push'`, the cleanest fix is a separate `release` job (push-only) that owns `contents: write`, or, minimally, keep the single job but scope write to a push-only prerequisite job. At minimum:
```yaml
permissions:
  contents: read
```
**Blueprint reference:** `neurhome/.github/workflows/build.yml:4-6`; `neurhome/.github/workflows/pr.yml:3-5`.

---

### [SEVERITY: high] 6. `r0adkll/sign-android-release` with hardcoded `BUILD_TOOLS_VERSION: 34.0.0` against `compileSdk 37`; unmaintained + deprecated `::set-output`
**Evidence**
- `build.yml:77-86`:
  ```yaml
  - name: Sign Android release
    uses: r0adkll/sign-android-release@d5f8436b702872ad5cdb1e6e4d5c8b983d25a997
    with:
      ...
    env:
      BUILD_TOOLS_VERSION: "34.0.0"
  ```
- The app compiles against a much newer SDK: `app/build.gradle:10` `compileSdk 37`.
- The blueprint removed this action for precisely this reason (`neurhome/.github/workflows/build.yml`, "Resolve build-tools version from the SDK"): *"It used to be r0adkll/sign-android-release, which defaulted to the literal 34.0.0 … A past run failed because those pinned build-tools were missing from the runner image"*, and the replacement comment notes the action *"emitted the deprecated `::set-output` command on every release run."*

**Impact:** Signing depends on `build-tools;34.0.0` being present on the runner image while the project builds with 37. Besides the documented past hard-failure, `zipalign`/`apksigner` from a 3-year-old build-tools line are used on an SDK-37 APK, and the action is unmaintained (deprecated command output, no vendor fixes).

**Recommendation:** Replace with the blueprint's SDK-resolved signing script — resolve the highest installed build-tools that actually ships `apksigner`, then `zipalign` + `apksigner sign` + `apksigner verify` explicitly:
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
**Blueprint reference:** `neurhome/.github/workflows/build.yml` ("Resolve build-tools version from the SDK" + the `zipalign`/`apksigner` "Sign Android release" steps).

---

### [SEVERITY: high] 7. Signing secrets have silent fallbacks (`|| 'key'`, `|| 'password'`)
**Evidence** — `build.yml:83-84`:
```yaml
alias: ${{ secrets.ALIAS || 'key' }}
keyStorePassword: ${{ secrets.KEY_STORE_PASSWORD || 'password' }}
```
**Impact:** If a secret is unset/renamed, the run does **not** fail fast; it attempts to sign with a guessable default password (`password`) and a guessed alias, producing a confusing failure at best — and normalising "the signing password is literally `password`" at worst. The blueprint passes `ALIAS`/`KEY_STORE_PASSWORD` straight through with no fallback and fails loudly: `apksigner sign … --ks-pass "pass:$KEY_STORE_PASSWORD"` plus an explicit `[ -s "$keystore" ]` decode check (`neurhome/.github/workflows/build.yml`).

**Recommendation:** Drop the `|| …` fallbacks; add an explicit precondition (`[ -n "$ALIAS" ] && [ -n "$KEY_STORE_PASSWORD" ] || { echo "::error::missing signing secret"; exit 1; }`).

**Blueprint reference:** `neurhome/.github/workflows/build.yml` "Sign Android release" script.

---

### [SEVERITY: medium] 8. Room schema export not configured — the committed-schema gate is impossible
**Evidence**
- Room is used with KSP (`app/build.gradle:117-118` `room-runtime`/`room-compiler` + `ksp`), but `app/build.gradle` has **no** `room { schemaDirectory(...) }` block, and a search for `**/schemas/**` finds nothing committed.
- The blueprint configures and gates it: `neurhome/app/build.gradle.kts:18-20` `room { schemaDirectory("$projectDir/schemas") }`, enforced by the `neurhome/.github/workflows/pr.yml` step "Verify Room schema export is committed" (`git status --porcelain -- app/schemas`).

**Impact:** Migration schema JSON is never written to version control, so a version bump can ship with no record of the schema and no ability to write migration tests later — and the blueprint-standard CI check cannot be added until the export is configured.

**Recommendation:** Add the Room Gradle plugin / `schemaDirectory`, commit `app/schemas`, then add the schema-committed step to PR CI.

**Blueprint reference:** `neurhome/app/build.gradle.kts:18-20`; `neurhome/.github/workflows/pr.yml` "Verify Room schema export is committed".

---

### [SEVERITY: medium] 9. No release-artifact checksum
**Evidence:** `build.yml:88-104` renames/copies the APK and uploads it with no hash. The blueprint generates one for both lanes: `neurhome/.github/workflows/build.yml` "Generate release checksum" (`sha256sum … > …apk.sha256`) and `neurhome/.github/workflows/pr.yml` "Generate APK checksum".
**Impact:** A downloaded release APK cannot be integrity-verified, and there's no evidence trail if an artifact is swapped.
**Recommendation:** Add `sha256sum pixelfed-client-<tag>.apk > pixelfed-client-<tag>.apk.sha256` and include the `.sha256` in both the uploaded artifact and `softprops/action-gh-release` `files:`.
**Blueprint reference:** `neurhome/.github/workflows/build.yml` "Generate release checksum".

---

### [SEVERITY: medium] 10. No Dependabot configuration
**Evidence:** `.github/` contains only `workflows/` (no `dependabot.yml`). The blueprint ships one: `neurhome/.github/dependabot.yml` with a `gradle` ecosystem (weekly, `open-pull-requests-limit: 10`, `groups.gradle-minor-patch`) and a `github-actions` ecosystem.
**Impact:** AGP/Kotlin/Compose and — once Finding 4 lands — action SHA pins drift with no automated bump PRs; the blueprint comment notes its own history of updates silently stalling at the default limit.
**Recommendation:**
```yaml
version: 2
updates:
  - package-ecosystem: "gradle"
    directory: "/"
    schedule: { interval: "weekly" }
    open-pull-requests-limit: 10
    groups:
      gradle-minor-patch:
        patterns: ["*"]
        update-types: ["minor", "patch"]
  - package-ecosystem: "github-actions"
    directory: "/"
    schedule: { interval: "weekly" }
    open-pull-requests-limit: 5
```
**Blueprint reference:** `neurhome/.github/dependabot.yml`.

---

### [SEVERITY: medium] 11. Release path is cancellable mid-flight on `main`
**Evidence:** `build.yml:14-16`
```yaml
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
```
Because the tag is pushed early (Finding 1), a second push to `main` cancels the in-progress release after its tag exists. The blueprint keeps the same concurrency but narrows the window by pushing the tag *after* validation (`neurhome/.github/workflows/build.yml`), and its comment acknowledges the remaining risk.
**Impact:** Orphan tags from ordinary "push twice quickly" behaviour, not just failures.
**Recommendation:** Either move the tag push last (Finding 1, which fixes this too) or disable cancellation for the main release lane, e.g. `cancel-in-progress: ${{ github.event_name == 'pull_request' }}`.
**Blueprint reference:** `neurhome/.github/workflows/build.yml:14-18`.

---

### [SEVERITY: medium] 12. No PR workflow: no path-based change detection, no always-on required check, no single-invocation assemble+test+lint
**Evidence:** my-pixelfed has one workflow (`build.yml`) that runs the full signed-release assembly on PRs; there is no change-detection job, no `required-checks` job, and no `workflow_dispatch`. The blueprint provides all of these in `neurhome/.github/workflows/pr.yml`: `changes` (paths-ignore filter at job level), `build`, `instrumented-tests` (non-blocking), `python-tests`, `required-checks` (`if: always()`), and `automerge` (Dependabot).
**Impact:** Docs-only PRs pay for a full release assembly; there is no stably-named check a branch-protection rule could require (the workflow-level filter the blueprint warns about is exactly the trap the `required-checks` job avoids); and tests/lint run in a separate invocation if added naively.
**Recommendation:** Split PR concerns into their own workflow mirroring the blueprint. Minimal shape:
```yaml
- name: Assemble, test and lint
  run: ./gradlew :app:assembleCiDevDebug :app:testCiDevDebugUnitTest :app:lintCiDevDebug --parallel
- name: required-checks
  needs: [changes, build]
  if: always()
  # fail only when build/changes actually failed (see blueprint)
```
**Blueprint reference:** `neurhome/.github/workflows/pr.yml` (whole file).

---

### [SEVERITY: low] 13. JDK/Gradle setup: JDK 17 is not *broken*, but there is no lockstep/daemon pin and no shared composite action
**Evidence**
- `build.yml:42-44` uses `actions/setup-java@v5` with `java-version: 17`, `distribution: temurin`; there is **no** `gradle/gradle-daemon-jvm.properties` in my-pixelfed and **no** `.github/actions/` composite.
- The blueprint pins JETBRAINS 21 in one shared local action (`neurhome/.github/actions/setup-jdk-gradle/action.yml`) explicitly held in lockstep with `gradle/gradle-daemon-jvm.properties` (`toolchainVendor=JETBRAINS`, `toolchainVersion=21`), with the comment: *"otherwise Gradle ignores JAVA_HOME and provisions a second JDK into ~/.gradle/jdks on every run."*
- Build config: `app/build.gradle:55-58` `sourceCompatibility/targetCompatibility = VERSION_17`; Gradle 9.5 wrapper (`gradle/wrapper/gradle-wrapper.properties:3`); `gradle.properties` sets only `-Xmx3072m` (no metaspace cap, no configuration-cache/caching/parallel).

**Assessment (the specific question asked):** AGP 9.3.2 / Kotlin 2.4.10 do **not** force a newer JDK here — AGP 9.x documents JDK 17 as its minimum and Gradle 9.5 requires JDK 17+, so `temurin 17` satisfies the toolchain. The real gaps are: (a) **no daemon-JVM criteria file**, so nothing pins the JVM the Gradle daemon actually uses and local-vs-CI drift is unmanaged (a local JDK 21 in the daemon vs CI's 17 is possible); and (b) **no composite action**, so the JDK/Gradle setup is duplicated per workflow and free to drift as soon as a second workflow is added. `org.gradle.jvmargs=-Xmx3072m` is also below the blueprint's `-Xmx4g -XX:MaxMetaspaceSize=1g` (`neurhome/gradle.properties`), which the blueprint warns must not be reduced (`D8`/Kotlin metaspace OOM).

**Recommendation:** Adopt the blueprint shape for consistency: add `gradle/gradle-daemon-jvm.properties` (`toolchainVendor=JETBRAINS`, `toolchainVersion=21`) **and** switch the shared composite action to `distribution: jetbrains`, `java-version: 21`, added in lockstep. If JDK 17 is deliberately retained, document that decision and add the daemon criteria for 17 instead. Raise `org.gradle.jvmargs` to `-Xmx4g -XX:MaxMetaspaceSize=1g`.
**Blueprint reference:** `neurhome/.github/actions/setup-jdk-gradle/action.yml`; `neurhome/gradle/gradle-daemon-jvm.properties`; `neurhome/AGENTS.md` "JVM policy"; `neurhome/gradle.properties`.

---

### [SEVERITY: low] 14. Secret handling and artifact retention differ from the blueprint's explicit script
**Evidence:** my-pixelfed delegates keystore decode/temp handling entirely to `r0adkll` (`build.yml:77-86`), so the repo cannot control `umask`, the temp path, or cleanup. The blueprint does it in-repo with `umask 077`, `mktemp "${RUNNER_TEMP}/…-keystore.XXXXXX"`, and `trap 'rm -f "$keystore" "$aligned_apk"' EXIT`, and it also strips whitespace before `base64 -d` (`printf '%s' "$SIGNING_KEY" | tr -d '[:space:]' | base64 -d`), guarding a real CR-in-secret failure the Node decoder silently tolerated. my-pixelfed's `upload-artifact@v4` (`build.yml:100-104`) also omits `retention-days`, whereas the blueprint sets 14 (PR) / 90 (release).
**Impact:** Reduced control over secret lifetime hygiene; unbounded default retention on PR artifacts.
**Recommendation:** Adopt the blueprint's explicit signing script (Findings 6/7 already require touching this block) and add `retention-days:` to the upload steps.
**Blueprint reference:** `neurhome/.github/workflows/build.yml` "Sign Android release"; `neurhome/.github/workflows/pr.yml` "Upload APK" (`retention-days: 14`).

---

### [SEVERITY: low] 15. Dead workflow config and inconsistent PR fallback versions
**Evidence**
- `build.yml:3-4` `env: main_project_module: app` is never referenced; `FLAVOR` is written (`build.yml:54,60`) but never read.
- The PR fallback version is computed twice with different values: `build.yml:71` `TAG_VERSION="1.0.0-pr"` vs `build.yml:92` `TAG_VERSION="1.0.0-pr-${{ github.run_number }}"`.
**Impact:** Confusing/dead config; the name the APK is stamped with (once Finding 2 is fixed) and the name it is uploaded under would disagree on PRs.
**Recommendation:** Delete `main_project_module` and `FLAVOR`; derive the PR fallback once (e.g. an output from `Set Build Config`) and reuse it.
**Blueprint reference:** `neurhome/.github/workflows/build.yml` keeps only used env and derives versions in explicit `id:` steps.

---

### [SEVERITY: low] 16. `.gitignore` missing `.kotlin/` (and repo-root `.artifacts/`)
**Evidence:** root listing shows `.kotlin/` and `.artifacts/` directories present; `.gitignore` ignores `.gradle/` (`:2`) and `build/` (`:3`) but not `.kotlin/`. The blueprint ignores `/.kotlin/` (`neurhome/.gitignore`) and `/.build/`.
**Impact:** Kotlin build caches can be accidentally staged/committed.
**Recommendation:** Add `/.kotlin/` (and, if it is a generated artifact dir, `/.artifacts/`) to `.gitignore`.
**Blueprint reference:** `neurhome/.gitignore`.

---

## Ordered migration plan

### (a) Correctness / bugs — do first (each blocks a trustworthy release)
1. **Wire `versionName` to the property** — `app/build.gradle:17` → `versionName = project.findProperty("android.versionName") ?: "1.0.0"` (Finding 2).
2. **Reorder release: DRY_RUN version → build → validate → CUSTOM_TAG → sign → release** — port `neurhome/.github/workflows/build.yml:26-72` verbatim, renaming `prod`→`ciProd` and confirming the task names `:app:testCiProdDebugUnitTest` / `:app:lintCiProdRelease` (Findings 1, 3, 11).
3. **Add the prod-variant validation step** before the tag push (Finding 3).
4. **Add tests+lint to PR CI in a single `--parallel` invocation** (Finding 12).
5. **Make the PR fallback version single-sourced** and remove `FLAVOR`/`main_project_module` (Finding 15).

### (b) Security
6. **SHA-pin every action** (`build.yml:25,33,42,48,101,108`) and add the `github-actions` Dependabot ecosystem to keep them fresh (Findings 4, 10).
7. **Least-privilege permissions** — top-level `contents: read`; `contents: write` only on the push-only path (Finding 5).
8. **Replace `r0adkll` with the SDK-resolved `zipalign`/`apksigner` script**; drop `|| 'key'` / `|| 'password'` fallbacks; adopt `umask 077` + `mktemp` + `trap` cleanup (Findings 6, 7, 14).

### (c) Quality gates
9. **Configure Room `schemaDirectory`, commit `app/schemas`, add the schema-committed check** (Finding 8).
10. **Add release/PR checksums** and include `.sha256` in the release (Finding 9).
11. **Add the `changes` path filter + always-on `required-checks` job**; add `workflow_dispatch`; optionally the non-blocking emulator job (Finding 12).
12. **Adopt the shared composite JDK/Gradle action + `gradle-daemon-jvm.properties`** and raise `org.gradle.jvmargs` (Finding 13).

### (d) Nice-to-have
13. `retention-days` on upload steps; make the artifact name lane-specific (Findings 14, 12).
14. Surface `BuildConfig.VERSION_NAME` in an About/debug screen so the stamped version is user-visible.
15. Add `.kotlin/` to `.gitignore` (Finding 16).

---

## Top 5 actions
1. **Stop pushing the tag before the build** — DRY_RUN version → build+validate → `CUSTOM_TAG` (Fixes the orphan-tag bug; `build.yml:30-39` → port `neurhome/.github/workflows/build.yml:26-72`).
2. **Make `-Pandroid.versionName` real** — `app/build.gradle:17` ignores it, so every APK ships `versionName "1.0.0"` regardless of tag.
3. **Add a prod-variant validation step** (`:app:testCiProdDebugUnitTest :app:lintCiProdRelease`) before signing, and run assemble+test+lint in PR CI — today CI runs zero tests and zero lint.
4. **SHA-pin all actions and cut the token's privilege** — top-level `permissions: contents: read`, `contents: write` only on the release path.
5. **Replace `r0adkll/sign-android-release` + hardcoded `BUILD_TOOLS_VERSION 34.0.0`** with the SDK-resolved `zipalign`/`apksigner` script, and remove the `|| 'password'`/`|| 'key'` signing fallbacks.