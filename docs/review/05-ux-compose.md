## Review

Domain: UX & Compose UI quality across Pixlit. Read: `MainActivity.kt`, `MainViewModel.kt`, `ui/upload/UploadScreen.kt` + all of `ui/upload/components/`, `ui/auth/LoginScreen.kt`, `ui/social/SocialScreen.kt` + `SocialViewModel.kt`, `ui/theme/{Theme,Color,Type}.kt`, `res/values/{strings,themes}.xml`, `src/dev/` + `src/prod/`, `AndroidManifest.xml`, `app/build.gradle`. Blueprint cross-checks against `/home/carlo/projects/neurhome` (`ui/theme/Theme.kt`, `MainActivity.kt`, `AGENTS.md`).

### What is already good

- Upload state machine is a clean UDF ViewModel (`UploadViewModel.kt`), screens are mostly stateless with callbacks; `UploadScreen` correctly hoists all state (no ViewModel reads inside leaf components).
- Recomposition hygiene on lists is right where it matters: `itemsIndexed(statuses, key = { _, status -> status.getIdString() ?: "post-$index" })` (`RecentPostsSection.kt:53`).
- Material 3 is used idiomatically for controls (`FilterChip`, `SuggestionChip`, `CardDefaults`, `ButtonDefaults.outlinedButtonColors(contentColor = error)`); light/dark follows the system (`Theme.kt:47`, `isSystemInDarkTheme()`), which is actually better than the blueprint, whose `Neurhome3Theme` hard-codes `darkTheme = true`.
- Error text on login is selectable and scrollable (`LoginScreen.kt:162-171`) — good for long OAuth failures.

---

### [SEVERITY: high] Login button hangs on a spinner forever when app registration fails

**Evidence** — `LoginScreen.kt:181-185`
```kotlin
isLoading = true
errorMessage = null
onLoginClick(instanceUrl, manualClientId, manualClientSecret)
...
enabled = !isLoading,
```
and `LoginScreen.kt:52-56`
```kotlin
if (result.isSuccess) { clientIdToUse = result.getOrNull()?.first }
else {
    clientIdToUse = null
    // Handle error message passing back to LoginContent if needed
}
```
`isLoading` is set `true` and is never reset anywhere in `LoginContent`. When `repository.registerApp(...)` fails (bad URL, no network, non-Pixelfed host) `clientIdToUse` is null, no Custom Tab opens, `errorMessage` stays null, and the CTA spins forever with no way out but killing the app. The same dead-end happens if the user closes the Custom Tab without consenting.

**Impact** — The single onboarding path is unrecoverable on any failure; the user cannot retry or read what went wrong. This is the highest user-visible defect on the whole app.

**Recommendation** — Hoist loading/error into the callback contract and always resolve it:
```kotlin
onLoginClick = { instanceUrl, id, secret ->
    LoginContent::class // concept: pass a result callback
    scope.launch {
        val result = if (manual...) ... else repository.registerApp(formattedUrl, redirectUri)
        result.fold(
            onSuccess = { /* launch Custom Tab */ },
            onFailure = { onLoginError(it.localizedMessage ?: "Could not reach $formattedUrl") }
        )
    }
}
```
and in `LoginContent` add `isLoading = false` on every non-success outcome, or lift `isLoading`/`errorMessage` into a small `LoginUiState` holder so the failure branch can write to it.

**Blueprint reference** — `neurhome` keeps failure logic in the ViewModel and screens observe a single state object; it has no equivalent fire-and-forget lambda that can strand a spinner.

---

### [SEVERITY: high] Shipping internals: "Debug" tab and "Simulate Art Show Notification" ship in prod

**Evidence** — `UploadScreen.kt:259`
```kotlin
val tabs = listOf("Upload", "Social", "Last", "Debug", "Settings")
```
`UploadScreen.kt:684` (`} else if (selectedTab == 3) { // Debug Tab`) and `UploadScreen.kt:736-741`
```kotlin
Button(onClick = onSimulateNotification, modifier = Modifier.fillMaxWidth()) {
    Text("Simulate Art Show Notification")
}
```
wired unconditionally in `MainActivity.kt:97`:
```kotlin
onSimulateNotification = { artShowNotifier.notify("#Minimal", false) }
```
Only `DevBanner` is flavor-gated (`src/dev/DevBanner.kt` vs `src/prod/DevBanner.kt` → `= Unit`); the Debug tab is not.

**Impact** — Production users see raw status JSON, cache counts and a "Simulate notification" action that fires a real notification with a hardcoded `#Minimal` theme. It undermines the app label/flavor split the build already sets up.

**Recommendation** — Gate the tab and its callbacks behind a `BuildConfig` flag, e.g. add `buildConfigField("boolean", "DEBUG_TOOLS", "true")` to the dev flavor and `false` to prod, then:
```kotlin
val tabs = buildList {
    add("Upload"); add("Social"); add("Recent")
    if (BuildConfig.DEBUG_TOOLS) add("Debug")
    add("Settings")
}
```
and guard `onSimulateNotification` at the call site in `MainActivity`.

**Blueprint reference** — `neurhome` confines debug-only behaviour to the `debug`/`dev` source sets (its `DebugLogcatCaptureProvider` is declared only in `src/debug/AndroidManifest.xml`).

---

### [SEVERITY: medium] No confirmation (or undo) before destructive actions

**Evidence** — Logout is a bare app-bar text button: `UploadScreen.kt:327-329`
```kotlin
TextButton(onClick = onLogout) { Text("Logout") }
```
`MainViewModel.logout()` (`MainViewModel.kt:88-92`) calls `tokenManager.clear()`, which wipes the access token, instance URL and cached tags. Photo removal is likewise unguarded: `UploadScreen.kt:384-390`
```kotlin
OutlinedButton(
    onClick = { onRemoveImage(currentPage) },
    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
) { Icon(Icons.Default.Delete, "Remove Photo"); Text("Remove") }
```
The only `AlertDialog` in the entire app is the collection-create dialog (`CollectionSelectionSection.kt:94`).

**Impact** — A misplaced tap during upload clears the session (full re-login + re-register OAuth client); removing the wrong photo silently drops it from the post with no undo.

**Recommendation** — Add a confirm dialog for logout (destructive) and a `Snackbar` with "Undo" for photo removal (keep the removed `Uri` so it can be re-inserted at the same index):
```kotlin
if (showLogoutConfirm) AlertDialog(
  onDismissRequest = { showLogoutConfirm = false },
  title = { Text("Log out?") },
  text = { Text("You'll need to sign in again.") },
  confirmButton = { Button(onClick = { showLogoutConfirm = false; onLogout() }) { Text("Log out") } },
  dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") } }
)
```

---

### [SEVERITY: medium] No IME handling; keyboard covers the primary Upload action

**Evidence** — the caption field is forced to `height(240.dp)` and relies on a timed scroll hack: `UploadScreen.kt:269-276` and `UploadScreen.kt:486-490`
```kotlin
LaunchedEffect(captionFocused) {
    if (captionFocused) { kotlinx.coroutines.delay(100); captionBringIntoViewRequester.bringIntoView() }
}
...
modifier = Modifier.fillMaxWidth().height(240.dp).bringIntoViewRequester(captionBringIntoViewRequester)
```
The bottom action bar is an overlay inside the scaffold with no IME padding (`UploadScreen.kt:618-655`), and the scaffold applies `Modifier.systemBarsPadding()` (`UploadScreen.kt:321`) which does not include the IME.

**Impact** — With the keyboard open the fixed "Upload N Photos" button and the character counters are covered; the user must dismiss the keyboard to submit. The `delay(100)` is racy across devices/keyboards, and `height(240.dp)` clips with large font scale.

**Recommendation** — Use `Scaffold(contentWindowInsets = WindowInsets.safeDrawing)` and a `Modifier.imePadding()` (or `WindowInsets.ime`) on the bottom bar, drop the fixed height in favour of `heightIn(min = 120.dp)`, and remove the `delay(100)` hack:
```kotlin
Surface(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding(), ...)
```

---

### [SEVERITY: medium] System back exits the app from any tab instead of returning to Upload

**Evidence** — tab selection is local state with no back handling: `UploadScreen.kt:258`
```kotlin
var selectedTab by remember { mutableStateOf(0) }
```
There is no `BackHandler` anywhere in the module (grep for `BackHandler` returns nothing).

**Impact** — From Social/Recent/Debug/Settings, pressing back closes the Activity; users expect it to return to the previous tab, and the surfaced tab state is also lost on process death/rotation.

**Recommendation**
```kotlin
BackHandler(enabled = selectedTab != 0) { selectedTab = 0 }
```

**Blueprint reference** — `neurhome` drives screens through Navigation Compose (`NavHost` in `MainActivity.kt`), so its back stack is correct by construction; Pixlit is a single-screen tab host and needs the explicit handler.

---

### [SEVERITY: medium] Accessibility: the resize row is a double TalkBack target

**Evidence** — `UploadScreen.kt:424-441`
```kotlin
Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .clickable { onResizeToggled(!resizeTo8Mb) }.padding(vertical = 4.dp)) {
    Checkbox(checked = resizeTo8Mb, onCheckedChange = { onResizeToggled(it) })
    Text("Resize down to 8MB", ...)
}
```
Both the `Row` (`clickable`) and the inner `Checkbox` are independently actionable, but the row carries no `Role.Checkbox`/toggle semantics, so TalkBack announces two separate targets ("Resize down to 8MB" and an unlabeled checkbox) with no checked state on the row.

**Recommendation** — Make the row the single toggle and clear the child:
```kotlin
Row(modifier = Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .toggleable(value = resizeTo8Mb, role = Role.Checkbox, onValueChange = onResizeToggled)
        .padding(vertical = 4.dp)) {
    Checkbox(checked = resizeTo8Mb, onCheckedChange = null)
    Text("Resize down to 8MB")
}
```

---

### [SEVERITY: medium] Accessibility: the "Advanced credentials" disclosure has no role or state

**Evidence** — `LoginScreen.kt:122-133`
```kotlin
Row(verticalAlignment = ..., modifier = Modifier.fillMaxWidth()
        .clickable { showManualCredentials = !showManualCredentials }) {
    Text(text = if (showManualCredentials) "▲ Hide manual API credentials"
               else "▼ Advanced: Enter Client ID & Secret", ...)
}
```
It is rendered as a clickable `Text` whose only affordance is a `▲`/`▼` glyph; TalkBack reads the glyphs and gives no expanded/collapsed state and no button role.

**Recommendation** — Use a real control and expose state:
```kotlin
TextButton(onClick = { showManualCredentials = !showManualCredentials }) { Text("Advanced: enter client ID & secret") }
// or Modifier.semantics { role = Role.Switch; stateDescription = if (show) "Expanded" else "Collapsed" }
```

---

### [SEVERITY: medium] Fixed pixel heights break with large font / dynamic type

**Evidence** — `RecentPostsSection.kt:84` `Card(modifier = Modifier.fillMaxWidth().height(420.dp))` combined with `RecentPostsSection.kt:118-122` `maxLines = 3`; `UploadScreen.kt:487` `.height(240.dp)` on the caption field; `ImagePagerSection.kt:37` `.height(260.dp)`.

**Impact** — At 1.5–2.0× font scale (accessibility setting) the recent-post card's fixed 420 dp box clips the caption/actions, and the caption editor shows fewer lines than intended. This is a direct TalkBack/large-font regression, not just polish.

**Recommendation** — Replace fixed `height(...)` with `heightIn(...)`/intrinsic sizing and let text flow (`maxLines = Int.MAX_VALUE` or drop the cap); keep image area fixed but make the surrounding card wrap content.

---

### [SEVERITY: medium] Form stays editable while an upload is in flight

**Evidence** — Only the submit button is gated: `UploadScreen.kt:639` `enabled = !isUploading && selectedImageUris.isNotEmpty()`, while the gallery button (`UploadScreen.kt:445-446`) is gated only on photo count and the checkbox/removal controls are always enabled.

**Impact** — During an upload the user can add/remove/reorder photos; the success branch then clears `selectedImageUris`, discarding images the user added mid-flight, and the "N Photos" label advertises a count that no longer matches what was uploaded.

**Recommendation** — Disable the whole form while `isUploading` (gallery `enabled = !isUploading && size < maxPhotos`, shift/remove `enabled = !isUploading && ...`, resize row `enabled = !isUploading`).

---

### [SEVERITY: medium] i18n not viable: `strings.xml` is empty and ~all UI copy is hardcoded

**Evidence** — `app/src/main/res/values/strings.xml` contains only `<resources></resources>`; only two `R.string` uses exist in the whole app (`MainViewModel.kt:66`, `LoginScreen.kt:31`, both `redirect_uri`). Literal strings are inlined everywhere, e.g. `LoginScreen.kt:104` `Text("Connect to Pixlit")`, `UploadScreen.kt:323` `Text("Upload to Pixlit")`, `UploadScreen.kt:452` `"Select Photos from Gallery (up to 6)"`, `SocialScreen.kt:57` `"Robyn's pinned art challenge"`, plus every `contentDescription`.

**Impact** — No localization path, and copy changes require touching composables; the app also mixes user-facing copy with logic-adjacent strings (error text built in `MainViewModel` / `UploadViewModel`).

**Recommendation** — Extract user-visible copy into `strings.xml` and read via `stringResource(...)`; start with the screens users hit first (Login, Upload header, buttons, error/empty states) rather than a big-bang migration.

**Blueprint reference** — `neurhome` is also largely hardcoded (its `strings.xml` has only `app_name` + `suffix`), so this is consistent with the current convention — but it is still the reason neither app can be localized. Worth fixing here if Pixlit is intended to be shared.

---

### [SEVERITY: medium] Theme has no night variant → white flash in dark mode

**Evidence** — `app/src/main/res/values/themes.xml:3`
```xml
<style name="Theme.PixelfedClient" parent="android:Theme.Material.Light.NoActionBar" />
```
There is no `values-night/themes.xml` (the only themes file in the tree), and the app is edge-to-edge (`MainActivity.kt:45` `enableEdgeToEdge()`).

**Impact** — Dark-mode users get a light window background/splash before Compose draws, and the system nav/status bar transitions visibly. Compose handles dark mode (`Theme.kt:47`) but the XML window does not.

**Recommendation** — Parent off a DayNight theme and add the night override:
```xml
<style name="Theme.PixelfedClient" parent="Theme.Material3.DayNight.NoActionBar">
  <item name="android:windowBackground">@android:color/transparent</item>
</style>
```
plus `values-night/themes.xml`, or adopt `androidx.core:core-splashscreen` with `postSplashScreenTheme` for a matched splash.

---

### [SEVERITY: medium] Over-broad media permissions with no matching use

**Evidence** — `AndroidManifest.xml:9-10`
```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />
```
but the picker is `ActivityResultContracts.GetMultipleContents()` (`UploadScreen.kt:295-297`, i.e. `ACTION_GET_CONTENT`) and all reads go through the granted URI via `contentResolver.openInputStream(...)` (`ImageUtils.kt:93,113,183,240,368`); no `MediaStore` query is performed.

**Impact** — Declares broad photo-library access the app never uses, which is a privacy/Play-review liability and can surface unnecessary permission UI on some OEMs.

**Recommendation** — Switch to the photo picker (`ActivityResultContracts.PickMultipleVisualMedia()`), which needs no storage permission, and delete both `READ_MEDIA_*` permissions; keep `ACCESS_MEDIA_LOCATION` only if you truly need original GPS EXIF.

---

### [SEVERITY: medium] Notification permission is requested on cold start, before it is needed, with no rationale

**Evidence** — `MainActivity.kt:47-50`
```kotlin
if (SDK_INT >= 33 && checkSelfPermission(POST_NOTIFICATIONS) != PERMISSION_GRANTED) {
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
}
```
This fires before login and before the reminder feature is reachable, uses the legacy `requestPermissions(int)` API, and a denial is never surfaced or re-requestable from the Settings tab. `ACCESS_MEDIA_LOCATION` is requested the same way at `MainActivity.kt:64-68`.

**Impact** — Permission dialog with no context is the top cause of permanent denial; the reminder feature then silently never notifies.

**Recommendation** — Move to `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` triggered from the first action that needs it (enabling a reminder), show a short rationale, and render an "Notifications disabled — enable in system settings" state in the Settings tab when denied.

---

### [SEVERITY: medium] Upload feedback is inline text with no snackbar, no auto-dismiss, no undo

**Evidence** — `UploadScreen.kt:626-635`
```kotlin
if (statusMessage != null) {
    Text(text = statusMessage,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, ...)
}
```
There is no `SnackbarHost` anywhere in the app (grep for `Snackbar` is empty); `statusMessage` persists until the next action (`UploadViewModel.addImages` clears it, `UploadScreen.kt` success branch does not auto-clear).

**Impact** — Success/failure is easy to miss (small text above the button), the success copy lingers indefinitely, and there is no way to undo the post-upload form reset.

**Recommendation** — Add `snackbarHost = { SnackbarHost(hostState) }` to the scaffold, emit success/error through it (`SnackbarHostState.showSnackbar(message, actionLabel = "Undo")`), and keep the inline text only for persistent, non-transient state.

---

### [SEVERITY: medium] `UploadContent` is a ~600-line composable containing five screens inline

**Evidence** — `UploadScreen.kt:255-790`: one function with 40+ parameters that owns the tab row and inlines Upload (`330-660`), Social (`661-671`), Recent (`672-683`), Debug (`684-786`) and Settings (`787`) bodies; plus two private helpers at `790-812`.

**Impact** — Every tab change recomposes from one root, the file is hard to review, and the Social/Recent/Debug/Settings branches cannot be previewed or tested independently.

**Recommendation** — Extract `UploadTabContent`, `RecentPostsTabContent`, `DebugTabContent`, `SettingsTabContent` into their own files/functions that take only what they need (they already take plain state), leaving `UploadContent` as the scaffold + tab host.

---

### [SEVERITY: low] `RecentPostCard` recomputes HTML parsing and regex on every recomposition

**Evidence** — `RecentPostsSection.kt:71-82`
```kotlin
val rawText = ... ; val text = Html.fromHtml(rawText, Html.FROM_HTML_MODE_LEGACY).toString().trim()
val tags = ... ; val missingTags = tags.filterNot { tag -> Regex("(?i)(?<!\\w)${Regex.escape(tag)}(?!\\w)").containsMatchIn(text) }
```
These run in the composable body (not `remember`), and `RecentPostCard` is re-entered whenever the parent list recomposes (pager page change, load-more).

**Recommendation** — `val text = remember(status) { ... }` and `remember(text, status.tags) { ... }` for `copyText`.

---

### [SEVERITY: low] Location search flashes "Use … as location name" during the debounce window

**Evidence** — `UploadViewModel.kt:187-203` sets `_placeSearchQuery` immediately but `_isSearchingPlaces.value = true` only after `delay(300)`; `PositionSelectionSection.kt:180-186` renders the fallback button whenever `placeSearchQuery.isNotBlank() && !isSearchingPlaces`.

**Impact** — While typing a city the "Use \"…\" as location name" action appears for 300 ms, then disappears — a flickering affordance that invites mis-taps.

**Recommendation** — Set `_isSearchingPlaces.value = true` synchronously when launching the debounce, before the `delay`.

---

### [SEVERITY: low] Thumbnail row has no item keys, so reorder reuse is fragile

**Evidence** — `OrderPreviewList.kt:48` `itemsIndexed(selectedImageUris) { index, uri -> ... }` (no `key`), while shift-left/right mutates the same list (`UploadViewModel.kt:311-335`).

**Recommendation** — `itemsIndexed(selectedImageUris, key = { _, uri -> uri })`; also consider raising the thumbnail touch target (`screenWidthDp * 0.15f` ≈ 54 dp on a 360 dp screen, i.e. barely above the 48 dp minimum).

---

### [SEVERITY: low] Dev builds double-apply the status-bar inset

**Evidence** — `src/dev/DevBanner.kt:22-27` applies `.statusBarsPadding()` to the banner, and `UploadScreen.kt:321` applies `Modifier.systemBarsPadding()` to the scaffold; the banner and the scaffold are siblings in the `MainActivity` `Column` (`MainActivity.kt:72-74`), so nothing consumes the top inset for the sibling.

**Impact** — In `dev` builds the "Upload to Pixlit" header sits ~one status-bar height too low; prod is unaffected because `DevBanner` is `Unit`.

**Recommendation** — Own insets once: drop `statusBarsPadding()` from the banner and let the scaffold (or a top-level `Modifier.safeDrawingPadding()` in `MainActivity`) handle it.

---

### [SEVERITY: low] Hashtag popup is positioned from an assumed 8 dp glyph width

**Evidence** — `UploadScreen.kt:461-469`
```kotlin
val cursorX = with(density) { (16.dp.toPx() + lineCharacterCount * 8.dp.toPx()).toInt() }
```
feeding `CaptionSuggestionPositionProvider` (`UploadScreen.kt:59-79`).

**Impact** — Mispositioned for non-monospace glyphs, wide characters, large fonts or indentation — the suggestion list can appear detached from the caret.

**Recommendation** — Accept the drift for now but drop the magic `8.dp` in favour of measuring the text layout (`TextLayoutResult.getHorizontalPosition`), or anchor the popup to the field edge as a fallback.

---

### [SEVERITY: low] Preview coverage is uneven; no `@PreviewParameter` samples; no `testTag`s

**Evidence** — `@Preview` exists only in `Theme.kt:72`, `LoginScreen.kt:200/208`, `ImagePagerSection.kt:87/100`, `MetadataDisplay.kt:70/90`, `OrderPreviewList.kt:85`, `TagSelectionCloud.kt:77/97`, `UploadScreen.kt:821`. Missing: `SocialScreen`, `RecentPostsSection`, `CollectionSelectionSection`, `PositionSelectionSection`, `MetadataDisplay` empty state, `UploadContent` other tabs, `SettingsContent`, and `MainActivity`'s auth-processing/logged-out states. No `testTag`/`Modifier.semantics { testTagsAsResourceId = true }` anywhere (grep empty).

**Recommendation** — Add previews for the four components above (they are already stateless), add `@PreviewParameter` sample providers for `recentStatuses`/`collections`/`placeSearchResults`, and set `testTagsAsResourceId = true` on the root `Box` in `MainActivity` so UI automation can find nodes.

**Blueprint reference** — `neurhome` has the same component granularity but uses `@PreviewParameter(SampleEventsProvider::class)` (`ui/components/Calendar.kt:53`) and root `testTagsAsResourceId` (`MainActivity.kt`), which Pixlit lacks.

---

### [SEVERITY: low] AlertDialog for collection creation swallows failures

**Evidence** — `CollectionSelectionSection.kt:126-133` closes the dialog and clears fields as soon as `onCreateCollection` is called; `UploadViewModel.createAndSelectCollection` (`UploadViewModel.kt:272-290`) only `Log.e(...)` on failure.

**Impact** — A failed create closes the dialog with no new chip and no message; the user assumes the tap was lost.

**Recommendation** — Surface the failure (snackbar) from the ViewModel and only dismiss on success.

---

### [SEVERITY: low] Tab naming and Logout placement hurt discoverability

**Evidence** — `UploadScreen.kt:259` → `"Last"` is the recent-posts tab; `UploadScreen.kt:326-329` puts a prominent `Logout` text button in the primary app bar.

**Recommendation** — Rename "Last" → "Recent"; move Logout into the Settings tab (behind the confirmation from the destructive-actions finding) so the primary header stays about the task.

---

### [SEVERITY: low] Theming/spec-fidelity notes (report-only)

- `Theme.kt:46-53` defaults `dynamicColor = true`, so on API 34+ (minSdk is 34) the `Purple/Pink` palette in `Color.kt` is dead code and the brand never shows. Either intentionally embrace Material You (document it) or default `dynamicColor = false`. `neurhome` makes the same choice but additionally pins `primary = onSecondaryContainer`.
- `Theme.kt:63-67` sets only `isAppearanceLightStatusBars`; with `enableEdgeToEdge()` (`MainActivity.kt:45`) nav-bar icon appearance is left to the default scrim. Consider `isAppearanceLightNavigationBars = !darkTheme` for consistency.
- `Type.kt:9-17` overrides only `bodyLarge`; the rest of the type scale is stock M3 — harmless, but a `headlineMedium` override would remove the default typography drift visible in `ThemePreview`.
- Notification small icon is the adaptive foreground (`ArtShowNotifier.kt:37` `setSmallIcon(R.drawable.ic_launcher_foreground)`), which renders as a coloured blob/tiny crop in the status bar; add a dedicated monochrome vector.
- `AndroidManifest.xml:22-38` has no `android:enableOnBackInvokedCallback="true"`, so targetSdk 35 gets no predictive-back animation.
- `SocialScreen.kt:91-93` shows the raw error string (`Text(error!!, color = ...)`) with no title or retry near it (retry is a `Refresh` button at the bottom of the list) — a title + inline retry would be clearer.

---

### Top 5 actions

1. **Fix the login dead-end** — reset `isLoading` and surface the `registerApp` failure in `LoginContent` (`LoginScreen.kt:52-56, 181-185`). Single point of failure for onboarding. *(high)*
2. **Gate the Debug tab and "Simulate notification" behind `BuildConfig.DEBUG_TOOLS`** (`UploadScreen.kt:259, 684, 736-741`; `MainActivity.kt:97`) so internals don't ship in prod. *(high)*
3. **Add confirmation/undo for destructive actions** — logout dialog and remove-photo Undo snackbar, with a `SnackbarHost` for upload success/failure (`UploadScreen.kt:327-329, 384-390, 626-635`). *(medium)*
4. **Make the layout keyboard- and large-font-safe** — `imePadding()` on the bottom bar, drop `delay(100)`/fixed `240.dp`, replace `height(420.dp)`/`maxLines=3` with intrinsic sizing (`UploadScreen.kt:269-276, 424-441, 487, 618-655`; `RecentPostsSection.kt:84, 118-122`). *(medium)*
5. **Do the cheap a11y + i18n + insets sweep** — `toggleable`/`Role.Checkbox` on the resize row, real button role for the advanced-credentials toggle, `BackHandler` for tabs, `values-night` theme, drop unused `READ_MEDIA_*`, and start moving first-run copy into `strings.xml`. *(medium)*