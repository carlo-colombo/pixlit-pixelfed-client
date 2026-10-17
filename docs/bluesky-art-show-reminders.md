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
| Enablement | Always on; request `POST_NOTIFICATIONS` on launch |
| Scheduling | WorkManager one-shot works with computed delay (not exact alarms) |
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

Greenfield: **WorkManager** (survives reboot, no exact-alarm permission).

- Two unique one-shot works (`art_show_friday`, `art_show_saturday`)
- Delay = next occurrence of that weekday + saved hour/minute
- Worker: fetch theme → notify → enqueue the following week
- Time change → cancel + reschedule both
- `ExistingWorkPolicy.REPLACE`

WorkManager is accurate enough for evening/morning reminders; do not use `SCHEDULE_EXACT_ALARM`.

## Notifications

- Manifest + runtime `POST_NOTIFICATIONS` (minSdk 34)
- Channel `art_show_reminders`
- Title: `#BlueSkyArtShow: #Minimal` (or `#BlueSkyArtShow` if parse fails)
- Body: Friday vs Saturday copy
- Tap → `MainActivity` extras (`theme`, `prefill=true`); handle `onNewIntent` (`singleTask`)
- `UploadViewModel` inserts `#BlueSkyArtShow` + theme via existing `insertTag`

Request permission in `MainActivity.onCreate`; schedule regardless so it starts working once granted.

## Settings UI

Always-on (no enable switch). Add a **Settings** tab beside Upload/Debug with two Material3 time pickers. Persist in SharedPreferences (same style as `TokenManager`), reschedule on change. Optional: show this week’s theme on that tab.

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

- Theme parser on both sample posts + failure cases
- Next-run delay (before/after Friday 20:00, timezone)
- Prefill does not duplicate tags
- Worker shows fallback notification when Bluesky fails (MockK)

## Out of scope

No “already posted this week” suppression, no Bluesky posting, no exact alarms.
