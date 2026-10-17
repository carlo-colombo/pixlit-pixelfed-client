# Task 35 — SEC-06: Narrow OAuth scopes

**Wave 3 · Priority P2 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-04 (note)

## Problem
`registerApp` requests `read write follow` (`PixelfedRepository.kt:80`) and the authorize URL repeats `scope=read write follow` (`LoginScreen.kt:65-68`), which is more than an uploader needs. The `follow` grant is not used by any code path; requesting it widens the token's blast radius for no functional benefit and makes the consent screen worse than it has to be. The review's F-04 note recommends narrowing to `read write` unless DMs/following are intended.

## Scope
- Change the registration scope to `read write` in `PixelfedRepository.kt:80`.
- Change the authorize-URL `scope` parameter to `read write` in `LoginScreen.kt`.
- Keep the two call sites in sync (same literal); do not introduce new features that depend on `follow`.
- If an already-issued token with `follow` exists, its stored scope is unchanged — note that in the PR rather than forcing re-auth.

## Acceptance criteria
- [ ] `grep -rn "read write follow" app/src/main` returns no matches.
- [ ] Both the registration and authorize call sites request exactly `read write`.
- [ ] Login completes and an upload succeeds with the narrowed scope.

## Dependencies
- SEC-03 (both touch the authorize URL / OAuth request contract)

## Verification
- `grep -rn "read write" app/src/main`
- `./gradlew :app:assembleLocalDevDebug` plus a manual login + upload run.

```kotlin
api.registerApp(
    clientName = "Pixlit Android Client",
    redirectUris = redirectUri,
    scopes = "read write",
    website = "https://pixelfed.org",
)
```
