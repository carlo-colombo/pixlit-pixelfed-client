# Pixlit

Pixlit is a personal Android client for uploading images to [Pixelfed](https://pixelfed.org/). It is
tailored to the `carlo-colombo` workflow, with browsing and feed consumption intentionally out of
scope.

## Features

### Pixelfed uploads

- Connect to any Pixelfed instance using OAuth2 (authorization code + S256 PKCE). The default
  instance is `pixelfed.social`.
- Automatically register the Pixlit OAuth client, or enter a client ID and secret manually.
- Select and upload up to six photos in one post.
- Preview the selected images, move photos left or right, and remove individual photos before
  uploading.
- Write a caption and insert hashtags at the current cursor position.
- Resize images larger than 8 MB before upload, with original and resized metadata displayed.
- View image dimensions and file sizes while preparing a post.
- Add per-photo alt text.
- Receive clear login, network, and upload error messages.
- Uploads run in a WorkManager foreground job, so back-press, rotation, or process death does not
  drop the post; progress is shown per photo and a retry cannot duplicate the post.

### Hashtags and diagnostics

- Load the user's recent Pixelfed posts and calculate the most-used hashtags, including tags found
  in captions and media descriptions.
- Display up to 30 top hashtags with usage counts for one-tap insertion.
- Cache fetched statuses locally with Room and fall back to the bundled status data when the network
  is unavailable.
- Use the Debug tab (dev builds only) to inspect retrieved statuses and simulate an Art Show
  notification.

### BlueSky Art Show

- View the pinned weekly art challenge from `churchstreetimages.com` in the Social tab.
- Show daily challenge tags and insert a day's tags directly into the upload caption.
- Schedule always-on local reminders for Friday evening and Saturday morning.
- Fetch the current theme from Bluesky for each reminder and include it in the notification title.
- Tap a reminder to open Pixlit with `#BlueSkyArtShow` and the theme prefilled, without duplicating
  existing tags.
- Configure the Friday and Saturday reminder times in Settings; times use the device timezone.
- Reminders use idle-tolerant alarms and are rescheduled after time changes and device restarts;
  WorkManager only fetches the optional theme.

## Tech Stack

- **Language:** Kotlin (JVM target 21)
- **UI:** Jetpack Compose and Material 3
- **Architecture:** MVVM with unidirectional state flow (a single `UploadUiState`)
- **Networking:** Retrofit, OkHttp, and Gson
- **Image loading:** Coil
- **Persistence:** Room for status/tag caching, DataStore for reminder preferences, and
  Keystore-backed EncryptedSharedPreferences for OAuth credentials
- **Background work:** WorkManager (uploads, theme fetch) and AlarmManager (reminders)
- **Dependency injection:** Hilt
- **Concurrency:** Kotlin Coroutines and Flow

## Getting Started

### Prerequisites

- Android Studio (a recent stable release)
- **JDK 21** — the project pins the JetBrains Runtime 21 via
  `gradle/gradle-daemon-jvm.properties` and the shared CI setup action
- **Android SDK Platform 37** (`compileSdk`/`targetSdk`; `minSdk` is 34)

### Building

Clone the repository and open it in Android Studio:

```bash
git clone https://github.com/carlo-colombo/pixlit-pixelfed-client.git
cd pixlit-pixelfed-client
```

Building from the command line (or a fresh worktree) needs an SDK path in `local.properties`
(gitignored):

```properties
sdk.dir=/path/to/Android/Sdk
```

The app has two flavors, `dev` and `prod`, in a `debug` and `release` build type:

```bash
./gradlew :app:assembleDevDebug     # Dev build, labeled "Pixlit Dev" (id suffix .dev)
./gradlew :app:assembleProdRelease  # Minified prod release (R8 + resource shrinking)
```

The dev flavor shows a `DEV BUILD` banner and uses a separate application ID and OAuth redirect
scheme (`pixlit-dev`), so it can be installed alongside the prod build. Release builds enable code
and resource shrinking.

On first launch, enter a Pixelfed instance URL and choose **Log In with Pixlit**. Pixlit opens the
instance's OAuth page in a browser Custom Tab and handles the callback in the app. Reminders ask for
notification permission in Settings, where the reminder is configured.

## Testing

Run the unit tests with:

```bash
./gradlew :app:testDevDebugUnitTest                    # dev unit tests
./gradlew :app:testProdDebugUnitTest :app:lintProdRelease   # prod gate (pre-release)
```

Tests run on Robolectric API 34 with `robolectric.offline=true` and cover OAuth parsing/PKCE/token
storage, tag extraction and counts, image utilities, upload state and failure paths, the data layer
against MockWebServer and in-memory Room, Bluesky theme/challenge parsing, reminder scheduling, and
notification prefill behaviour. Lint uses `abortOnError = true`.

## Scope

Pixlit is optimized for a single user's upload flow. Timeline browsing, Bluesky posting, exact
alarms, and suppression of reminders after posting are not supported.

## License

Pixlit is free software, licensed under the **GNU General Public License, version 3 or later**
(`GPL-3.0-or-later`). Copyright (C) 2026 Carlo Colombo.

The full license text is in [`LICENSE`](LICENSE), the copyright and SPDX identifier in
[`NOTICE`](NOTICE), and the licenses of the third-party libraries the app links against in
[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md). All three ship inside the APK (under
`assets/licenses/`) and are shown under Settings → About.

This program is distributed in the hope that it will be useful, but **without any warranty**; without
even the implied warranty of merchantability or fitness for a particular purpose. See the GNU General
Public License for more details.
