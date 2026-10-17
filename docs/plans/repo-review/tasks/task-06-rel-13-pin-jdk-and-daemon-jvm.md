# Task 06 — REL-13: Pin the JDK and Gradle daemon JVM

**Wave 0 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [01-ci-release-blueprint-gap.md](../../../review/01-ci-release-blueprint-gap.md) #13, [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) medium-7

## Problem
`build.yml:42-44` runs CI on temurin JDK 17, while the local Gradle daemon runs JetBrains JDK 21 (`.gradle/config.properties:2`) and `app/build.gradle:55-58` compiles to 17. There is no `gradle/gradle-daemon-jvm.properties` and no shared setup action, so nothing pins the JVM the daemon actually uses. The foojay resolver is applied (`settings.gradle:8-10`) but no toolchain is ever requested, so it contributes nothing.

## Scope
- Add `gradle/gradle-daemon-jvm.properties` with `toolchainVendor=JETBRAINS` and `toolchainVersion=21` (generate via `./gradlew updateDaemonJvm --jvm-version=21`).
- `.github/workflows/build.yml`: set `actions/setup-java` to the same vendor/version (`distribution: jetbrains`, `java-version: 21`).
- Keep `app/build.gradle` `compileOptions` and the `README.md:60-63` prerequisite consistent with the pin.

```properties
toolchainVendor=JETBRAINS
toolchainVersion=21
```

```yaml
- uses: actions/setup-java@<sha> # v5
  with:
    distribution: jetbrains
    java-version: 21
```

Keep the two in lockstep; if they drift, Gradle ignores `JAVA_HOME` and provisions a second JDK into `~/.gradle/jdks` on every run.

## Acceptance criteria
- [ ] CI and the local daemon use the same JDK version and vendor.
- [ ] On a clean `~/.gradle/jdks`, `./gradlew --version` shows the pinned JDK and provisions no second one.
- [ ] The README prerequisite matches the pin.

## Dependencies
- None.

## Verification
- `./gradlew --version` (after moving `~/.gradle/jdks` aside) reports the pinned vendor/version.
