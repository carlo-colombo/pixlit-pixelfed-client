# Task 48 — BUILD-08: Give each variant its own OAuth redirect scheme

**Wave 3 · Priority P3 · Size S · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) medium-oauth-scheme

## Problem
Two installable variants share one OAuth redirect scheme. `local` sets no scheme override
(`app/build.gradle:29-33`) while `dev` uses `pixlit-dev` (`:37-42`) and `prod` inherits `pixlit`
(`:24`), so `localDev`/`ciDev` both register `pixlit-dev://oauth` and `localProd`/`ciProd` both
register `pixlit://oauth` (`src/dev/res/values/strings.xml:2`, `src/prod/res/values/strings.xml:2`).
Because the distribution dimension exists precisely so two builds can coexist (`README.md:77-79`),
installing a CI artifact next to a local build makes the callback ambiguous — Android shows a chooser
or hands the code to the wrong app, which then fails token exchange with a redirect mismatch.

## Scope
- Give `local` its own scheme (`pixlit-local` -> `manifestPlaceholders.appAuthRedirectScheme`) plus a
  matching `app/src/local/res/values/strings.xml` `redirect_uri`, so the placeholder and the resource
  cannot drift.
- Better: derive both from `applicationId` (e.g. `manifestPlaceholders… = "${applicationId}.oauth"` and
  a matching `resValue`) to remove the hand-maintained pair.
- If BUILD-02 collapses the matrix, derive the scheme from `applicationId` and the problem disappears.

## Acceptance criteria
- [ ] Every installable variant registers a distinct redirect scheme (checked in the merged manifest).
- [ ] The manifest placeholder and the `redirect_uri` string resource agree per variant.
- [ ] Two coexisting installs never race for the same callback.

## Dependencies
- None

## Verification
- `./gradlew :app:assembleLocalDevDebug :app:assembleCiDevDebug` then inspect the merged manifests for distinct `appAuthRedirectScheme`
