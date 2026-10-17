# Task 09 — SEC-01: Gate HTTP logging and add timeouts

**Wave 0 · Priority P0 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-02, [06-images-db-background.md](../../../review/06-images-db-background.md) (release logs full bodies)

## Problem
`di/NetworkModule.kt:31-38` installs `HttpLoggingInterceptor` at `Level.BODY` unconditionally on the shared `@Singleton` client, which is used for `registerApp`/`fetchAccessToken`/`uploadMedia` (`PixelfedRepository.kt:113-190, 207-216`). Signed release builds therefore write `client_secret` and the bearer token to logcat, and `BODY` buffers/stringifies the whole ~8 MB multipart body per upload. No connect/read/write/call timeout is set, so an 8 MB upload trips OkHttp's stock 10 s budget mid-flight (`PixelfedRepository.kt:247`).

## Scope
- `di/NetworkModule.kt`: set `level = if (BuildConfig.DEBUG) Level.BASIC else Level.NONE` and `redactHeader("Authorization")`.
- `di/NetworkModule.kt`: add `connectTimeout`/`readTimeout`/`writeTimeout`/`callTimeout` (e.g. 15 s / 60 s / 120 s / 180 s) and `retryOnConnectionFailure`.
- `app/build.gradle`: set `buildFeatures { buildConfig = true }` (currently only `compose = true`, `:62-64`) so `BuildConfig.DEBUG` is available in the module.
- `PixelfedRepository.kt:262`: drop the ad-hoc `withTimeoutOrNull(8_000L)` in favour of the client `callTimeout`, or document why it stays.

```kotlin
val builder = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)   // >8 MB multipart uploads
    .callTimeout(180, TimeUnit.SECONDS)
if (BuildConfig.DEBUG) {
    builder.addInterceptor(HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
        redactHeader("Authorization")
    })
}
return builder.build()
```

## Acceptance criteria
- [ ] Release logcat contains no `Authorization` header and no `client_secret`/`access_token` during login or upload.
- [ ] A >8 MB upload completes without a mid-upload `SocketTimeoutException`.
- [ ] Debug builds log method/URL/status but never bodies.

## Dependencies
- None.

## Verification
- `./gradlew :app:assembleCiProdDebug && adb logcat -c && <login+upload> && adb logcat | grep -iE 'authorization|client_secret|access_token'` (expect no matches)
