# Pixlit — Gradle & Android build configuration review

**Scope:** `app/build.gradle`, `gradle/libs.versions.toml`, `gradle.properties`, `settings.gradle`, `build.gradle`, `gradle/wrapper/gradle-wrapper.properties`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `.github/workflows/build.yml`, `app/src/**` flavor source sets, plus the neurhome blueprint (`app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `settings.gradle`, `build.gradle`, `gradle/gradle-daemon-jvm.properties`, `.github/actions/setup-jdk-gradle/action.yml`, `.github/workflows/{build,pr}.yml`, `.github/dependabot.yml`).

**Method note (evidence base):** read-only review; I did not run Gradle. Where I make a claim about *what actually happens on this machine*, it is backed by artifacts already in `app/build/**` from a prior real build (Gradle 9.5.0, JDK 21 — see `.gradle/config.properties:2` `java.home=…/jetbrains_s_r_o_-21-amd64-linux.2`) and by `app/build/outputs/mapping/ciProdRelease/configuration.txt` (the R8 configuration dump, which records every consumer rule that actually applied). Those artifacts also show historical flavor names (`app/build/test-results/testDevDebugUnitTest/`, `app/build/outputs/apk/dev/debug/`) proving the `distribution` dimension was added on top of the older `env`-only matrix.

---

## Already correct (keep)

- `android.nonTransitiveRclass=true` — `gradle.properties:3`.
- Release shrinking on: `minifyEnabled true` + `shrinkResources = true` + `proguard-android-optimize.txt` — `app/build.gradle:50-53`.
- R8 diagnostics are retained and useful: `app/build/outputs/mapping/ciProdRelease/{configuration,usage,seeds,mapping}.txt`.
- `lintVital<Flavor>Release` gates `assembleCiProdRelease` automatically (intermediates `app/build/intermediates/lint_vital_*` exist), so *fatal* lint issues cannot ship — only non-fatal ones can.
- A real test suite exists and passes: 53 tests across 7 classes, `failures="0"` in all of `app/build/test-results/testLocalDevDebugUnitTest/*.xml`.
- Unit-test JVM heap is raised (`maxHeapSize = "2048m"`, `app/build.gradle:74`) — needed for Robolectric.
- Explicit `sourceCompatibility/targetCompatibility` 17 and an explicit Robolectric repository rather than relying on defaults.
- Robolectric's `android-all` download is at least pinned to a mirror that is already in the repository list (`settings.gradle:17`), not left to Robolectric's live fallback ambiguity.

---

## Findings

### [SEVERITY: critical] The CI tag never reaches the APK: `versionName` is hardcoded and `versionCode` is frozen at 1

**Evidence**

- `.github/workflows/build.yml:74` — `-Pandroid.versionName="$TAG_VERSION" \`
- `app/build.gradle:16-17` — `versionCode 1` / `versionName "1.0.0"`
- Exhaustive search for the property: the only occurrences of `android.versionName` in the repo are `build.yml:74` and the literal strings in `app/build.gradle:16-17`. There is **no** `findProperty("android.versionName")` anywhere, and no `-Pandroid.versionCode` is ever passed.

**Impact**

- Every artifact published by the release job claims `versionName=1.0.0`, `versionCode=1`, no matter which tag it was built from. The released APK name (`pixelfed-client-<tag>.apk`, `build.yml:88-99`) and the GitHub release tag (`build.yml:110`) disagree with the binary's own metadata, so "which build is installed on the phone?" is unanswerable — and it cannot be retrofitted onto APKs already published.
- Sideloading still works (Android only rejects *lower* versionCodes), which is exactly why this stays invisible: there is no user-visible failure.
- It permanently blocks any store/internal-track distribution (upload requires a monotonic versionCode) and breaks any tag/version-driven updater (e.g. Obtainium-style `versionCode` comparison) since the value never changes.
- The property is silently ignored, so it looks like a working pipeline.

**Concrete recommendation**

`app/build.gradle` (`defaultConfig`):

```groovy
def stampedName = (findProperty("android.versionName") ?: "1.0.0").toString()
// 1.0.3-build.2 -> 10003 ; falls back to 1 when no tag is supplied
def stampedCode = (findProperty("android.versionCode") ?: "1") as int

defaultConfig {
    versionCode stampedCode
    versionName stampedName
    ...
}
```

`.github/workflows/build.yml` (in the "Assemble APK" step):

```yaml
TAG_VERSION="${{ steps.bump_and_tag.outputs.new_tag }}"
[ -z "$TAG_VERSION" ] && TAG_VERSION="1.0.0-pr"
# "1.2.3-build.4" -> 1000000*1 + 1000*2 + 3  (suffix stripped before parsing)
VERSION_CODE=$(printf '%s' "$TAG_VERSION" | sed 's/-.*$//' \
  | awk -F. '{printf "%d%04d%04d", $1, $2, $3}')
./gradlew \
  -Pandroid.versionName="$TAG_VERSION" \
  -Pandroid.versionCode="$VERSION_CODE" \
  "${ASSEMBLE_TASK}"
```

(Alternative for the code: `git rev-list --count HEAD` when `fetch-depth: 0` is already set — `build.yml:26`.)

**Blueprint reference:** neurhome wires exactly this: `versionName = findProperty("android.versionName").toString()` (`/home/carlo/projects/neurhome/app/build.gradle.kts:69`) with `versionCode = 300` maintained manually, and its tag action is pinned to a `DRY_RUN` first pass so the versionName stamped into the APK equals the tag that is later published (`neurhome/.github/workflows/build.yml:27-41,110-116`).

---

### [SEVERITY: high] CI passes `--tests`-style verification to nobody: unit tests and full lint never run in CI

**Evidence**

- `.github/workflows/build.yml:52-75` — the only Gradle invocation in the whole workflow is `./gradlew … "${ASSEMBLE_TASK}"` (`assembleCiDevRelease` on PRs, `assembleCiProdRelease` on main).
- No `test`/`check`/`lint` task is ever requested; there is no separate PR workflow (`.github/workflows/` contains only `build.yml`).

**Impact**

- The 53 existing unit tests are dead weight in CI: a PR that breaks OAuth parsing, tag counting or image utils merges green and is released, because `assemble*Release` does not depend on unit tests (AGP 9 creates unit-test tasks for the tested build type only).
- `lintVital*` does run, but only the *fatal* subset, and only for the release variant — the full lint (correctness/security/perf warnings) has never been executed on this project.
- The flavor-specific code paths (`app/src/dev/**`, `app/src/prod/**` `DevBanner.kt`) are only ever built, never asserted.

**Concrete recommendation** (mirror the blueprint, one Gradle invocation so the daemon/configuration is reused):

```yaml
- name: Assemble, test and lint
  run: |
    ./gradlew :app:assembleCiDevDebug :app:testCiDevDebugUnitTest :app:lintCiDevDebug --parallel
```

and, in the release job *before* signing (so a broken prod variant cannot be signed and released):

```yaml
- name: Validate prod variant before signing
  run: |
    ./gradlew -Pandroid.versionName="$TAG_VERSION" \
      :app:testCiProdDebugUnitTest :app:lintCiProdRelease
```

**Blueprint reference:** `neurhome/.github/workflows/pr.yml:84` runs `:app:assembleDevDebug :app:testDevDebugUnitTest :app:lintDevDebug --parallel`; `neurhome/.github/workflows/build.yml:65-73` runs `testProdDebugUnitTest lintProdRelease` before the signing step, with a comment explaining exactly why.

---

### [SEVERITY: high] Release signing depends on hardcoded build-tools 34.0.0 while the project compiles against SDK 37

**Evidence**

- `.github/workflows/build.yml:79-86` — `uses: r0adkll/sign-android-release@d5f8436…` with `env: BUILD_TOOLS_VERSION: "34.0.0"`.
- `app/build.gradle:10` — `compileSdk 37`.

**Impact**

- The action shells out to `<sdk>/build-tools/34.0.0/{zipalign,apksigner}` on `ubuntu-latest`. That is a version this repository does not control; when the runner image drops or relocates build-tools 34 the release job fails at the very last step, after the tag has already been pushed (see the tag-ordering finding below), leaving an orphan tag with no release.
- The action is also unmaintained and emits the deprecated `::set-output` command; it is pinned by SHA (good) but its internals are out of scope of any test here.

**Concrete recommendation**

Either resolve the installed build-tools at runtime (blueprint approach) or pin the version in *this* repo as a variable, and keep the SHA pin:

```yaml
- name: Resolve build-tools version from the SDK
  id: build_tools
  run: |
    sdk_root="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
    version=$(find "$sdk_root/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' \
      | sort -Vr | while read -r v; do [ -x "$sdk_root/build-tools/$v/apksigner" ] && { echo "$v"; break; }; done)
    echo "version=$version" >> "$GITHUB_OUTPUT"
```

then `BUILD_TOOLS_VERSION: ${{ steps.build_tools.outputs.version }}`.

**Blueprint reference:** `neurhome/.github/workflows/build.yml:100-133` does exactly this and documents that a past release failed for this reason ("those pinned build-tools were missing from the runner image"), replacing `r0adkll/sign-android-release` with explicit `zipalign`/`apksigner` steps.

---

### [SEVERITY: high] The release tag is created before the build is validated (orphan tags), and the release is auto-created on every push to `main`

**Evidence**

- `.github/workflows/build.yml:31-40` — `Bump version and push tag (main only)` with `anothrNick/github-tag-action@v1`, `PRERELEASE: true`, `INITIAL_VERSION: 1.0.0`, `WITH_V: false`.
- `build.yml:68-75` — the build runs *after* the tag push; `build.yml:104-111` publishes a release from that tag unconditionally.

**Impact**

- Any failure between the tag push and the release (build, signing, upload) leaves a tag with no release; the next run then bumps again, so tags and released artifacts drift apart. Combined with the ignored `versionName` (finding 1) there is no way to tell which tag a binary corresponds to.
- Every merge to `main` produces a tag + GitHub release (the `prerelease` suffix is the only guard), which floods the release list for a single-user project.

**Concrete recommendation** — resolve the tag first (dry run), build and validate, then push the tag with `CUSTOM_TAG`, then release:

```yaml
- id: version
  uses: anothrNick/github-tag-action@4ed44965e0db8dab2b466a16da04aec3cc312fd8 # 1.75.0
  env: { GITHUB_TOKEN: "${{ secrets.GITHUB_TOKEN }}", WITH_V: "false", PRERELEASE: "true", PRERELEASE_SUFFIX: "build", INITIAL_VERSION: "1.0.0", DRY_RUN: "true" }
# … build with -Pandroid.versionName=${{ steps.version.outputs.new_tag }} …
- id: bump_and_tag
  uses: anothrNick/github-tag-action@4ed44965e0db8dab2b466a16da04aec3cc312fd8 # 1.75.0
  env: { …, CUSTOM_TAG: "${{ steps.version.outputs.new_tag }}" }
```

**Blueprint reference:** `neurhome/.github/workflows/build.yml:27-41` (dry run), `:100-116` (tag pushed only after validation, `CUSTOM_TAG` pinned), with a comment stating the orphan-tag failure actually happened.

---

### [SEVERITY: medium] The 2×2 flavor matrix multiplies the most expensive tasks four-fold without adding functional coverage

**Evidence**

- `app/build.gradle:27-46` — `flavorDimensions = ["distribution", "env"]` with `local`/`ci` differing **only** by `applicationIdSuffix ".local"` and `appLabel`; `dev` adds `.dev` + the redirect scheme, `prod` adds nothing.
- Observed cost carriers: one unit-test task *per flavor* (`app/build/test-results/testDevDebugUnitTest/`, `testProdDebugUnitTest/` — both from the pre-`distribution` era, i.e. one test JVM per flavor) and one R8 run *per release variant* (`app/build/outputs/mapping/ciProdRelease/`).
- The four combos produce 8 variants: every `assemble` runs 4 × R8 (+4 × `lintVitalRelease`, 4 × resource shrink), and every `check`/`test` runs the same 53 tests four times, each in a fresh JVM with Robolectric.

**Impact**

- Build wall-clock and CI minutes are ~4× the necessary amount for the same signal. `localProd` (documented in `README.md:73`) and `ciDev` overlap functionally: the only distinction the README relies on is "install alongside" (`README.md:77-79`), which `applicationIdSuffix` alone provides.
- Extra variants also multiply KSP (Hilt + Room) and Hilt aggregation work, so incremental developer loops are slower than they need to be.

**Concrete recommendation** — keep one `env` dimension (`dev`/`prod`), and make the CI-only id suffix a build-time property rather than a flavor dimension; if the matrix must stay, stop testing/resource-shrinking the variants nobody ships:

```groovy
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variant ->
        // Only the primary flavor needs its own unit-test JVM.
        variant.enableUnitTest = (variant.flavorName == "localDev")
    }
}
```

(verify the `enableUnitTest` setter name against AGP 9.3's `VariantBuilder` before landing). Alternatively `./gradlew` invocations should name the one variant they care about instead of `assemble`/`check`.

**Blueprint reference:** neurhome deliberately runs a single `env` dimension (`dev`/`prod`) and solves the "same app installed twice" problem with `-PdevHostSuffix` applied to `versionNameSuffix`/`resValue` (`neurhome/app/build.gradle.kts:22-34`, `neurhome/.github/workflows/pr.yml:84`), keeping both local and CI dev variants cacheable and identical across machines.

---

### [SEVERITY: medium] The hand-rolled `testDebugUnitTest` alias is not a `Test` task: `--tests` filtering breaks, `check` still runs all flavors

**Evidence**

- `app/build.gradle:82-85` — `tasks.register("testDebugUnitTest") { dependsOn("testLocalDevDebugUnitTest") }`.
- AGP has no task of that name in this project: only flavor-qualified names exist in the build output (`app/build/test-results/testLocalDevDebugUnitTest/`, `app/build/reports/tests/testLocalDevDebugUnitTest/`), and AGP's naming is `test<VariantName>UnitTest`, so today there is no name collision.
- `README.md:81` tells developers to run `./gradlew testLocalDevDebugUnitTest` — i.e. the alias is already redundant documentation-wise.

**Impact**

- Anything that relies on the task being a `Test` task fails: `./gradlew :app:testDebugUnitTest --tests "*Upload*"` errors with "Unknown command-line option '--tests'", HTML/XML result aggregation and `--continue`-style CI reporting do not attach to it, and IDE/gradle-enterprise test reporting sees it as an opaque `DefaultTask`.
- It gives a false sense of coverage: it runs *only* the local dev flavor, not the other three. `./gradlew check` still launches four separate test JVMs (one per flavor) with the same suite.
- It shadows the *conventional* name: if the `distribution` dimension is ever dropped (recommended above), AGP will register a real `testDebugUnitTest` and configuration will fail with "Cannot add task 'testDebugUnitTest' as a task with that name already exists", unless this block is removed first.

**Concrete recommendation** — delete the alias (`app/build.gradle:82-85`) and use AGP's own names (`./gradlew testLocalDevDebugUnitTest`); if a short handle is wanted, make it lazily resolve AGP's task instead of duplicating the name:

```groovy
tasks.register("fastTest") { dependsOn("testLocalDevDebugUnitTest") }
```

---

### [SEVERITY: medium] Build caching / configuration cache / daemon tuning are absent from `gradle.properties`

**Evidence**

- `gradle.properties` is three lines: `org.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8`, `android.useAndroidX=true`, `android.nonTransitiveRclass=true`. No `org.gradle.caching`, `org.gradle.configuration-cache`, `org.gradle.parallel`, `org.gradle.vfs.watch`, `kotlin.incremental`, `ksp.incremental`, no `-XX:MaxMetaspaceSize`.
- No `configuration-cache` state is present in the local build's `.gradle/` tree (the directory contains only `9.5.0/`, `buildOutputCleanup/`, `config.properties`, `file-system.probe`, `kotlin/`, `vcs-1/` — no `configuration-cache/`), i.e. the feature is not in use here.
- CI does not pass `--build-cache`/`--configuration-cache` either (`build.yml:71-75`); `gradle/actions/setup-gradle@v6` (`build.yml:47-48`) caches the Gradle User Home but cannot substitute for `org.gradle.caching=true`.

**Impact**

- No task-output caching: re-running the same tasks after a branch switch/clean, and CI runs, redo KSP, Kotlin compilation, dexing and R8 from scratch. For a project whose expensive tasks are flavor-multiplied (previous finding) this is the largest single win available.
- Configuration phase runs on every invocation; with four flavors, KSP and Hilt model creation that is measurable on every `./gradlew` call.
- 3072 MB with no metaspace cap is a poor pairing for a build that runs KSP (Hilt + Room) and R8 in-process; the Kotlin/KSP daemons and the Gradle daemon can push metaspace without bound before the heap limit is reached.

**Concrete recommendation** — `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC -Dfile.encoding=UTF-8
org.gradle.configuration-cache=true
org.gradle.caching=true
org.gradle.parallel=true
kotlin.incremental=true
ksp.incremental=true
```

and, if the configuration cache should be non-negotiable in CI once verified locally, add `--configuration-cache` to the Gradle invocations. Verify CC compatibility *first* with `./gradlew :app:assembleCiDevDebug --configuration-cache` and watch for "configuration cache entry stored/reused".

**Blueprint reference:** `neurhome/gradle.properties` sets exactly this set (`-Xmx4g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC`, configuration cache, caching, parallel, vfs.watch, kotlin/ksp incremental) — with the reason for each in a comment.

---

### [SEVERITY: medium] No JDK/toolchain or daemon-JVM pin: CI builds on JDK 17, this machine builds on JetBrains JDK 21, and the foojay resolver is applied but unused

**Evidence**

- `settings.gradle:8-10` — `id 'org.gradle.toolchains.foojay-resolver-convention' version '0.10.0'` is applied.
- Exhaustive search for `toolchain|jvmToolchain|jvmTarget|java.home|kotlin.jvm` across all `*.gradle*` files returns **only** that settings line — no toolchain is ever requested, so the foojay plugin contributes nothing.
- `.github/workflows/build.yml:41-44` — `actions/setup-java@v5` with `java-version: 17`, `distribution: temurin`.
- `.gradle/config.properties:2` — `java.home=/home/carlo/.gradle/jdks/jetbrains_s_r_o_-21-amd64-linux.2` (the build's daemon JVM is JBR 21).
- `README.md:60-63` states "JDK 17" as a prerequisite while `app/build.gradle:58-59` compiles to Java 17 bytecode on a JDK 21 daemon.

**Impact**

- Reproducibility is convention-only: the compile classpath, the Kotlin compiler's own JVM, and the toolchain used for KSP/Hilt all depend on whatever JDK happens to launch Gradle. A divergent JDK in CI vs local is exactly the class of difference that produces "works locally, fails in CI" (annotation-processor behaviour, `--release` semantics, Kotlin `jvmTarget` inference).
- The applied-but-unused resolver plugin is also a small configuration-phase cost and a misleading signal to future contributors ("toolchains are pinned here" — they are not).

**Concrete recommendation** — pick one JDK (21 is what the local environment and the blueprint already use, and what AGP 9 expects) and pin it in both places:

```properties
# gradle/gradle-daemon-jvm.properties  (generated by `./gradlew updateDaemonJvm --jvm-version=21`)
toolchainVendor=JETBRAINS
toolchainVersion=21
```

```groovy
// app/build.gradle
kotlin { jvmToolchain(21) }        // or java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
compileOptions { sourceCompatibility JavaVersion.VERSION_21; targetCompatibility JavaVersion.VERSION_21 }
```

and set CI to the same vendor/version (`distribution: jetbrains`, `java-version: 21`). Do not leave `compileOptions` at 17 while the daemon runs 21.

**Blueprint reference:** neurhome pins JBR 21 in `gradle/gradle-daemon-jvm.properties` **and** in the shared composite action `neurhome/.github/actions/setup-jdk-gradle/action.yml` (with an explicit comment that the two must stay in lockstep or Gradle provisions a second JDK on every run), sets `JVM_21` explicitly for Kotlin, and uses `JavaVersion.VERSION_21`.

---

### [SEVERITY: medium] Robolectric is configured for a live Maven fetch and (apparently) does not receive the merged manifest/resources

**Evidence**

- `app/build.gradle:72-78` — `testOptions { unitTests.all { maxHeapSize = "2048m"; jvmArgs "-Xshare:off"; systemProperty "robolectric.dependency.repo.id", "custom"; systemProperty "robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/" } }`.
- There is **no** `includeAndroidResources`/`isIncludeAndroidResources` anywhere, and no `robolectric.properties` (`app/src/test/resources/` does not exist — the only `app/src` children are `dev`, `main`, `prod`, `test`).
- Consistent with the flag being off, none of the intermediates AGP produces for it exist in the current `app/build/intermediates/` tree: no `unit_test_config_directory/**`, no `test_config.properties`, no `apk_for_local_test/**`, while every other variant-scoped intermediate is present.
- Robolectric *is* used, including for `ExifInterface`/`File` work: `app/src/test/java/ovh/litapp/pixlit/utils/ImageUtilsTest.kt:19` and `app/src/test/java/ovh/litapp/pixlit/ui/upload/UploadViewModelTest.kt:29` (`@RunWith(RobolectricTestRunner::class)`).

**Impact**

- Tests pass today only because none of them touch resources or the merged manifest. The moment a Compose UI test or anything using `R.*`, a themed Context, or manifest metadata is added (AGENTS.md explicitly plans "Compose Test Rule for UI testing"), the suite fails for environmental reasons — a classic multi-hour detour.
- Robolectric resolves `android-all` at test runtime from a hardcoded Google-hosted Maven Central mirror. That is a network dependency inside the test task, invisible in the dependency graph, not covered by Gradle's dependency cache, and not declared as a task input (changing the URL does not invalidate the test task). It is also the *only* place the mirror choice is documented.
- `-Xshare:off` disables class-data sharing for every forked test JVM (4 of them today), slowing startup, and there is no comment recording why it is needed.

**Concrete recommendation**

1. Turn resource access on and document the SDK pin (protects against a future `targetSdk` bump, see below):

```groovy
testOptions {
    unitTests {
        includeAndroidResources = true
        all {
            maxHeapSize = "2048m"
            jvmArgs += ["-Xshare:off"]   // only if a JDK CDS crash is actually reproducible
            // input so a URL change invalidates the test task
            inputs.property("robolectricRepoUrl", robolectricRepoUrl)
        }
    }
}
```

2. `app/src/test/resources/robolectric.properties`:

```properties
sdk=35
```

3. Replace the runtime download with a staged, Gradle-resolved jar (offline mode) so tests are hermetic.

**Blueprint reference:** neurhome does all three: `isIncludeAndroidResources = true`, an explicit `robolectric.properties` `sdk=34` pin (kept in lockstep with a declared `android-all-instrumented` version), and a `robolectricAndroidAll` configuration + `copyRobolectricAndroidAll` task with `robolectric.offline=true`, `robolectric.dependency.dir`, and the version declared as a task input (`neurhome/app/build.gradle.kts:36-58,151-172`).

---

### [SEVERITY: medium] `targetSdk 35` lags `compileSdk 37` — a silent behaviour-opt-out that also couples the test suite to a future bump

**Evidence**

- `app/build.gradle:10` — `compileSdk 37`; `app/build.gradle:15` — `targetSdk 35`; `app/build.gradle:14` — `minSdk 34`.
- Robolectric's default SDK is `targetSdk`, and no `robolectric.properties` pin exists (see previous finding), so the test SDK is currently 35 *by accident of `targetSdk`*.

**Impact**

- The app opts out of every API 36/37 behaviour change (edge-to-edge enforcement, predictive back, scheduling/permission changes, stricter foreground-service and background-start rules). Those changes arrive the day `targetSdk` must move, as an untested bulk change — and there is no instrumented/Compose test layer to catch it.
- `compileSdk` 37 buys new APIs without testing the platform behaviour the app will eventually run under, i.e. the risky half of the pair is the one being deferred.
- Because Robolectric follows `targetSdk`, bumping it without a `robolectric.properties` pin will request an SDK the installed Robolectric (4.16.1) may not ship, turning an unrelated config bump into a test-suite outage.

**Concrete recommendation** — decouple these two changes: (a) add the `robolectric.properties` pin *first*; (b) then raise `targetSdk` (36 now, 37 with `compileSdk` when you are willing to test the behaviour changes) and run a device pass on the upload flow. Keep `minSdk 34` as-is; it is consistent across both projects.

**Blueprint reference:** neurhome keeps `compileSdk = 37` **and** `targetSdk = 37` (`neurhome/app/build.gradle.kts:63,68`) while pinning the Robolectric SDK independently to 34 — that decoupling is the point of the pin in `neurhome/gradle/libs.versions.toml:2-8`.

---

### [SEVERITY: medium] ProGuard rules: several are redundant with shipped consumer rules, and the ones that would make crash reports readable are missing

**Evidence** (all from the R8 configuration dump `app/build/outputs/mapping/ciProdRelease/configuration.txt`, which lists every rule R8 actually received)

- Redundant with libraries' own rules, which are already present in the same dump:
  - app rule `app/proguard-rules.pro:29-31` `-dontwarn retrofit2.**` / `-keep class retrofit2.** { *; }` vs. `configuration.txt:746-796` — Retrofit 3.0.0 ships `META-INF/proguard/retrofit2.pro` (`-keepattributes Signature, InnerClasses, EnclosingMethod`, `-keep,allowobfuscation,allowshrinking class retrofit2.Response`, …).
  - app rule `app/proguard-rules.pro:33-36` `-dontwarn okhttp3.**`, `-dontwarn okio.**`, `-dontwarn javax.annotation.**`, `-dontwarn org.conscrypt.**` vs. `configuration.txt:870-883` — OkHttp 5.5.0 ships `proguard.txt` containing `-dontwarn okhttp3.internal.platform.**` and `-dontwarn org.conscrypt.**`.
  - app rules `app/proguard-rules.pro:14-27` duplicate a large part of Gson 2.13.1's own `META-INF/proguard/gson.pro` (`configuration.txt:1012-1086`).
- Missing: no `-keepattributes SourceFile,LineNumberTable` and no `-renamesourcefileattribute SourceFile` in `app/proguard-rules.pro` (the file only keeps `*Annotation*,Signature,InnerClasses,EnclosingMethod`, lines 8-9).
- `ovh.litapp.pixlit.MainViewModel` is additionally kept, but only by AGP's generated rule for the `@Keep`/compose-mapping path (`configuration.txt:1087-1088`).
- No consumer rule at all exists for Coil (`configuration.txt` has no `io.coil-kt` section), so `-dontwarn io.coil-kt.**` at `app/proguard-rules.pro:38` is the one `-dontwarn` here that actually suppresses something — silently.

**Impact**

- `-keep class retrofit2.** { *; }` blocks optimization and obfuscation of the entire Retrofit package for no benefit — it is the opposite of the rule the library already ships (`allowobfuscation,allowshrinking`).
- Blanket `-dontwarn` on `okhttp3.**` and `io.coil-kt.**` hides genuinely missing classes, which is how reflection bugs get into a release build unnoticed.
- The complete absence of `SourceFile/LineNumberTable` keeps means obfuscated stack traces from a sideloaded release APK cannot be line-mapped back to source through `app/build/outputs/mapping/ciProdRelease/mapping.txt`. For a personal app that is the *only* crash-triage path there is.
- Gson reflection is the reason this rule block exists at all; every line of it is a standing release-only risk (the file's own comment at lines 15-16 records a past `IllegalStateException` with `TypeToken`).

**Concrete recommendation** — delete the redundant rules and add the trace-readability pair:

```proguard
# Readable stack traces for sideloaded release builds (kept in mapping.txt)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Retrofit, OkHttp, Gson, Room, Hilt, WorkManager and AndroidX all ship their own
# consumer rules (see build/outputs/mapping/<variant>/configuration.txt) - do not duplicate them.
# Only Coil needs a targeted suppression until it is migrated:
-dontwarn io.coil-kt.**
```

Long term, removing the Gson reflection dependency (kotlinx-serialization or Moshi codegen) deletes `app/proguard-rules.pro:11-27` entirely — see "deeper investments".

**Blueprint reference:** neurhome uses `kotlinx-serialization` (`neurhome/app/build.gradle.kts:8`) with no app-level reflection keeps at all, so its `proguard-rules.pro` has nothing to maintain.

---

### [SEVERITY: medium] No lint configuration and no lint gate: warnings are unowned and unbounded

**Evidence**

- `app/build.gradle` has no `lint { }` block; a search of all `*.gradle*` files for lint configuration returns nothing.
- CI never invokes a lint task (`build.yml:52-75`); only AGP's automatic `lintVital<Flavor>Release` runs (intermediates `app/build/intermediates/lint_vital_*`).

**Impact**

- Only errors marked *fatal* block a release. Everything else (and there is measurable debt: `android:allowBackup="true"` with no `dataExtractionRules`, unused `androidTest` dependencies, `targetSdk` drift) accumulates with no record and no gate.
- A future `lintVital` addition (a new fatal check in an AGP bump) can break the release build unexpectedly, at the worst time, because nothing else surfaces lint output.

**Concrete recommendation**

```groovy
lint {
    warningsAsErrors = true
    abortOnError = true
    baseline = file("lint-baseline.xml")   // record existing debt once, then keep it frozen
    checkDependencies = true
}
```

and add `:app:lintCiDevDebug` to the PR Gradle invocation (see the CI finding). Use the HTML report as a CI artifact.

**Blueprint reference:** neurhome exactly this, with a rationale comment and a checked-in baseline (`neurhome/app/build.gradle.kts:177-183`), plus `app/build/reports/lint-results-devDebug.html` uploaded from `pr.yml:118-126`.

---

### [SEVERITY: medium] `allowBackup="true"` with no `dataExtractionRules`/`fullBackupContent` while OAuth tokens live in plain SharedPreferences

**Evidence**

- `app/src/main/AndroidManifest.xml:15` — `android:allowBackup="true"` (no `android:dataExtractionRules`, no `android:fullBackupContent`; the only `tools:` usage in the manifest is the WorkManager initializer removal at lines 64-71).
- `app/src/main/java/ovh/litapp/pixlit/data/auth/TokenManager.kt:11` — `context.getSharedPreferences("pixlit_prefs", Context.MODE_PRIVATE)`; the same class stores `clientSecret` (referenced from `LoginScreen.kt:47` and `PixelfedRepository.kt:67-70`).

**Impact**

- The OAuth access token, `clientId` and `clientSecret` are written to unencrypted, auto-backed-up storage: they are uploaded to the user's Google Drive backup and copied by device-to-device transfer (`adb backup`-style extraction paths as well). `allowBackup=true` without rules is the default that lint flags as `DataExtractionRules`.
- Combined with `android:exported="true"` deep-link entry on `MainActivity` (`AndroidManifest.xml:24-40`), the credential store is the most valuable asset in the app and it is currently the least protected.

**Concrete recommendation** — either exclude the store or drop backup:

```xml
<application
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="@xml/backup_rules"
    ... >
```

```xml
<!-- res/xml/data_extraction_rules.xml -->
<data-extraction-rules>
    <cloud-backup><exclude domain="sharedpref" path="pixlit_prefs.xml"/></cloud-backup>
    <device-transfer><exclude domain="sharedpref" path="pixlit_prefs.xml"/></device-transfer>
</data-extraction-rules>
```

and (separately, higher value) move the credential store to `androidx.security:security-crypto`'s `EncryptedSharedPreferences` or to a Keystore-wrapped value.

---

### [SEVERITY: medium] The flavor matrix gives two installable variants the *same* OAuth redirect scheme, so the callback can land in the wrong app

**Evidence**

- `app/build.gradle:29-33` — `local { dimension "distribution"; applicationIdSuffix ".local"; manifestPlaceholders.appLabel = "Pixlit (Internal)" }` (no scheme override).
- `app/build.gradle:37-42` — `dev { dimension "env"; applicationIdSuffix ".dev"; manifestPlaceholders.appAuthRedirectScheme = "pixlit-dev" }`; `prod` (lines 44-46) inherits the default `"pixlit"` from `app/build.gradle:24`.
- `app/src/dev/res/values/strings.xml:2` — `<string name="redirect_uri">pixlit-dev://oauth</string>`; `app/src/prod/res/values/strings.xml:2` — `pixlit://oauth`. The scheme is consumed at runtime from the resource, not from the placeholder: `MainViewModel.kt:66-69` (`context.getString(R.string.redirect_uri)` … `uri.scheme == scheme`), `LoginScreen.kt:31`.
- `README.md:77-79` states the intent: local builds "can be installed alongside APKs built by GitHub Actions".

**Impact**

- `localDev` (`ovh.litapp.pixlit.local.dev`) and `ciDev` (`ovh.litapp.pixlit.dev`) both register `pixlit-dev://oauth`; `localProd` (`…local`) and `ciProd` (`ovh.litapp.pixlit`) both register `pixlit://oauth`. Because distribution variants exist precisely so that two builds *can* coexist on one device, this is reachable by design, not in theory: installing a CI PR artifact next to a local build (or a prod release next to `localProdDebug`) makes the OAuth callback ambiguous — Android shows a chooser, or the browser hands the code to the other app, which then fails token exchange with a redirect-URI mismatch.
- The two sources of truth (manifest placeholder vs. `strings.xml`) are currently consistent per *env* flavor, so the bug is invisible in single-variant testing.

**Concrete recommendation** — make the scheme a function of the full variant, not just `env`:

```groovy
// distribution flavor: give local its own scheme
local { manifestPlaceholders.appAuthRedirectScheme = "pixlit-local" }
```

plus `app/src/local/res/values/strings.xml` with the matching `redirect_uri`, so scheme and resource cannot drift. Better still, derive both from `applicationId` (e.g. `manifestPlaceholders.appAuthRedirectScheme = "${applicationId}.oauth"` and a `resValue "string", "redirect_uri", "${applicationId}.oauth://oauth"`), which removes the hand-maintained pair altogether. If the flavor matrix is collapsed per the earlier finding, this problem disappears with it.

---

### [SEVERITY: medium] Dependency freshness and supply-chain hygiene: no Dependabot, floating action tags, drift from the blueprint, and two version choices that risk runtime breakage

**Evidence**

- No `.github/dependabot.yml` exists (`.github/` contains only `workflows/build.yml`); the blueprint has a grouped weekly Gradle + GitHub-Actions config (`neurhome/.github/dependabot.yml`).
- Actions are pinned by floating major tags, not SHAs: `build.yml:25` `actions/checkout@v4`, `:33` `anothrNick/github-tag-action@v1`, `:42` `actions/setup-java@v5`, `:48` `gradle/actions/setup-gradle@v6`, `:101` `actions/upload-artifact@v4`, `:108` `softprops/action-gh-release@v2`. neurhome pins every one by commit SHA.
- Version drift vs. the blueprint: AGP `9.3.2` vs `9.4.1`, Kotlin `2.4.10` vs `2.4.21`, Compose BOM `2026.08.00` vs `2026.09.00`, Room `2.8.4` vs `2.8.5`, Robolectric `4.16.1` vs `4.17`, KSP `2.3.11` vs `2.3.12`, Turbine `1.1.0` vs `1.2.1`, core-ktx `1.19.0` vs `1.19.1`, Gradle wrapper `9.5.0` vs `9.8.1` (`gradle/wrapper/gradle-wrapper.properties:3`).
- Runtime-risk pair: `libs.versions.toml:12,14` — `okhttp = "5.5.0"` with `coil = "2.7.0"`. Coil 2.7 is built against OkHttp 4.x, and the app does use Coil's network path (`RecentPostsSection.kt:92` `AsyncImage(model = attachments[page].url …)`); Gradle will resolve a single OkHttp (5.5.0). Retrofit 3.0.0 (`libs.versions.toml:13`) is the correct pairing for OkHttp 5, Coil 2.7 is not.
- `libs.versions.toml:25` — `ksp = "2.3.11" # Assuming matching version for Kotlin 2.4.10`: the comment states the version pairing is a guess (the build succeeds, so the pair is compatible; the comment is what is wrong).
- `settings.gradle:15-18` declares `google()` and a Google-hosted Maven Central mirror, but not `mavenCentral()`, in both `pluginManagement` and `dependencyResolutionManagement`; there is no `gradle/verification-metadata.xml` (the blueprint has none either, so this is advisory).

**Impact**

- A floating-tag action can change behaviour without any change in this repository; on a release job that holds signing secrets and `contents: write`, that is the highest-leverage supply-chain exposure in the project.
- Nothing tells the maintainer that AGP/Kotlin/Compose moved; the drift above already exists and will silently widen (next AGP major will be forced, not chosen).
- A Coil/OkHttp mismatch would manifest at runtime on the "load recent posts" path (post-at-a-time, hard to attribute) rather than at build time.
- A single upstream incident at `maven-central.storage-download.googleapis.com` breaks both dependency resolution and Robolectric, with no fallback repository declared.

**Concrete recommendation**

- Add `.github/dependabot.yml` (gradle + github-actions, weekly, `open-pull-requests-limit: 10`, grouped minor/patch) — copy `neurhome/.github/dependabot.yml` verbatim.
- Pin actions by SHA (`actions/checkout@3d3c42e5… # v7` etc. — the SHAs are already resolved in neurhome's workflows and can be reused).
- Move to Coil 3 (`io.coil-kt.coil3:coil-compose` + `coil-network-okhttp`) so the network stack and OkHttp 5 are the same major, or explicitly verify the current combination:

```bash
./gradlew :app:dependencies --configuration ciDevDebugRuntimeClasspath | grep -E 'okhttp|coil'
```

- Add `mavenCentral()` next to the mirror so a mirror outage degrades instead of failing.
- Fix or delete the KSP comment, and bump the wrapper/AGP/Kotlin to the blueprint versions in one deliberate step.

---

### [SEVERITY: low] Unused and redundant dependencies / catalog entries

**Evidence**

- `app/build.gradle:96-97` — `implementation libs.androidx.material.icons.core` **and** `libs.androidx.material.icons.extended`. The only icon usages are `Icons.Default.*` (`UploadScreen.kt:384`, `RecentPostsSection.kt:126,130`, `TagSelectionCloud.kt:41`, `CollectionSelectionSection.kt:64,82`, `PositionSelectionSection.kt:52,84,107,124,137,167`); `material-icons-extended` already depends on `material-icons-core`, so the `core` line is redundant, and `extended` is a very large compile-time artifact pulled in for essentially one non-core glyph (`Icons.Default.Repeat`, `RecentPostsSection.kt:130`).
- No instrumented test sources exist (`app/src/` contains only `dev/`, `main/`, `prod/`, `test/`), yet `app/build.gradle:131-134` declares `androidTestImplementation libs.androidx.junit`, `libs.androidx.espresso.core`, `platform(libs.androidx.compose.bom)`, `libs.androidx.ui.test.junit4`, and `app/build.gradle:136` `debugImplementation libs.androidx.ui.test.manifest` — all currently dead. CI never runs connected tests either.
- `libs.versions.toml:57` — `mockk-android` is defined but never referenced; `libs.versions.toml:75` — the `kotlin-android` plugin alias is defined but never applied (this project relies on AGP 9's built-in Kotlin support, evidenced by `app/build/intermediates/built_in_kotlinc/**/compile<Variant>Kotlin/` and by the plugins block at `app/build.gradle:1-6`, which lists only `android.application`, `kotlin.compose`, `hilt`, `ksp`). If AGP's built-in Kotlin is ever turned off, the build breaks in a confusing way because the catalog entry looks intentional.
- `app/build.gradle:20-22` — `vectorDrawables { useSupportLibrary = true }` is a no-op with `minSdk 34` (native vector support since API 21).
- `app/src/main/res/values/strings.xml:1-2` is an empty `<resources></resources>` element; all real strings live in the flavor source sets (`dev/`, `prod/`). `app/src/main/res/values/themes.xml` is used, so the file is just dead weight that implies main has strings.
- `settings.gradle:22` — `rootProject.name = "PixelfedClient"` while the product, package and README all say "Pixlit" (`README.md:1`, `app/build.gradle:9`).

**Impact** Minor build-time and comprehension cost; the redundant `material-icons-extended`/`icons-core` pair and the dead `androidTest` block are the ones with measurable cost (icons-extended is a known incremental-build tax; `ui-test-manifest` is merged into every debug manifest).

**Concrete recommendation** — delete `androidx-material-icons-core`, the unused `androidTestImplementation`/`ui-test-manifest` lines (re-add them with the first instrumented test), `mockk-android`, `vectorDrawables { }`, the empty `main/res/values/strings.xml`, and the unused `kotlin-android` alias **or** apply it explicitly and drop the reliance on built-in Kotlin. Rename `rootProject.name` to `Pixlit`. If `Icons.Default.Repeat` can be replaced by a core glyph or a small local vector, `material-icons-extended` can go too.

---

### [SEVERITY: low] `composeCompiler` is not configured: no metrics, no stability configuration, no reports

**Evidence**

- `app/build.gradle:62-64` — `buildFeatures { compose = true }` only; there is no `composeCompiler { }` block anywhere, and `app/` contains no stability configuration file. `app/build/intermediates/compose_mapping/` exists, so compose mapping is on by default but no metrics/reports are requested.

**Impact**

- There is no data on skippability/recomposition, and no mechanism to declare (and thereby audit) types the compiler cannot prove stable — exactly the situation neurhome documents for its `data.Application` holder. For an upload-heavy Compose app with a pager, image metadata rows and tag clouds, that is the first measurement a startup/jank review needs.

**Concrete recommendation**

```groovy
composeCompiler {
    metricsDestination = layout.buildDirectory.dir("compose-metrics")
    reportsDestination = layout.buildDirectory.dir("compose-reports")
    // stabilityConfigurationFiles.add(layout.projectDirectory.file("compose_stability.conf"))
}
```

Run once (`./gradlew :app:assembleLocalDevDebug`), inspect `app/build/compose-reports/**`, then keep the destinations out of the default build if the artifacts are noisy.

**Blueprint reference:** neurhome wires `stabilityConfigurationFiles` with a comment explaining which type needed the promise and why (`neurhome/app/build.gradle.kts:186-191`).

---

### [SEVERITY: low] No baseline profile / macrobenchmark, and no Room schema export

**Evidence**

- No `baselineprofile` module (`settings.gradle:23` includes only `:app`) and no `baseline-prof.txt` anywhere (`find **/baseline-prof*` → nothing in `app/src`); likewise no `androidx.benchmark`/`macrobenchmark` dependency.
- The APK nevertheless *does* carry profiles — only the libraries': `app/build/intermediates/merged_art_profile/ciProdRelease/mergeCiProdReleaseArtProfile/baseline-prof.txt` starts with `# Baseline profiles for androidx.fragment`, then `# Baseline profiles for lifecycle-process`, `# … Lifecycle ViewModel`, `# … androidx.activity` (lines 1, 4251, 4293, 5658), and a search for `ovh/litapp` in that 5 900-line file returns **no** app-class entries.
- `app/src/main/java/ovh/litapp/pixlit/data/db/AppDatabase.kt:5` — `@Database(entities = [StatusEntity::class], version = 1, exportSchema = false)` and no `room { }` block / `app/schemas` directory in `app/build.gradle`.

**Impact**

- App classes (Activity/Application/Compose entry points) are not in any profile, so cold start relies entirely on library profiles. On the other hand, **`androidx.profileinstaller` is not needed here** — its purpose is API 24–30 dexopt/profile installation, and `minSdk 34` means ART handles profiles natively; a naive "add profileinstaller like neurhome" would be cargo culting.
- With `exportSchema = false` there is no committed schema history, so the first Room migration (very likely for a status cache) cannot be validated against a previous version.

**Concrete recommendation** (deeper work): add a `:baselineprofile` module (`com.android.test` + `androidx.benchmark.macro:benchmark-macro-junit4`), record a profile on a device, and commit `app/src/main/baseline-prof.txt`; skip `androidx.profileinstaller` given `minSdk 34`. For Room, flip to `exportSchema = true`, add `room { schemaDirectory("$projectDir/schemas") }` (Groovy: `ksp { arg('room.schemaLocation', "$projectDir/schemas") }`), commit `app/schemas`, and gate it in CI.

**Blueprint reference:** neurhome has a `:baselineprofile` module excluded from the app build, `libs.androidx.profileinstaller` wired with a comment ("Nothing is embedded until a profile is committed…"), and `room { schemaDirectory("$projectDir/schemas") }` plus a CI step that fails when `app/schemas` is dirty (`neurhome/.github/workflows/pr.yml:88-100`).

---

### [SEVERITY: low] CI/workflow hygiene: over-broad token scope, no change filter, no artifact/reading-quality steps

**Evidence**

- `build.yml:21-22` — the single job sets `permissions: contents: write`, which therefore also applies to `pull_request` runs.
- `build.yml:11-15` — the workflow triggers on every PR to `main` with no path filtering, and there is no always-running "required checks" job.
- `build.yml:101-103` — the uploaded artifact has no checksum and no `retention-days`.
- Action versions are not pinned by SHA (covered above).

**Impact** Least-privilege and cost-hygiene issues rather than correctness ones (fork PRs do get a downgraded token automatically, which limits the blast radius). Docs-only PRs still pay for a full APK build. Because the PR check can be skipped/cancelled with no always-on job, a branch-protection rule pointed at this workflow can hang "pending".

**Concrete recommendation**

```yaml
permissions:
  contents: read            # workflow level
jobs:
  build:
    permissions:
      contents: write       # only this job needs to push tags
```

add a cheap `paths-ignore` analysis job plus an always-running `required-checks` job (neurhome's `pr.yml:20-60,197-220` pattern), emit `sha256sum` for the APK, and set `retention-days`.

**Blueprint reference:** `neurhome/.github/workflows/{build,pr}.yml` — workflow-level `contents: read`, change detection, checksum generation, lint/coverage artifacts uploaded with `if: always()`, and an always-on `required-checks` job.

---

### [SEVERITY: low] Repository hygiene: `.kotlin/` is not ignored

**Evidence**

- `.kotlin/` exists at the repo root with `errors/` and `sessions/` contents; `.gitignore` lists `.gradle/`, `build/`, `local.properties`, `*.log`, `.idea/`, keystores, etc., but not `.kotlin/`. (Reading the whole file: no `.kotlin` entry.)

**Impact** Kotlin build-session state shows up as untracked noise in every `git status`, and one careless `git add -A` commits it. (`.gitignore` is otherwise correct, including `local.properties` and `mapping` outputs.)

**Concrete recommendation** — add:

```gitignore
# Kotlin build session state
.kotlin/
```

---

## Quick wins (under 30 minutes each)

1. **Wire the version stamp** — `app/build.gradle:16-17` + `build.yml:74` (`findProperty` + `-Pandroid.versionCode`). Highest value per minute in this review. *(finding 1)*
2. **Add `org.gradle.caching=true`, `org.gradle.configuration-cache=true`, `org.gradle.parallel=true`, `-Xmx4g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC` to `gradle.properties`**, then verify with `./gradlew :app:assembleCiDevDebug --configuration-cache`. *(finding 6)*
3. **Add `:app:testCiDevDebugUnitTest :app:lintCiDevDebug` to the CI Gradle invocation** so the existing suite actually gates PRs. *(finding 2)*
4. **Delete the redundant ProGuard rules** (`retrofit2.**` keep + `dontwarn`, `okhttp3/okio/javax.annotation/conscrypt` `dontwarn`) and add `-keepattributes SourceFile,LineNumberTable` + `-renamesourcefileattribute SourceFile`. *(finding 10)*
5. **`unitTests { includeAndroidResources = true }` + `app/src/test/resources/robolectric.properties` (`sdk=35`)** so resource/Compose tests are possible and a future `targetSdk` bump cannot silently break the suite. *(findings 7, 8)*
6. **Move the Robolectric repo URL into a Gradle property and declare it as a test-task input**; delete the `testDebugUnitTest` alias task and the dead `androidTest*`/`icons-core` dependency lines. *(findings 5, 7, 13)*
7. **Add `.kotlin/` to `.gitignore`**, add `.github/dependabot.yml` (copy from neurhome), pin the workflow actions to the SHAs already resolved in neurhome's workflows, and scope `permissions` to `contents: read` at workflow level. *(findings 12, 14)*
8. **Give the `local` distribution flavor its own OAuth scheme + `app/src/local/res/values/strings.xml`.** *(finding 11)*

## Deeper investments

1. **Collapse the flavor matrix to one dimension** (`env`: dev/prod) or, if it must stay, disable unit tests for non-primary variants via `beforeVariants` — removes 3 of 4 test JVMs and 2–3 of 4 R8 runs per full build. *(finding 4)*
2. **Fix the release pipeline ordering and the signing step**: dry-run tag → build → validate → push tag (`CUSTOM_TAG`) → sign with SDK-resolved build-tools → release; replace `r0adkll/sign-android-release`. *(findings 2, 3)*
3. **Pin the JDK everywhere**: `gradle/gradle-daemon-jvm.properties` + a composite setup action so CI and local cannot drift, and align `compileOptions` with the pinned JDK. *(finding 7)*
4. **Replace Gson with kotlinx-serialization** (or Moshi codegen via KSP): deletes the entire reflection-sensitive rule block, removes the `TypeToken` failure class, and matches the blueprint's stack.
5. **Coil 3 migration** (or an explicit resolution check) so the image stack and OkHttp 5 are the same major; then remove the blanket `-dontwarn io.coil-kt.**`.
6. **Baseline profile + macrobenchmark module** and a committed `baseline-prof.txt` (skip `profileinstaller` at `minSdk 34`); plus Room `exportSchema = true` with committed `app/schemas` and a CI dirtiness check.
7. **Lint gate with baseline + `warningsAsErrors`**, plus compose compiler metrics/stability config and `targetSdk` catch-up (36 → 37) with a device pass instead of a silent deferral.
8. **Decide on the `allowBackup` / credential-storage model** — `dataExtractionRules` at minimum, `EncryptedSharedPreferences` better.

## Top 5 actions

1. **Wire `versionName`/`versionCode` to the CI tag** (`findProperty("android.versionName")` + `-Pandroid.versionCode`) — today every released APK claims `1.0.0`/`1` regardless of its tag.
2. **Make CI prove the build**: run `testCiDevDebugUnitTest` and `lintCiDevDebug` on PRs, and `testCiProdDebugUnitTest` + `lintCiProdRelease` before signing.
3. **Repair the release pipeline**: tag only after validation, and stop depending on hardcoded build-tools `34.0.0` for `apksigner`.
4. **Turn on Gradle caching and the configuration cache**, and stop building/testing four near-identical flavor variants (collapse the matrix or scope unit tests to one variant); pin the JDK/daemon JVM while you are in `gradle.properties`.
5. **Right-size the R8/ProGuard file** (drop the duplicated Retrofit/OkHttp/Gson rules, add `SourceFile,LineNumberTable`) and add `includeAndroidResources = true` + a Robolectric SDK pin so the test suite survives the next dependency or `targetSdk` bump.