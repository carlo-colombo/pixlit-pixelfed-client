# Project Update and Library Implementation Plan

This plan outlines the steps to update the Pixlit project's dependencies, migrate to a Version Catalog, and implement Hilt and Room as planned in the `AGENTS.md` file.

## User Review Required

> [!IMPORTANT]
> - **Kotlin 2.0 Migration**: Upgrading to Kotlin 2.4.10 will involve migrating the Compose Compiler to the official Kotlin Gradle Plugin.
> - **Hilt Integration**: `MainActivity` will be refactored to use `@AndroidEntryPoint`.
> - **Room Implementation**: A basic database will be introduced to cache Pixelfed statuses and tags.

## Proposed Changes

### Build System & Dependencies

#### [NEW] [libs.versions.toml](file:///home/carlo/projects/my-pixelfed/gradle/libs.versions.toml)
Centralize all dependency versions and definitions.

#### [MODIFY] [build.gradle](file:///home/carlo/projects/my-pixelfed/build.gradle)
Update to use the Version Catalog and new plugin versions.

#### [MODIFY] [app/build.gradle](file:///home/carlo/projects/my-pixelfed/app/build.gradle)
Update dependencies to use the catalog, enable Hilt, and Room. Update `compileSdk` and `targetSdk` to 35.

---

### Dependency Injection (Hilt)

#### [NEW] [PixlitApplication.kt](file:///home/carlo/projects/my-pixelfed/app/src/main/java/ovh/litapp/pixlit/PixlitApplication.kt)
Create the Hilt Application class.

#### [NEW] [NetworkModule.kt](file:///home/carlo/projects/my-pixelfed/app/src/main/java/ovh/litapp/pixlit/di/NetworkModule.kt)
Provide Retrofit, OkHttp, and PixelfedApi instances.

#### [NEW] [DatabaseModule.kt](file:///home/carlo/projects/my-pixelfed/app/src/main/java/ovh/litapp/pixlit/di/DatabaseModule.kt)
Provide Room database and DAO instances.

#### [NEW] [RepositoryModule.kt](file:///home/carlo/projects/my-pixelfed/app/src/main/java/ovh/litapp/pixlit/di/RepositoryModule.kt)
Provide `PixelfedRepository` and `TokenManager`.

#### [MODIFY] [MainActivity.kt](file:///home/carlo/projects/my-pixelfed/app/src/main/java/ovh/litapp/pixlit/MainActivity.kt)
Annotate with `@AndroidEntryPoint` and inject the repository.

---

### Persistence (Room)

#### [NEW] [AppDatabase.kt](file:///home/carlo/projects/my-pixelfed/app/src/main/java/ovh/litapp/pixlit/data/db/AppDatabase.kt)
Define the Room database.

#### [NEW] [StatusDao.kt](file:///home/carlo/projects/my-pixelfed/app/src/main/java/ovh/litapp/pixlit/data/db/StatusDao.kt)
Define DAOs for caching statuses.

---

### Refactoring & Updates

#### [MODIFY] [PixelfedRepository.kt](file:///home/carlo/projects/my-pixelfed/app/src/main/java/ovh/litapp/pixlit/data/repository/PixelfedRepository.kt)
Refactor to use injected `PixelfedApi` and `AppDatabase`.

#### [MODIFY] [AndroidManifest.xml](file:///home/carlo/projects/my-pixelfed/app/src/main/AndroidManifest.xml)
Register `PixlitApplication`.

## Verification Plan

### Automated Tests
- Run existing unit tests to ensure no regressions: `./gradlew test`
- Add a simple test for Hilt injection if possible.

### Manual Verification
- Deploy the app to a device/emulator.
- Verify login flow still works.
- Verify image upload functionality.
- Check logs for Hilt/Room initialization.
