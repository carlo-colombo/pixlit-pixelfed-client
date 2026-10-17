## Review

Read-only review of image processing, persistence, background work, and app entry points. I read `ImageUtils.kt` + `ImageUtilsTest.kt`, all of `data/reminder/*`, `data/db/*`, `MainActivity.kt`, `MainViewModel.kt`, `PixlitApplication.kt`, `AndroidManifest.xml`, plus the callers/seams that make these reachable (`UploadViewModel.kt`, `PixelfedRepository.kt`, `NetworkModule.kt`, `TokenManager.kt`, `UploadScreen.kt`, `app/build.gradle`, `docs/bluesky-art-show-reminders.md`) and the blueprint repo `/home/carlo/projects/neurhome` for conventions.

I could not execute Android/Robolectric tests in this session. Recommended verification command for a supervisor: `./gradlew testLocalDevDebugUnitTest` (or `:app:testCiDevDebugUnitTest`).

Findings are ordered by risk. Evidence is file:line with short quotes.

---

### [SEVERITY: critical] Resize path decodes the full-resolution bitmap; `catch (Exception)` cannot catch the resulting `OutOfMemoryError`
**Evidence**
- `app/src/main/java/ovh/litapp/pixlit/utils/ImageUtils.kt:313-317` — `var scale = 0.95; val ratio = Math.sqrt(maxSizeBytes / originalMetadata.sizeBytes); if (ratio < 0.95) scale = ratio`.
- `ImageUtils.kt:325-331` — decode then `Bitmap.createScaledBitmap(bitmap, currentWidth, currentHeight, true)`; both bitmaps are resident at once.
- `ImageUtils.kt:389-406` (`calculateInSampleSize`) — the loop only doubles while `halfHeight/inSampleSize >= reqHeight && halfWidth/inSampleSize >= reqWidth`. With `req ≈ 0.58 × original` (the 8 MB case: √(8/24) = 0.577) even `inSampleSize = 1` fails that test, so the **full-resolution** image is decoded.
- `ImageUtils.kt:378-386` — `catch (e: Exception) { …; null }`; `OutOfMemoryError` is an `Error`, so it escapes and crashes the process.
- `ImageUtils.kt:375-376`, `UploadViewModel.kt:258-261`, `PixelfedRepository.kt:194-196` — this is the main flow whenever the 8 MB toggle is on and a photo is > 8 MB.

**Impact** — A 48 MP camera photo (8000×6000, e.g. 24 MB JPEG) requests 4616×3462 → `inSampleSize = 1` → 8000×6000×4 B ≈ **192 MB** bitmap, plus a 64 MB scaled copy ≈ **256 MB peak** before the encoded buffer is counted. On a typical 256 MB heap that is an immediate OOM crash of the whole app while preparing/uploading exactly the photos the app exists to upload (worst case on 25–200 MP sensors). It also explains why this path has no test: a Robolectric test with a 12 MP source would already need a large heap.

**Recommendation** — `minSdk` 34 means `ImageDecoder` is always available; use it with an exact target size (single pass, bounded memory, EXIF orientation applied by the decoder) and delete the manual sample+rotate dance:

```kotlin
private fun decodeToTarget(context: Context, uri: Uri, w: Int, h: Int): Bitmap? =
    context.contentResolver.openInputStream(uri)?.use { input ->
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(input)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE   // required before compress()
            decoder.setTargetSize(w, h)                            // no >2x overshoot
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    }
```
If `BitmapFactory` is kept, (a) allow the sample size to overshoot (bound decoded pixels, e.g. ≤ 12 MP, and downscale from there), (b) set `inPreferredConfig = Bitmap.Config.RGB_565` for opaque JPEG sources, and (c) add a defensive `catch (e: OutOfMemoryError)` that returns null and surfaces "Image too large to process" instead of crashing. Add a regression test asserting an oversized source yields `inSampleSize >= 2` / a bounded decoded pixel count.

**Blueprint reference** — neurhome bounds every image decode by size and goes through the platform decoder/thumbnail path (`app/src/main/java/ovh/litapp/neurhome3/data/dao/ContactsDAO.kt:97-100`, `contentResolver.loadThumbnail(uri, CONTACT_PHOTO_SIZE, null)`, commented at `app/src/test/java/ovh/litapp/neurhome3/data/dao/ContactsDaoTest.kt:41-43` as "goes through `ImageDecoder`"), and caches the bounded result in a small map.

---

### [SEVERITY: high] Intents are re-handled on every `Activity` creation → duplicate shared photos and a repeated OAuth exchange
**Evidence**
- `app/src/main/java/ovh/litapp/pixlit/MainActivity.kt:53` — `viewModel.handleIntent(intent)` is called from `onCreate` (which also runs on every configuration change) and again in `onNewIntent` (`MainActivity.kt:102-107`, with `setIntent(intent)`, so `getIntent()` returns the callback/share intent afterwards).
- `MainViewModel.kt:44-59` — `_sharedImageUris.value = listOf(uri)` / `uris.filterNotNull()`, reset only by an explicit consume (`MainViewModel.kt:101`).
- `UploadScreen.kt:131-135` — `LaunchedEffect(sharedImageUris) { if (isNotEmpty()) { viewModel.addImages(...); onSharedImageUrisConsumed() } }`.
- `UploadViewModel.kt:382-385` — `addImages` concatenates: `(_selectedImageUris.value + uris).take(6)`.
- `MainViewModel.kt:76-99` — the OAuth branch re-runs `repository.exchangeCodeForToken(code, redirectUri)` for a code that was already spent, and sets `_isAuthProcessing = true`; `MainActivity.kt:77-84` renders the full-screen spinner whenever `isAuthProcessing` is true.

**Impact** — Share one photo → rotate the device (or any config change) → `onCreate` re-delivers the same `EXTRA_STREAM` → the photo is appended **again** (`Uri` list now contains it twice), consuming two of the six slots and uploading the same file twice in one post. Same root cause after login: rotating re-issues an OAuth token exchange with a consumed code → unnecessary network call, transient full-screen spinner (`isAuthProcessing` wins over `isLoggedIn`) and a stale `authError`. The `prefill`/`theme` extras happen to be idempotent (`UploadViewModel.prefillArtShowTags` de-dupes via regex, `UploadViewModel.kt:521-534`), which is why only the image and OAuth paths break.

**Recommendation** — treat intent payloads as one-shot: consume the extras after handling (e.g. `intent.removeExtra(Intent.EXTRA_STREAM)`, `intent.data = null` once the code is processed, or a `handledIntentId` in the ViewModel) and only do initial delivery work when `savedInstanceState == null`:

```kotlin
if (savedInstanceState == null) {
    viewModel.handleIntent(intent)
    intent.removeExtra(Intent.EXTRA_STREAM)   // survives config changes with the Activity
    intent.data = null
}
```
Add a test that calls `handleIntent` twice with the same share intent and asserts no duplicate (`MainViewModelTest.kt:73-105` already has the fixtures).

**Blueprint reference** — neurhome's `MainActivity` keeps no intent state and delegates to per-screen ViewModels (`app/src/main/java/ovh/litapp/neurhome3/MainActivity.kt:43-80`), avoiding activity-recreation re-entry.

---

### [SEVERITY: high] Release builds log full HTTP bodies: OAuth secret/access token in logcat, whole multipart body copied and stringified per upload
**Evidence** — `app/src/main/java/ovh/litapp/pixlit/di/NetworkModule.kt:32-42`:
```kotlin
val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
return OkHttpClient.Builder().addInterceptor(logging).build()
```
No flavor/debug gate exists (`app/src/dev/.../DevBanner.kt` is the only source-set difference; `app/build.gradle:73-100` has no `debugImplementation`-only client). This client is used for the Pixelfed instance, including `registerApp`/`fetchAccessToken` (`PixelfedRepository.kt:113-190`, which puts `client_secret` in a form body and receives `access_token` in a JSON body) and for `uploadMedia` (`PixelfedRepository.kt:207-216`).

**Impact** — `client_secret` and the long-lived `access_token` for the user's Pixelfed account are written to logcat on every build, including the signed release APK shipped by CI (`.github/workflows/build.yml:60-70` assembles `assembleCiProdRelease`). Alongside that, the interceptor writes the whole request body into an in-memory `Buffer` to decide whether to log it; the multipart preamble is ASCII, so OkHttp's plaintext check classifies the part as text and converts the entire ~8 MB binary payload into a `String` (≈8 M chars / 16 MB +) on every upload — avoidable CPU/RAM churn on the upload critical path. There are also no timeouts configured (OkHttp defaults 10 s connect/read/write) for an 8 MB upload over a slow mobile uplink.

**Recommendation**
```kotlin
val builder = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(120, TimeUnit.SECONDS)   // 8 MB uploads on slow uplinks
    .readTimeout(60, TimeUnit.SECONDS)
if (BuildConfig.DEBUG) {
    builder.addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
}
return builder.build()
```
(buildConfig is already available from the flavors; `Level.BASIC` keeps method/URL/status without bodies.)

**Blueprint reference** — neurhome does not ship a body-logging interceptor and keeps environment-specific configuration out of release artifacts (no `HttpLoggingInterceptor` anywhere in `neurhome/app/src/main`).

---

### [SEVERITY: high] Every processed photo is copied into `cacheDir` and never deleted
**Evidence**
- `ImageUtils.kt:310` — `File.createTempFile("resized_upload_", ".jpg", context.cacheDir)`; `ImageUtils.kt:412` — `File.createTempFile("original_upload_", ".jpg", context.cacheDir)`.
- `PixelfedRepository.kt:194-197` — the upload path calls `resizeImageDownToMaxBytes(...)` or `getFileFromUri(uri)` (`PixelfedRepository.kt:726-737`, `File.createTempFile("upload_", ".jpg", …)`).
- `UploadViewModel.kt:249-266` — the UI path calls `resizeImageDownToMaxBytes` as well.
- No deletion after upload: the only `delete()` in the domain is the EXIF-scratch file at `ImageUtils.kt:155`.

**Impact** — Each photo processed leaves one or two files up to 8 MB (`resized_upload_*`, `original_upload_*`, `upload_*`) in `cacheDir`, unbounded between OS cache evictions: a 6-photo post that was swiped through with the resize toggle on can leave ~10 files / tens of MB, and repeated uploads accumulate. Because the "already small enough" branch copies the **original bytes** (`ImageUtils.kt:300-302`), those leftovers include un-stripped EXIF (GPS, timestamps) of the user's photos sitting in the cache directory indefinitely.

**Recommendation** — delete in a `finally` once the multipart request has completed, or write into a dedicated subdirectory cleared at process start:
```kotlin
val uploadDir = File(context.cacheDir, "uploads").apply { mkdirs() }
// PixlitApplication.onCreate(): uploadDir.listFiles()?.forEach { it.delete() }
...
try { /* resize + upload */ } finally { tempFiles.forEach { it.delete() } }
```
**Blueprint reference** — neurhome keeps a bounded, explicitly-managed cache and documents the eviction (see the photo cache in `app/src/main/java/ovh/litapp/neurhome3/data/dao/ContactsDAO.kt:87-107`).

---

### [SEVERITY: high] The resize pipeline runs twice per photo and re-runs on every pager swipe
**Evidence**
- `UploadViewModel.kt:128-142` — `combine(_currentPage, _selectedImageUris, _resizeTo8Mb).onEach { … updateMetadata(uri, resize) }.launchIn(viewModelScope)`.
- `UploadViewModel.kt:249-266` — `updateMetadata` runs the full `resizeImageDownToMaxBytes` on `Dispatchers.IO` whenever the current page/toggle changes (previous jobs are not cancelled).
- `PixelfedRepository.kt:194-196` — the upload repeats `ImageUtils.resizeImageDownToMaxBytes(...)` for the same URI.

**Impact** — With the toggle on, each photo is decoded, scaled, JPEG-encoded (possibly several loop iterations, `ImageUtils.kt:319-350`) once while browsing and again during upload: seconds of CPU and duplicated I/O for a 6-photo post on the critical path, plus the extra cache files from the previous finding. Swiping through 6 pages eagerly re-processes each image even if the user only wanted the metadata.

**Recommendation** — produce the prepared file once and hand it to the repository (single source of truth), e.g. cache `Map<Uri, PreparedFile>` in the ViewModel keyed by `uri + resize` and pass it into `uploadPhotosAndCreateStatus(imageFiles = …)`, so the upload never re-encodes; and make page changes use `collectLatest`/`flatMapLatest` so superseded metadata jobs are cancelled.

---

### [SEVERITY: medium] Room has no schema history and no migration path
**Evidence** — `app/src/main/java/ovh/litapp/pixlit/data/db/AppDatabase.kt:4-7`: `@Database(entities = [StatusEntity::class], version = 1, exportSchema = false)`; `app/build.gradle` has **no** `ksp`/`room.schemaDirectory` argument and no `app/schemas` directory exists; `di/DatabaseModule.kt:21-26` builds with `Room.databaseBuilder(...).build()` and no `addMigrations`/`fallbackToDestructiveMigration`.

**Impact** — No committed schema means no diff review of DB changes and no way to write/verify a migration; the first `version = 2` without a migration will throw `IllegalStateException` at open and crash on launch (there is no destructive fallback and no test to catch it before release).

**Recommendation**
```kotlin
// app/build.gradle
room { schemaDirectory("$projectDir/schemas") }
// AppDatabase.kt
@Database(entities = [StatusEntity::class], version = 1, exportSchema = true)
```
and for the next change add `autoMigrations = [AutoMigration(from = 1, to = 2)]` with the exported JSONs committed.

**Blueprint reference** — neurhome does exactly this: `app/build.gradle.kts:18-20` `room { schemaDirectory("$projectDir/schemas") }`, `exportSchema = true` + `autoMigrations` (`app/src/main/java/ovh/litapp/neurhome3/data/AppDatabase.kt:34-40`), 23 committed schema files under `app/schemas/ovh.litapp.neurhome3.data.AppDatabase/`, a dedicated sweep test, and a CI step "Verify Room schema export is committed" (`neurhome/.github/workflows/pr.yml:84-98`).

---

### [SEVERITY: medium] Room cache is never invalidated, never pruned, and is keyed only by instance
**Evidence** — `data/db/StatusDao.kt:20-21` `clearStatuses` and `StatusDao.kt:11-12` `getStatusesFlow` are never called anywhere (only `getStatuses`, `insertStatuses` are used: `PixelfedRepository.kt:428-446`, `467-470`, `508-514`, `565-570`); `MainViewModel.kt:103-109` `logout()` only calls `tokenManager.clear()`; `PixelfedRepository.kt:519-560` caches tag searches in prefs keyed by `"${instanceUrl}|$query"` (no account), and the status cache is queried by `instanceUrl` only (`StatusDao.kt:11-15`).

**Impact** — Switching accounts on the same instance (or logging out and back in as someone else) keeps serving the previous account's cached statuses as the fallback result (`PixelfedRepository.kt:433-444`) and the previous account's cached hashtag searches; nothing bounds table growth, and the Debug tab only reports the counts (`UploadScreen.kt:713-724`) with no way to clear them.

**Recommendation** — call `statusDao.clearStatuses(instanceUrl)` and drop `tagSearchCacheJson` in `logout()`, include the account id in the tag-cache key, and prune on insert (e.g. keep the newest 40 per instance: `DELETE FROM statuses WHERE instanceUrl = :instanceUrl AND id NOT IN (SELECT id … ORDER BY createdAt DESC LIMIT 40)`). Add a DAO test with an in-memory database (there are currently none).

---

### [SEVERITY: medium] Cached status timestamps are "now", so `ORDER BY createdAt DESC` does not reflect post order
**Evidence** — `PixelfedRepository.kt:465-471` (`StatusEntity(id = id, …, createdAt = System.currentTimeMillis(), instanceUrl = instanceUrl)`) and `PixelfedRepository.kt:509-513` (`StatusEntity(id, item.content, item.text, item.description, System.currentTimeMillis(), instanceUrl)`), consumed by `StatusDao.kt:11-15` (`ORDER BY createdAt DESC`). `StatusItem` (`data/api/PixelfedApi.kt:191-202`) has no `created_at`/`createdAt` field at all, so the API's real timestamp is discarded (the bundled asset does carry `created_at`).

**Impact** — Every row inserted by one fetch shares (near-)identical millisecond timestamps, so the "recent posts" fallback order is effectively arbitrary and, worse, a later re-fetch re-inserts everything with a fresh stamp, so old posts can sort above newer ones. "Recent posts" derived from cache is therefore unreliable, and the top-tag counts will be recomputed over a mis-ordered, ever-growing set.

**Recommendation** — add `@SerializedName("created_at") val createdAt: String? = null` (or an epoch converter) to `StatusItem`, persist the real value, and keep `System.currentTimeMillis()` only as a separate `fetchedAt` column used for staleness/pruning.

---

### [SEVERITY: medium] Uploaded part always claims `.jpg` + `image/*`; resizing silently drops EXIF and flattens alpha
**Evidence** — `PixelfedRepository.kt:200` `file.asRequestBody("image/*".toMediaTypeOrNull())` with `MultipartBody.Part.createFormData("file", file.name, requestFile)` (`PixelfedRepository.kt:201`); `file.name` is always `original_upload_*.jpg` / `upload_*.jpg` / `resized_upload_*.jpg` regardless of the source format (`ImageUtils.kt:310`, `412`; `PixelfedRepository.kt:729`). The no-resize branch copies the source bytes verbatim (`ImageUtils.kt:300-302`, `PixelfedRepository.kt:726-737`), while the resize branch always re-encodes with `Bitmap.CompressFormat.JPEG` (`ImageUtils.kt:333`).

**Impact** — A HEIC/AVIF source (iPhones sharing into Pixlit, several Android cameras) is uploaded as a `.jpg`-named `image/*` part with HEIC content: PHP/Laravel `mimes:` validation sniffs the real type and rejects it (upload fails only after the whole file has been transferred), and any server that trusts the type stores a mislabelled asset. In the other direction, when the file is ≤ 8 MB nothing is converted, so the "resize" feature is not a "normalise to JPEG" feature — and when it does convert, all EXIF (DateTimeOriginal, camera data) is lost and transparent PNGs get a black background.

**Recommendation** — resolve the real type/name from the resolver and pass it through, and convert anything that is not JPEG/PNG in the resize path:
```kotlin
val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
} ?: file.name
val requestFile = file.asRequestBody(mime.toMediaTypeOrNull())
```
Plus: flatten alpha onto white (or keep PNG) before `compress`, and decide explicitly whether EXIF should survive (copy orientation/DateTimeOriginal through `ExifInterface` if the server cares).

---

### [SEVERITY: medium] I/O errors escape `resizeImageDownToMaxBytes`; failure paths leave temp files and an unclosed stream
**Evidence** — `ImageUtils.kt:294-352` has no `try`/`catch` and no `use` around the stream: `val outStream = FileOutputStream(tempFile)` / `scaledBitmap.compress(…)` / `outStream.close()` (`ImageUtils.kt:331-334`); `ImageUtils.kt:325-326` returns `null` after the temp file was already created at `ImageUtils.kt:310`. Callers are inside coroutines with no handler: `UploadViewModel.kt:258-261` (`withContext(Dispatchers.IO) { ImageUtils.resizeImageDownToMaxBytes(...) }`) and `PixelfedRepository.kt:194-196` (inside `try`, so the upload holds, but the metadata path does not).

**Impact** — Disk-full, `FileNotFoundException`, or a decode/`OutOfMemoryError` throw out of the ViewModel coroutine → an unhandled exception crashes the app (or is swallowed by the default handler); partial/empty `resized_upload_*` files remain in cache; a file descriptor leaks when `compress`/`close` throws.

**Recommendation** — make the public entry points total and tidy:
```kotlin
fun resizeImageDownToMaxBytes(context: Context, uri: Uri, maxSizeBytes: Long = MAX_BYTES_8MB): File? =
    try { /* … */ } catch (t: Throwable) { Log.w(TAG, "resize failed", t); null }
```
use `FileOutputStream(tempFile).use { scaledBitmap.compress(JPEG, quality, it) }`, and delete the temp file on every early-return path; callers should render a clear error instead of a silent no-op.

---

### [SEVERITY: medium] ~1 MB asset JSON is read and parsed on the main thread in the ViewModel constructor
**Evidence** — `UploadViewModel.kt:58` `private val _topTags = MutableStateFlow(repository.getDefaultStaticTagCounts())` (ViewModel fields are initialised during composition on the main thread) → `PixelfedRepository.kt:313-316` `getDefaultStaticTagCounts()` → `getStaticStatuses()` (`PixelfedRepository.kt:286-306`) which does `context.assets.open("pixelfed-statuses.json").use { … readText() }` and a fresh `Gson().fromJson(...)` + `TypeToken`. The asset is ≈1 MB (24 128 lines; same file the API-failure fallback re-parses at `PixelfedRepository.kt:345`, `354`, `439`).

**Impact** — Tens of milliseconds of main-thread I/O + JSON parsing on first composition of the upload screen (and again on every fallback path), with two copies of the 1 MB string while parsing, and a fresh `Gson`/`TypeToken` per call. This is the only main-thread blocking work I found in this domain (Room access is all `suspend`/`Flow`, image work is on `Dispatchers.IO`), so it is the one place that risks a dropped frame / watchdog complaint.

**Recommendation** — cache the parsed list and load it off the main thread:
```kotlin
private val staticStatuses: List<StatusItem> by lazy { … }         // parse once
suspend fun loadStaticTagCounts(): List<TagCount> =
    withContext(Dispatchers.IO) { extractTopTagCountsFromStatuses(staticStatuses, MAX_DISPLAYED_TAGS) }
```
initialise `_topTags` to `emptyList()` and fill it from `init { viewModelScope.launch { … } }`.

---

### [SEVERITY: medium] Metadata work is not synchronised: stale results and a shared `isCalculatingResized` flag
**Evidence** — `UploadViewModel.kt:128-142` (`onEach` → `viewModelScope.launch` per emission, no cancellation); `UploadViewModel.kt:249-266` sets `_originalMetadata`, `_isCalculatingResized`, `_resizedMetadata` from whichever job finishes last, for a single shared flag; `UploadViewModel.kt:158-187` (`detectExifLocation`) has the same shape for location state.

**Impact** — Fast paging with the resize toggle on makes the preview show the size/dimensions (and the "calculating" indicator) of a different photo than the one displayed; the shown "resized" size can belong to a photo that never gets resized. The EXIF/location pass can also be overwritten by a stale URI's city name.

**Recommendation** — key the state by URI (`Map<Uri, ImageState>` or `StateFlow<Map<…>>`) and derive the displayed values from `_selectedImageUris[_currentPage]`, or use `collectLatest` on the page flow so only the newest request survives; clear the indicator per-URI instead of with one global boolean.

---

### [SEVERITY: medium] SharedPreferences (with `commit()`) instead of DataStore; one write happens on the main thread
**Evidence** — `data/auth/TokenManager.kt:12-13` (`getSharedPreferences("pixlit_prefs", MODE_PRIVATE)`), every setter uses `prefs.edit().…commit()` (`TokenManager.kt:26, 32, 38, 44, 50, 56, 62`), and `clear()` also commits (`TokenManager.kt:70`); `data/reminder/ReminderPreferences.kt:9-22` uses a second `SharedPreferences` file. `MainViewModel.kt:103-104` `logout()` → `tokenManager.clear()` runs on the main thread (called from `MainActivity.kt:90-92`), and `MainViewModel.kt:33` reads prefs during ViewModel construction (first read loads the XML synchronously). The tag-search cache JSON (potentially large, `PixelfedRepository.kt:544-554`) is rewritten with `commit()`.

**Impact** — Blocking disk I/O on the main thread at logout/first read (StrictMode violation), no reactive state (a second consumer can't observe token changes without polling), and a JSON blob growing unbounded inside a SharedPreferences file, which is re-serialised in full on every tag search.

**Recommendation** — migrate `TokenManager` and `ReminderPreferences` to `androidx.datastore:datastore-preferences` with a `Flow`-based API (`at least use apply()` and move the JSON cache to a file/Room table in the meantime).

**Blueprint reference** — neurhome depends on `androidx-datastore-preferences:1.2.1` (`app/build.gradle.kts:173-174`) and has no `getSharedPreferences` call anywhere; settings live in Room/an explicit repository (`Setting` entity).

---

### [SEVERITY: medium] Missing `instanceUrl` index for the DAO's filter+sort
**Evidence** — `data/db/StatusEntity.kt:6-13` (no `@Entity(indices = …)`), `StatusDao.kt:11-15` (`WHERE instanceUrl = :instanceUrl ORDER BY createdAt DESC` twice).

**Impact** — Full table scan plus a temp B-tree sort on every read of the fallback path (`PixelfedRepository.kt:439`) and every `getCacheSummary()` call (`PixelfedRepository.kt:565-570`). Small today, but the cache is never pruned, so it degrades as the table grows and is trivially avoidable.

**Recommendation** — `@Entity(tableName = "statuses", indices = [Index(value = ["instanceUrl", "createdAt"])])` (bump the version with the migration from the Room finding).

---

### [SEVERITY: low] Reminder/notification hygiene and startup hardening
**Evidence**
- `MainActivity.kt:51` calls `reminderScheduler.scheduleAll()` unguarded; `ReminderScheduler.kt:19-21` does `LocalTime.of(prefs.getInt("friday_hour", 20), prefs.getInt("friday_minute", 0))` — a corrupt/out-of-range stored value throws `DateTimeException` on the main thread during `onCreate`.
- `MainActivity.kt:47-49` re-requests `POST_NOTIFICATIONS` on every cold start with no rationale, and `ArtShowNotifier.kt:22` silently skips the notification if it is denied (the WorkManager theme fetch still runs).
- `ArtShowNotifier.kt:18` recreates the channel on every notification (idempotent, but importance changes are ignored after first creation — worth a comment or a versioned id), and `ArtShowNotifier.kt:30` uses `R.drawable.ic_launcher_foreground` as `setSmallIcon` (an adaptive-icon foreground can render as a grey blob in the status bar).
- Inexact alarm `setAndAllowWhileIdle` (`ReminderScheduler.kt:38-43`) is the right choice for evening/morning reminders, but it cannot reliably land at 20:00 under Doze (documented as acceptable in `README.md:33`).

**Impact** — Small robustness/UX gaps: a possible startup crash from stale prefs, reminders silently disabled with no user feedback, inconsistent notification iconography, and reminder timing that can drift under Doze.

**Recommendation** — wrap `scheduleAll()` in `runCatching`, clamp/validate the stored hour/minute (`LocalTime.of(hour.coerceIn(0,23), minute.coerceIn(0,59))`), request `POST_NOTIFICATIONS` once with an explanation (and surface "reminders need notification permission" in Settings), create the channel once in `PixlitApplication`, and add a dedicated white monochrome vector for `setSmallIcon`.

---

### [SEVERITY: low] Design doc contradicts the shipped scheduling implementation, and its promised test is absent
**Evidence** — `docs/bluesky-art-show-reminders.md:24` ("Scheduling | WorkManager one-shot works with computed delay (not exact alarms)"), `:40-49` ("Greenfield: **WorkManager** (survives reboot…)"), `:85-89` ("No 'already posted this week' suppression…"); the code schedules with `AlarmManager.setAndAllowWhileIdle` + `ArtShowReminderReceiver` (`ReminderScheduler.kt:28-43`, `ArtShowReminderReceiver.kt:17-33`) and uses WorkManager only for the theme fetch (`ArtShowReminderReceiver.kt:26-31`); `README.md:33` describes the alarms (so the README, not the design doc, matches the code). The doc's promised "Worker shows fallback notification when Bluesky fails (MockK)" test does not exist (`app/src/test` contains only `MainViewModelTest`, `TokenManagerTest`, `ReminderSchedulerTest`, `BlueskyArtShowRepositoryTest`, `PixelfedRepositoryTest`, `UploadViewModelTest`, `ImageUtilsTest`).

**Impact** — A future reader (or agent) may re-implement scheduling as WorkManager per the doc and change behaviour, and the reboot/time-change reschedule claims (`ReminderRescheduleReceiver.kt:12-16`, manifest `:52-61`) are unverified by tests.

**Recommendation** — update the doc's Decisions/Scheduling rows to the alarm-based design (and note why: alarms survive Doze better than a WorkManager delay for a wall-clock time), keep the "no exact alarms" rationale, and add the missing `ArtShowReminderWorker`/`ArtShowNotifier` test.

---

### [SEVERITY: low] Test gaps for this whole domain, and CI never runs the tests
**Evidence**
- `app/src/test/java/ovh/litapp/pixlit/utils/ImageUtilsTest.kt` uses only `Uri.fromFile(...)` (`:9-30, 60-77`) — no `content://` (the real picker/share path), no HEIC/PNG input, no oversized-image/OOM-bound case, no error-path (unreadable URI/disk-full) case, no `copyUriToTempFile` format case.
- `data/reminder/ReminderSchedulerTest.kt:7-18` covers only `nextRunDelay` (2 cases, UTC) — nothing for `schedule`/`cancel`/`rescheduleAll`, PendingIntent identity/flags, DST transitions, or `ReminderPreferences` defaults; no `ArtShowNotifier`/`ArtShowReminderWorker`/receiver test.
- No Room test at all (`grep` for `inMemoryDatabaseBuilder`/`Room.` in `app/src/test` returns nothing) — `StatusDao`/`StatusEntity` are untested.
- `.github/workflows/build.yml:60-70` runs only `assembleCiDevRelease`/`assembleCiProdRelease` plus signing/release; there is no `…UnitTest` or `lint` invocation, so all of the above is only exercised locally.

**Impact** — The riskiest code in this domain (resize/OOM, DAO queries/migrations, alarm rescheduling, notification permission handling) has no automated guard, and regressions reach the release APK CI publishes.

**Recommendation** — add a CI step (plus `lint`) — `./gradlew :app:testCiDevDebugUnitTest :app:lintCiDevDebug` — and fill the gaps: `content://` fixture via a `ContentProvider`/Robolectric `ShadowContentResolver`, a bounded-pixel assertion for oversized sources, an in-memory Room DAO test, a DST case for `nextRunDelay` (`ZonedDateTime` around the spring-forward boundary), and a `TestListenableWorkerBuilder` test for the theme-fetch fallback.

**Blueprint reference** — neurhome's PR workflow runs `:app:assembleDevDebug :app:testDevDebugUnitTest :app:lintDevDebug --parallel` and verifies committed Room schemas (`neurhome/.github/workflows/pr.yml:78-98`), with the DB-schema sweep test in `app/src/test/java/ovh/litapp/neurhome3/data/AppDatabaseSchemaSweepTest.kt`.

---

### Verified correct (no finding)
- **Timezone/DST arithmetic**: `ReminderScheduler.kt:56-61` uses zone-aware `ZonedDateTime` and `Duration.between(now, next)`, which resolves to a real instant difference, so the alarm instant is DST-safe; the `!next.isAfter(now)` roll-over is correct and is the one thing the existing test covers.
- **Alarm cancel/flags**: `cancel()` (`ReminderScheduler.kt:45-53`) reuses the same request code (`day.value`) and passes `FLAG_IMMUTABLE` with `FLAG_NO_CREATE`; `PendingIntent` matching ignores extras but requires matching mutability, so cancellation resolves to the same pending intent — no crash/false-negative here. Unique request codes (5/6) avoid collisions; `rescheduleAll()` is idempotent.
- **Reboot/time-change rescheduling**: `ReminderRescheduleReceiver` + manifest `:52-61` (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED`) matches the alarms-cleared-on-reboot reality; `AlarmManager` + `WorkManager` are both correctly declared (`PixlitApplication` implements `Configuration.Provider` with `HiltWorkerFactory`, and `WorkManagerInitializer` is removed in the manifest `:62-71`).
- **Room is not accessed on the main thread**: all DAO methods are `suspend`/`Flow` (`StatusDao.kt:11-21`) and every call site is inside `withContext(Dispatchers.IO)` or a coroutine (`PixelfedRepository.kt:428-470, 508-570`).
- **Bitmap recycling discipline inside the happy path**: `rotateBitmap` recycles the pre-rotation bitmap (`ImageUtils.kt:279-283`) and `resizeImageDownToMaxBytes` guards the shared-instance case before recycling (`ImageUtils.kt:336-339`); request sizes are floored at 100 px so `createScaledBitmap` cannot be called with 0 (`ImageUtils.kt:322-323`).
- **`extractExifLocation`** correctly handles the API 29+ redaction path (`MediaStore.setRequireOriginal` guarded by `runCatching(Throwable)` at `ImageUtils.kt:132-143`), deletes its scratch file in `finally` (`ImageUtils.kt:150-159`) and falls back to DMS parsing when `latLong` is unavailable (`ImageUtils.kt:192-236`); the blocking `Geocoder` call is made off the main thread (`UploadViewModel.kt:160-186`).
- **Coil previews**: `ImagePagerSection.kt:44-50` uses Coil's painter, which samples to the composable size, so the 260 dp preview does not hold full-size bitmaps.

---

## Top 5 actions
1. **Fix the resize decode (critical)**: replace the `BitmapFactory`+`createScaledBitmap` loop with `ImageDecoder`/`setTargetSize` (or a pixel-bounded sample size), add an `OutOfMemoryError` safety net, and add an oversized-source test. This is the only finding that can crash the app's primary flow.
2. **Make intent handling one-shot (high)**: consume `EXTRA_STREAM`/`data` after handling and guard the `onCreate` call with `savedInstanceState == null`, so config changes cannot re-add a shared photo (duplicate post) or re-exchange a spent OAuth code (spinner + bogus call).
3. **Gate HTTP logging and add timeouts (high)**: `Level.BASIC` behind `BuildConfig.DEBUG` so `client_secret`/`access_token` never reach release logcat and the 8 MB multipart body is not buffered/stringified on every upload; set 20/60/120 s connect/read/write timeouts.
4. **Clean up and de-duplicate image work (high)**: delete temp files in `finally` (or clear a dedicated uploads dir in `PixlitApplication`), and compute the resized file once (ViewModel cache → repository) instead of re-encoding on every page swipe and again at upload.
5. **Give Room a future (medium)**: `exportSchema = true` + `room { schemaDirectory("$projectDir/schemas") }` with committed schemas (and AutoMigrations as neurhome does), add the `instanceUrl`(+`createdAt`) index, persist the real `created_at`, and clear/prune the cache on logout; then add the missing DAO/alarm/resize tests and a CI step that actually runs `:app:testCiDevDebugUnitTest` + lint.