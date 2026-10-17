# BlueSkyArtShow weekly reminders

Weekly local notifications (Fri 20:00 / Sat 08:00, device TZ, times editable) that pull the pinned `#BlueSkyArtShow` theme from Bluesky and, on tap, open upload with tags prefilled. Always on after `POST_NOTIFICATIONS`.

## Goal

Remind the user Friday evening and Saturday morning to post a photo for the current `#BlueSkyArtShow` theme, taken from the pinned post on [churchstreetimages.com](https://bsky.app/profile/churchstreetimages.com).

## Decisions

| Item | Choice |
|---|---|
| Friday default | 20:00 local |
| Saturday default | 08:00 local |
| Times | Configurable in-app (no enable/disable toggle) |
| Tap | Open upload and prefill `#BlueSkyArtShow` + theme tag |
| Enablement | Always on; `POST_NOTIFICATIONS` is requested from the Settings tab where the reminder is configured |
| Scheduling | Inexact idle-tolerant `AlarmManager` alarms, one per weekday (not exact alarms) |
| Timezone | `ZoneId.systemDefault()` |

## Data: theme from Bluesky

Public AT Protocol (no login):

1. `GET https://public.api.bsky.app/xrpc/app.bsky.actor.getProfile?actor=churchstreetimages.com` → `pinnedPost.uri`
2. `GET https://public.api.bsky.app/xrpc/app.bsky.feed.getPosts?uris=<uri>` → `posts[0].record.text`

Parse with `(?i)The theme is\s+(#\w+)`.

Examples:

- `The theme is #Minimal` → `#Minimal`
- `The theme is #Summer` → `#Summer`

On miss/network error, still notify with `#BlueSkyArtShow` only.

Reuse existing OkHttp/Gson via a small `BlueskyApi` Retrofit interface + `BlueskyArtShowRepository`. Handle hardcoded to `churchstreetimages.com`.

## Scheduling

The shipped design uses **`AlarmManager`** inexact, idle-tolerant alarms, not a WorkManager delay
(`ReminderScheduler.kt:28-43`).

- One alarm per weekday, keyed by a `PendingIntent` with request code `day.value` (Friday 5, Saturday 6)
- Trigger instant = now + `nextRunDelay(day, time)`, where `nextRunDelay` (`ReminderScheduler.kt:56-60`)
  resolves the next occurrence of that weekday at the saved hour/minute in `ZoneId.systemDefault()`
- `ArtShowReminderReceiver` posts the reminder immediately, re-schedules the following week and
  enqueues the optional theme fetch (`ArtShowReminderReceiver.kt:18-34`)
- WorkManager runs only that theme fetch (`ArtShowReminderWorker`), never the reminder timing
  (`ArtShowReminderReceiver.kt:26-33`)
- Reboot / time / timezone change → `ReminderRescheduleReceiver` cancels and re-schedules both alarms
  (`ReminderRescheduleReceiver.kt:13-14`; manifest actions `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`,
  `TIME_SET`, `TIMEZONE_CHANGED`)

Alarms beat a WorkManager delay for a wall-clock reminder: WorkManager's minimum delay and Doze
deferral are unbounded, whereas `setAndAllowWhileIdle` fires from a Doze maintenance window at the
requested wall-clock instant. It is still inexact (the OS may defer it), which is acceptable for
evening/morning reminders; do not use `SCHEDULE_EXACT_ALARM`.

## Notifications

- Manifest + runtime `POST_NOTIFICATIONS` (minSdk 34)
- Channel `art_show_reminders`
- Title: `#BlueSkyArtShow: #Minimal` (or `#BlueSkyArtShow` if parse fails)
- Body: Friday vs Saturday copy
- Tap → `MainActivity` extras (`theme`, `prefill=true`); handle `onNewIntent` (`singleTask`)
- `UploadViewModel` inserts `#BlueSkyArtShow` + theme via existing `insertTag`

Request permission from the Settings tab, where the reminder is configured; the app schedules on
startup regardless so it starts working once granted (`SettingsTabContent.NotificationPermissionNotice`).

## Settings UI

Always-on (no enable switch). A **Settings** tab beside Upload, Social, Recent (and Debug in dev builds) holds two time pickers. Persist with preferences DataStore (`ReminderPreferences`), reschedule on change. The tab also shows the About/license section and a logout button.

## Wiring

| Area | Change |
|---|---|
| Catalog | `work-runtime-ktx`, `hilt-work` + `hilt-compiler` |
| Manifest | `POST_NOTIFICATIONS`; disable default WM initializer |
| `PixlitApplication` | `Configuration.Provider` + `HiltWorkerFactory` |
| Hilt | Provide `BlueskyApi` (fixed base URL `https://public.api.bsky.app/`) |

New types (suggested):

- `data.api.BlueskyApi` + DTOs
- `data.repository.BlueskyArtShowRepository` (fetch + parse)
- `data.reminder.ReminderPreferences` (Fri/Sat hour+minute)
- `data.reminder.ReminderScheduler`
- `data.reminder.ArtShowReminderWorker` (`@HiltWorker`)
- `data.reminder.ArtShowNotifier` (channel + `NotificationCompat`)
- Theme parser (pure function, easy to unit test)

## Tests

- Theme parser on both sample posts + failure cases (`BlueskyArtShowRepositoryTest`)
- Next-run delay, including a spring-forward DST boundary
  (`ReminderSchedulerTest.next run crosses spring forward without an hour of drift`)
- `PendingIntent` identity/flags for `cancel()`, and the Friday/Saturday preference defaults and
  out-of-range clamping (`ReminderSchedulerTest`)
- Prefill does not duplicate tags
- `ArtShowReminderWorker` posts the untitled fallback notification when the Bluesky theme fetch fails
  (`ReminderSchedulerTest.worker posts an untitled fallback notification when the theme fetch fails`)

## Out of scope

No “already posted this week” suppression, no Bluesky posting, no exact alarms.
