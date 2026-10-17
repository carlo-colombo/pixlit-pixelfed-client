# Task 47 — BUILD-06: Clean up ProGuard rules and keep line numbers

**Wave 3 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) medium-proguard

## Problem
`app/proguard-rules.pro:14-38` duplicates rules the libraries already ship — Retrofit 3.0.0,
OkHttp 5.5.0 and Gson 2.13.1 all contribute consumer rules (visible in the R8 configuration dump
`app/build/outputs/mapping/ciProdRelease/configuration.txt`). In particular `-keep class retrofit2.** { *; }`
(`:29-31`) blocks optimization/obfuscation of the whole package for no benefit, and blanket
`-dontwarn okhttp3.**`/`io.coil-kt.**` hides genuinely missing classes. Meanwhile the rules that would
make crash reports readable are missing: there is no `-keepattributes SourceFile,LineNumberTable` and
no `-renamesourcefileattribute SourceFile`, so a sideloaded release stack trace cannot be mapped
through `mapping.txt` — the only crash-triage path for this app.

## Scope
- Delete the redundant app rules (`app/proguard-rules.pro:14-38`) that ship with Retrofit/OkHttp/Gson.
- Add the trace-readability pair and the one suppression that actually does something:

```proguard
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-dontwarn io.coil-kt.**
```

- Verify the R8 output still assembles and that the mapping contains source attributes.

## Acceptance criteria
- [ ] No rule duplicates a library consumer rule; `configuration.txt` no longer carries the app's copies.
- [ ] A release stack trace is line-mappable via `mapping.txt`.
- [ ] `assembleCiProdRelease` (R8) succeeds.

## Dependencies
- None

## Verification
- `./gradlew :app:assembleCiProdRelease`
