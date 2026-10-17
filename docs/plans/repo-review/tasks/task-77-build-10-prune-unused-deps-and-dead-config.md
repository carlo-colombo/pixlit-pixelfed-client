# Task 77 — BUILD-10: Prune unused dependencies and dead config

**Wave 4 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) low-unused

## Problem
`app/build.gradle:96-97` declares `androidx.material.icons.core` alongside `material-icons.extended`
(the latter already depends on core), `app/build.gradle:131-136` declares a dead `androidTest` block
plus `ui-test-manifest` although no instrumented sources exist, `libs.versions.toml:57` defines
`mockk-android` and `:75` a `kotlin-android` plugin alias that is never applied or referenced,
`app/build.gradle:20-22` keeps a no-op `vectorDrawables { }` at `minSdk 34`, and
`settings.gradle:22` names the project `PixelfedClient` while product/package/README say "Pixlit".

## Scope
- `app/build.gradle`: delete `implementation libs.androidx.material.icons.core`; delete the
  `androidTestImplementation` lines (`:131-134`) and `debugImplementation libs.androidx.ui.test.manifest`
  (`:136`), to be re-added with the first instrumented test; delete `vectorDrawables { }` (`:20-22`).
- Keep `material-icons-extended` and say why in the diff (`Icons.Default.Repeat`,
  `RecentPostsSection.kt:130`), or replace that glyph with a local vector and drop it too.
- `gradle/libs.versions.toml`: delete `mockk-android` and the unused `kotlin-android` alias, or apply
  the alias explicitly and stop relying on AGP's built-in Kotlin (choose one, not both).
- Delete the empty `app/src/main/res/values/strings.xml:1-2`.
- `settings.gradle:22`: `rootProject.name = "Pixlit"`.

## Acceptance criteria
- [ ] `grep -rn 'material.icons.core\|mockk-android\|kotlin-android\|vectorDrawables' app/build.gradle gradle/libs.versions.toml`
      returns no unused entry.
- [ ] `app/src/main/res/values/strings.xml` no longer exists.
- [ ] `rootProject.name` is `Pixlit`.
- [ ] `./gradlew :app:assembleLocalDevDebug :app:testLocalDevDebugUnitTest` passes after the deletions.

## Dependencies
- None

## Verification
- `./gradlew :app:assembleLocalDevDebug :app:testLocalDevDebugUnitTest`
