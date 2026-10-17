# Task 10 — SEC-02: Encrypt token storage and exclude it from backup

**Wave 0 · Priority P0 · Size M · Status: Todo.**
Backlog: [docs/backlog.md](../../../backlog.md) · Evidence: [04-data-network.md](../../../review/04-data-network.md) F-01, [02-gradle-build-config.md](../../../review/02-gradle-build-config.md) medium-allowBackup

## Problem
`data/auth/TokenManager.kt:11,47-61` stores the OAuth access token and `client_secret` verbatim in plaintext `SharedPreferences("pixlit_prefs")`, and `AndroidManifest.xml:14` sets `allowBackup="true"` with no `dataExtractionRules`/`fullBackupContent`. Auto Backup therefore copies a full `read write follow` bearer token to the user's Google Drive and device-to-device transfer, and no `res/xml/` backup rules exist.

## Scope
- Add `androidx.security:security-crypto`; back the credential store with `EncryptedSharedPreferences.create(...)` (`MasterKey` with `AES256_GCM`, `PrefKeyEncryptionScheme.AES256_SIV`, `PrefValueEncryptionScheme.AES256_GCM`). Keep only the token/secret behind it; cosmetic prefs stay plain.
- Add `res/xml/data_extraction_rules.xml` excluding `sharedpref` `pixlit_prefs.xml` from `<cloud-backup>` and `<device-transfer>`; reference it (plus `fullBackupContent`) from `AndroidManifest.xml`.
- Add `PrefsBackupExclusionTest` asserting the rule shape, mirroring neurhome's backup-rule test.

```xml
<data-extraction-rules>
  <cloud-backup><exclude domain="sharedpref" path="pixlit_prefs.xml" /></cloud-backup>
  <device-transfer><exclude domain="sharedpref" path="pixlit_prefs.xml" /></device-transfer>
</data-extraction-rules>
```

## Acceptance criteria
- [ ] The credential preferences file is unreadable as plaintext on device.
- [ ] `data_extraction_rules.xml` excludes the credential file from both cloud backup and device transfer.
- [ ] A test asserts the exclusion entries.

## Dependencies
- None.

## Verification
- `./gradlew :app:testLocalDevDebugUnitTest` (includes `PrefsBackupExclusionTest`)
