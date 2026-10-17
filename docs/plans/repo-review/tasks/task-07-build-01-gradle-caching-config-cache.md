# Task 07 — BUILD-01: Enable Gradle caching and configuration cache

**Wave 0 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) medium-6

## Problem
`gradle.properties` is three lines (`org.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8`, `android.useAndroidX=true`, `android.nonTransitiveRclass=true`): no `org.gradle.caching`, no `org.gradle.configuration-cache`, no `org.gradle.parallel`, no Kotlin/KSP incremental flags, and no metaspace cap. CI passes no `--build-cache`/`--configuration-cache` either (`build.yml:71-75`), and `setup-gradle` cannot substitute for `org.gradle.caching=true`. With the 2×2 flavor matrix (`app/build.gradle:27-46`) this is the largest single build-time win available.

## Scope
- `gradle.properties`: adopt the `neurhome` property set, including the metaspace cap:

```properties
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC -Dfile.encoding=UTF-8
org.gradle.configuration-cache=true
org.gradle.caching=true
org.gradle.parallel=true
kotlin.incremental=true
ksp.incremental=true
```

- Verify configuration-cache compatibility across the variant matrix before relying on it.
- Add `--configuration-cache` to the CI Gradle invocations once locally verified.

## Acceptance criteria
- [ ] `./gradlew :app:assembleCiDevDebug --configuration-cache` stores/reuses a configuration-cache entry with no CC problems.
- [ ] A second identical build reports `FROM-CACHE` for compile/R8 tasks.
- [ ] Metaspace cap is in effect (no unbounded metaspace growth under KSP + R8).

## Dependencies
- None.

## Verification
- `./gradlew :app:assembleCiDevDebug --configuration-cache`
