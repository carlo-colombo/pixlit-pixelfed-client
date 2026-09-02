# Pixlit

Pixlit is a personal Android client for uploading images to [Pixelfed](https://pixelfed.org/). It is tailored to the `carlo-colombo` workflow, with browsing and feed consumption intentionally out of scope.

## Features

### Pixelfed uploads

- Connect to any Pixelfed instance using OAuth2. The default instance is `pixelfed.social`.
- Automatically register the Pixlit OAuth client, or enter a client ID and secret manually.
- Select and upload up to six photos in one post.
- Preview the selected images, move photos left or right, and remove individual photos before uploading.
- Write a caption and insert hashtags at the current cursor position.
- Resize images larger than 8 MB before upload, with original and resized metadata displayed.
- View image dimensions and file sizes while preparing a post.
- Receive clear login, network, and upload error messages.

### Hashtags and diagnostics

- Load the user's recent Pixelfed posts and calculate the most-used hashtags, including tags found in captions and media descriptions.
- Display up to 30 top hashtags with usage counts for one-tap insertion.
- Cache fetched statuses locally with Room and fall back to the bundled status data when the network is unavailable.
- Use the Debug tab to inspect retrieved statuses and simulate an Art Show notification.

### BlueSky Art Show

- View the pinned weekly art challenge from `churchstreetimages.com` in the Social tab.
- Show daily challenge tags and insert a day's tags directly into the upload caption.
- Schedule always-on local reminders for Friday evening and Saturday morning.
- Fetch the current theme from Bluesky for each reminder and include it in the notification title.
- Tap a reminder to open Pixlit with `#BlueSkyArtShow` and the theme prefilled, without duplicating existing tags.
- Configure the Friday and Saturday reminder times in Settings; times use the device timezone.
- Reminders use WorkManager and are rescheduled after time changes and device restarts.

## Tech Stack

- **Language:** Kotlin
- **UI:** Jetpack Compose and Material 3
- **Architecture:** MVVM with unidirectional state flow
- **Networking:** Retrofit, OkHttp, and Gson
- **Image loading:** Coil
- **Persistence:** Room for status caching and SharedPreferences for OAuth/reminder preferences
- **Background work:** WorkManager
- **Dependency injection:** Hilt
- **Concurrency:** Kotlin Coroutines and Flow

## Getting Started

### Prerequisites

- Android Studio Koala or newer
- JDK 17
- Android SDK 37

### Building

Clone the repository and open it in Android Studio:

```bash
git clone https://github.com/carlo-colombo/pixlit-pixelfed-client.git
cd pixlit-pixelfed-client
```

The app has two product flavors:

```bash
./gradlew assembleDevDebug   # Local development build, labeled "Pixlit Dev"
./gradlew assembleProdDebug  # Production-flavor debug build
./gradlew assembleProdRelease
```

The dev flavor shows a `DEV BUILD` banner and uses a separate application ID and OAuth redirect scheme. Release builds enable code and resource shrinking.

On first launch, enter a Pixelfed instance URL and choose **Log In with Pixlit**. Pixlit opens the instance's OAuth page in a browser Custom Tab and handles the callback in the app. Android 13 and newer also require notification permission for Art Show reminders.

## Testing

Run the unit tests with:

```bash
./gradlew testDevDebugUnitTest
```

Tests cover OAuth parsing and token storage, tag extraction and counts, image utilities, upload state, Bluesky theme/challenge parsing, reminder scheduling, and notification prefill behavior.

## Scope

Pixlit is optimized for a single user's upload flow. Timeline browsing, Bluesky posting, exact alarms, and suppression of reminders after posting are not supported.
