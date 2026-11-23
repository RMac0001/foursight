# Implementation status

Tracks what's built against [`docs/SPEC.md`](docs/SPEC.md), what's been verified against the
live APIs, and what still needs testing. Update it with every change that moves a build-order
step forward.

## Build order

| Step | State |
| --- | --- |
| 1. Setup (developer apps) | Done by Roger, November 21, 2026 (details in the vault spec's Setup status) |
| 2. Core | Built and tested on branch `claude/step-2-core` (see below) |
| 3. Collectors | Done except Instagram's live check (no Reels on the linked account yet): TikTok, YouTube, Facebook, Drive publish, installed command and daily schedule verified live |
| 4. CLI and MCP | Built and tested on branch `claude/step-4-cli-mcp`; audience added November 22, 2026; obsidian and post wait for steps 5 and 8. Connected to the Claude app |
| 5. Obsidian writer | Built and tested on branch `claude/step-5-obsidian`; vault set on Roger's PC; waits for notes with post ids |
| 6. Windows UI | Built on branch `claude/step-6-windows-ui`: all six sections on real data; installed with desktop and Start menu shortcuts |
| 7. Android | Part 1 (the app with Drive sync) verified on Roger's phone; part 2 (phone sign-ins and collection) and part 3 (widget) next |
| 8. Posting | Not started |
| 9. Hardening | Not started |

## Skeleton (November 21, 2026)

- Modules `core`, `ui`, `desktopApp`, `androidApp`, per the spec's Architecture section.
- Toolchain: Kotlin 2.4.21, Compose Multiplatform 1.12.2, Android Gradle Plugin 9.5.2,
  Gradle 9.9.2, JDK 21. Android compileSdk/targetSdk 37, minSdk 29.
- Android application ID: `io.github.rmac0001.foursight` (approved by Roger).
- CI (GitHub Actions): format check, tests and the Android build on Linux; the Windows MSI
  installer on a Windows runner.

## Step 2: core (November 21, 2026)

- **Database** (SQLDelight 2.4.1, schema version 1): posts, raw snapshots with normalized values
  (missing stays NULL, never 0), user-edit events, connection status, sync bookkeeping. Plain SQL
  that works on Android 10's SQLite 3.22 (no upserts). Migrations are verified by SQLDelight's
  `verifyMigrations` once version 2 exists.
- **Metrics engine:** the metric catalog with per-platform sources or reasons they're unavailable,
  per-1,000 rates, views per day, engagement rate, follower change, cross-platform sums (counts
  only), and the day-7 lock. Definitions in `docs/metrics.md`.
- **Sync:** `SyncStore` interface, Google Drive v3 adapter (`drive.file`), and the merge engine
  (one writer per file, idempotent merge, last-writer-wins events with losers kept, per-file
  transactions, bad files reported and skipped).
- **Secrets:** `SecretStore` with Windows Credential Manager (JNA, CREDENTIALW) and an Android
  Keystore (AES-GCM) implementation.
- **Query services:** content library (confirmed groups plus unmatched posts), 7-day report,
  freshness, metric definitions.
- **Tests:** 47 JVM tests. The Credential Manager round trip runs on the Windows CI runner only.
- **Not yet exercised on a device:** the Android database and Keystore store compile but run on
  Android only (step 7). The Drive adapter is tested against a fake; its first live run is in step 3.

### Choices made in step 2, confirmed by Roger (November 21, 2026)

- **Day-7 window:** a capture counts as the day-7 number only between day 7.0 and day 8.0; after
  that the post shows as missed instead of locking on a later day's numbers.
- **Device registry:** one file per device (`devices/<device-id>.json`) instead of the spec's single
  `device-registry.json`, so every Drive file keeps exactly one writer. The vault note needs the
  matching one-line change (Roger's call; `docs/SPEC.md` is recopied from it).
- **Engagement rate:** (likes + comments + shares + saves where reported) ÷ views × 100.
- **Sign-in before step 4:** `foursight connect <platform>` and `foursight collect` are built in
  step 3 so the first live sign-in can happen; the rest of the CLI comes in step 4. `connect` isn't
  in the spec's CLI table yet.

### Windows run (November 21, 2026)

- `gradlew.bat check` passes on Roger's PC (Windows 11): 47 desktop tests, 0 skipped, including the
  Credential Manager round trip.

## Step 3: collectors (done except Instagram's live check)

- **TikTok** (built November 21, 2026): Login Kit for Desktop sign-in (loopback on
  `http://127.0.0.1:<random port>/callback/`, PKCE with the hex SHA-256 challenge, `state` check),
  token renewal that stores the rotated refresh token before anything else, `/v2/user/info/` for
  followers and every page of `/v2/video/list/` for lifetime views, likes, comments and shares. Only
  the read scopes (`user.info.basic`, `user.info.stats`, `video.list`) are requested; posting scopes
  wait for step 8. Missing fields stay missing. The access token lives in memory for one run.
- **Collection runner:** each platform runs on its own, writes in one transaction, and records every
  attempt (last success, last attempt, last error, sign-in expiry). `collect --day7-due` collects only
  platforms with a post in the day-7 window that has no capture there yet (the extra run near day 7).
- **CLI so far:** `foursight connect <platform>` and `foursight collect [--platform x] [--day7-due]`.
  From a checkout: `gradlew.bat :desktopApp:cli --args="connect tiktok"`.
- **YouTube** (verified live November 22, 2026): Google sign-in for the Desktop client (loopback
  `http://127.0.0.1:<port>`, PKCE S256, `access_type=offline`, `prompt=consent`). Two separate
  sign-ins, `connect youtube` (`youtube.readonly`, `yt-analytics.readonly`) and `connect drive`
  (`drive.file`): Google answers a request for both with `invalid_request`, "scopes that cannot be
  requested together". Public videos from the uploads
  playlist. Views, likes and comments from the Data API's current counters (Roger's decision);
  shares, subscribers gained, watch time, average view duration and average percentage viewed from
  Analytics, with the last Analytics day kept as `analytics_through`. An Analytics failure leaves
  those missing with a warning and keeps the rest. Definitions version 2.
- **Drive publish** (verified live November 22, 2026): after each platform is stored, this
  device's snapshot file for the day is written to Drive, once the Drive sign-in exists. The first
  run created the `FourSight` folder, `snapshots/` and `devices/`.
- **Confirmed on the first YouTube run:** `privacyStatus` comes back as `public`; the Top videos
  report accepts `averageViewPercentage` and `engagedViews` with a video filter; Analytics ran
  three days behind (`analytics_through` 2026-11-19 on November 22), and the newest video had no
  Analytics row yet, so its Analytics numbers are missing rather than zero. Still unconfirmed: 50
  ids per `videos.list` call (the channel has fewer than 50 public videos).
- **Meta** (built November 22, 2026): `foursight connect meta`, Facebook Login for Business with the
  configuration id from Credential Manager, `http://localhost:<port>/` redirect (Meta adds the
  trailing slash to a bare address and then rejects a code exchange without it). No PKCE in Meta's
  flow, so the `state` check carries it. Short-lived to long-lived user token, then the Page token
  from `/me/accounts`, which doesn't expire; only the Page token is stored. Graph API v27.0.
- **Facebook** (verified live November 22, 2026): `/{page-id}/video_reels` and each Reel's lifetime
  `video_insights`. `post_impressions_unique` is rejected as "not a valid insights metric" although
  the reference still lists it, so Facebook reach is unavailable (definitions version 3).
  `post_video_followers` is net (it came back -1 for one Reel). `pages_manage_engagement` turned out
  not to be needed for reading insights.
- **Instagram** (built, not verified): the Page's linked account is an old personal account with no
  Reels, so the collector found none. Waiting on Roger to link the professional account he posts
  Reels to. `ig_reels_avg_watch_time` is assumed to be milliseconds, like Facebook's; confirm on the
  first run with Reels.
- **Installed command and schedule** (verified live November 22, 2026): `scripts/install-windows.ps1`
  builds the app plus a jlinked Java runtime for the command (the app image's runtime has no java.exe
  or javaw.exe), installs to `%USERPROFILE%\FourSight\app`, writes `bin\foursight.cmd`, adds `bin` to
  the user PATH, and registers two per-user tasks under `\FourSight\` in Task Scheduler: "Daily
  collection" at 6:00 AM and "Day-7 check" every hour (`collect --day7-due`). Both run windowless
  (javaw with `--log`), only while Roger is signed in, and catch up after the PC was off. Logs go to
  `%USERPROFILE%\FourSight\logs`. `scripts/uninstall-windows.ps1` removes it all and keeps the data.
- **Why not AppData:** the Claude desktop app is a packaged app, and Windows redirects its writes to
  `%LOCALAPPDATA%` into a private copy (`AppData\Local\Packages\Claude_...\LocalCache`) that Task
  Scheduler and other programs can't see. The first install landed there and every task failed with
  0x80070002. The database moved with it, to `%USERPROFILE%\FourSight\foursight.db` (Roger's
  decision), so the CLI, the scheduled runs and the future MCP server all share one database.
- **Tests:** JVM tests for the TikTok, YouTube, Facebook and Instagram clients, auth and collectors
  against doc-shaped fixtures, and the runner.

## Step 4: CLI and MCP (November 22, 2026)

- **Query services in core** (`core/.../query`), shared by the CLI and MCP and later the UI:
  `videos`, `videoStats` (latest or day 7), `sevenDayReport` and `recentSevenDayReports`,
  `accountSummary`, `trends`, `compare`, `freshness` with each platform's API limits, `definitions`,
  `Exporter` (JSON and CSV) and `Matching` (suggestions). `QueryJson` is the one JSON shape: `foursight
  ... --json` and the MCP tools return the same text.
- **CLI:** `videos`, `stats`, `report`, `summary`, `trends`, `compare`, `status`, `definitions`,
  `export`, `refresh` (the same as `collect`), `match` (suggest, `confirm`, `unlink`) and `mcp`, plus
  `connect` and `collect` from step 3. `report` with no video prints the 7-day block for every video
  that locked day 7 in the last 7 days (the Sunday review list).
- **MCP server:** `foursight mcp`, stdio, official Kotlin MCP SDK 0.16.0. Tools `list_videos`,
  `get_video_stats`, `get_seven_day_report`, `get_account_summary`, `get_trends`, `compare_videos`,
  `get_freshness`, `get_metric_definitions`, `refresh` and `export`. All are marked read-only except
  `refresh`. No tool posts, edits, deletes, changes matches or settings, or reads tokens. stdout
  carries only the protocol (System.out is pointed at stderr while it runs; kotlin-logging's startup
  line is turned off).
- **Left for later, Roger's decision (November 22, 2026):** `audience` / `get_audience` (needs new
  collection of demographics and traffic sources; its own piece after step 4), `obsidian` /
  `write_obsidian_stats` (step 5) and `post` / `request_post` (step 8).
- **Decisions, Roger's (November 22, 2026):**
  - `summary --period` means growth during the period: each count gained is the latest capture
    minus the last capture at or before the period start, summed over posts. A post published in
    the period starts from 0. Older posts with no capture before the start are left out and counted,
    never estimated. Data starts November 21, 2026, so longer periods say they're partial.
  - Best and weakest: YouTube and Facebook rank by day-7 followers per 1,000; TikTok and Instagram,
    which don't report followers per video, rank by day-7 shares per 1,000, labeled as a different
    measure. Each platform is ranked on its own; two best and two weakest, as in the Playbook.
- **Choices made here (simple defaults; say if you want them changed):** trends default to
  followers and views over 28 days and cover counts only; a match suggestion needs posts within 48
  hours on different platforms, scored half on publish-time closeness and half on caption word
  overlap; a video can hold one post per platform; exports leave out the raw API responses (some hold
  signed media URLs) and defuse cells a spreadsheet would run as a formula.
- **Tried on the real database (read-only):** `summary`, `report`, `trends` and `match` ran on
  Roger's data; `match` found the TikTok, Short and Reel of each recent video with high or medium
  confidence. The MCP server answered `initialize`, `tools/list` and tool calls over stdio.
- **Tests:** 19 core tests for the new queries (missing stays null, baselines, ranking measures,
  gaps, matching, the 7-day block, export) and 11 desktop tests for the CLI and MCP (usage errors,
  match events, identical CLI and MCP JSON, tool errors, no secrets in any tool output).
- **Next:** with Roger's OK, add FourSight to the Claude app's MCP settings (outside the repo), then
  reinstall so the installed `foursight` has the new commands.

## Audience (November 22, 2026)

- **Collected daily with each platform**, as AUDIENCE snapshots (one per breakdown; item id is the
  breakdown id), so they sync and export like everything else. A failed breakdown is a warning; the
  rest of the platform's run still counts.
- **Checked live on Roger's accounts before building:**
  - YouTube Analytics channel reports work: `viewerPercentage` by `ageGroup,gender`, `views` by
    `insightTrafficSourceType`, and `views` by `country` (sorted, top 25). All cover the 28 days
    ending on the last Analytics day.
  - Facebook `page_follows_country` and `page_follows_city` (period `day`) work; FourSight keeps the
    latest day. Page age and gender are retired (`page_fans_gender_age`, March 14, 2024), and the
    per-Reel `post_video_view_time_by_age_bucket_and_gender` and `post_video_view_time_by_country_id`
    both return code 1 ("An unknown error has occurred.").
  - Instagram `follower_demographics` with `breakdown` age, gender and country, `period=lifetime`,
    `metric_type=total_value` works. The `timeframe` parameter made no difference to follower
    numbers (this_month, last_30_days and last_90_days returned the same); FourSight sends
    `this_month`, which the docs keep.
  - TikTok Personal has no audience data.
- **`foursight audience [--platform] [--top 10]` and MCP `get_audience`.** Each breakdown shows
  its unit, time window, source and capture time; counts also show a labeled share of the groups
  listed (platforms list only their top groups).

## Step 5: Obsidian writer (November 22, 2026)

- **What it touches:** only video notes with a post id in their frontmatter (`tiktok_id`,
  `youtube_id`, `facebook_id`, `instagram_id`), and in them only FourSight's stats fields and the
  `## 7-day numbers` block; plus the Stats Overview note, which it owns. It refuses to replace a
  Stats Overview it didn't write (it looks for its marker line). Writes are atomic and idempotent:
  a second run with the same numbers changes nothing.
- **Decisions, Roger's (November 22, 2026):**
  - Post ids are added when a video is posted, by Roger or by Claude; FourSight doesn't guess which
    note is which video.
  - One field per platform, nothing combined: `views_<p>`, `shares_per_1k_<p>`,
    `followers_per_1k_<p>` (YouTube and Facebook only) for p in tiktok, shorts, reels,
    instagram, plus `stats_day7_date`. A missing number is an empty field, never 0.
  - Writes automatically after each collection (the hourly day-7 check usually does it), and on
    demand with `foursight obsidian [<note>]` or the MCP tool `write_obsidian_stats`.
  - Roger's block lines (where viewers stopped, comments with substance, the lesson) are kept
    when he has filled them in, and so is any other line he adds to the block.
- **Settings** (local database only, never in the repo): `foursight obsidian setup --vault
  <folder> [--notes <folder>] [--overview <note>]`. Defaults: notes in `Social Media Content`,
  overview at `Social Media Content/Stats Overview.md`. Set on Roger's PC November 22, 2026.
- **Tests:** 6 desktop tests on a throwaway vault: fields and block written, Roger's lines and
  other sections untouched, a second run changes nothing, notes without ids or day-7 numbers
  left alone, a hand-made Stats Overview never replaced, and CRLF notes keep their line endings.

## Step 6: Windows UI (November 22, 2026)

- **Shared screens in `ui/`** (Compose Multiplatform, so Android reuses them in step 7), reading
  only the core query services through an `AppServices` interface; the Windows side
  (`DesktopAppServices`) adds sign-in, the save dialog, opening links and the vault setting.
- **Overview:** the spec's date filters (Today, Yesterday, Last 7, 28, 90, a custom range, all
  history) and platform filters; followers per platform and summed; counts gained in the range,
  summed only as counts; followers over time (one chart per platform) and views gained per day;
  the best and weakest videos per platform. A range starting before collection did is labeled
  partial, and charts start at the first capture instead of showing days before it as gaps.
- **Platforms:** followers, every post with its latest counts and engagement rate, what the platform
  doesn't report and why, its audience breakdowns as bars, and its limits.
- **Content:** the library with search; match suggestions with a Confirm button (writes the same
  events as `foursight match confirm`); a video page with latest or day-7 numbers per platform,
  counts summed across platforms, views over time by days since publishing, the day-7 numbers
  against the same platform's other posts (with the sample size), links to the posts, and a compare
  view (differences, not causes).
- **Posting:** says plainly it's step 8, with each platform's expected limit; no pending posts yet.
- **Accounts:** each sign-in with its latest capture, last success and attempt, last error, sign-in
  expiry and limits; Sign in (opens the browser, same flow as `foursight connect`) and Refresh now,
  plus Refresh all. A refresh also runs the Obsidian writer, like the scheduled runs.
- **Settings:** theme (match Windows, light, dark), the Obsidian vault, Drive status, JSON and CSV
  export through a save dialog, and every metric's definition and source.
- **Charts** are drawn in FourSight's own code (Roger's decision): gaps are dotted lines with a
  hollow marker, and hovering or tapping shows the value, date, capture time and source.
- **Roger's decisions (November 22, 2026):** charts drawn in-house; closing the window quits (no
  tray icon, since the scheduled tasks collect without the app); shortcuts on the desktop and in the
  Start menu, added by `scripts/install-windows.ps1` and removed by the uninstall script. The
  install script now also stops a running FourSight window before replacing the files.
- **New core queries:** `accountSummaryBetween` and `trendsBetween` (any range), `postHistory`,
  and `versusAverage` (a post's day-7 numbers against the platform's other day-7 posts; averaged
  within one platform only). 4 new tests.
- **Checked on Roger's PC:** the installed app opens from its shortcut and shows Overview, Content
  (list and a video page), and Accounts on the real database.

## Look and feel (November 22, 2026)

- **Roger's request:** a proper icon, his TikTok colors, and a Microsoft 365 feel.
- **Colors:** the Retro Sunset brand from his Video Toolkit note (burnt orange `#E0702F` as the one
  accent, dark brown `#2B1B12` for the header bar and the dark theme, cream `#F3E3C3` for the
  wordmark), on Microsoft 365-style neutral grays and white cards.
- **Icon:** a burnt-orange sun cut by four stripes on dark brown, drawn as pure shapes by
  `scripts/make-icon.py` (window and taskbar PNG, installer ICO). The app bar draws the same mark.
- **Microsoft 365 look:** a brown app bar with the wordmark, a left navigation pane with Segoe Fluent
  Icons and an orange selection bar, Segoe UI text, 4 px corners on buttons and chips, bordered cards,
  KPI tiles with an accent edge, and Power BI-style charts (gridlines, a soft fill, two per row).
- **Fonts** come from the PC's own font folder (Segoe UI, Segoe Fluent Icons, Cooper Black for the
  wordmark); nothing is bundled, and a missing font falls back to the default.

## Step 7: Android (in progress)

- **Roger's decisions (November 22, 2026):** sync first, then the phone's own sign-ins (YouTube, then
  Meta, then TikTok), then the widget; his own release key; installs over USB.
- **Release key:** kept outside the repo; its path and password are in Roger's user-level
  `~/.gradle/gradle.properties` (`foursight.keystore`, `foursight.keystore.password`,
  `foursight.key.alias`). Without them the release build is unsigned (CI). Its SHA-1 is registered
  with Google's "FourSight Android" client (created November 22, 2026).
- **Part 1, verified on Roger's Pixel 10 Pro XL (November 22, 2026):** the shared screens, with a
  bottom tab bar on narrow screens; its own database; Google Drive through Google's Android
  AuthorizationClient (`drive.file` only, no client ID, secret or refresh token on the phone);
  merges the PC's files on launch, on demand (Accounts, "Sync from Drive") and twice a day with
  WorkManager. The first sync read 9 files: 385 snapshots and 29 edits, and the phone showed the same
  numbers as the PC. That settles the spec's open item: files one Google client creates under
  `drive.file` are visible to the other client of the same project.
- **Not yet:** the phone's own platform sign-ins and daily collection (part 2), the home-screen
  widget (part 3). The background sync's silent token refresh still needs a day on the phone to
  confirm.

## To verify live

From the spec's "Still open" list; neither can be known until it's tested.

- [x] **TikTok first sign-in** (verified November 22, 2026): in Sandbox, with One Fat Man's Journey
  as target user, the sign-in granted `user.info.basic`, `user.info.stats` and `video.list`, and the
  first collection returned every public video with views, likes, comments and
  shares, plus the follower count. Two live findings: the token endpoint rejects a `Content-Type`
  with a `charset` parameter (`invalid_request`, "The request parameters are malformed"), and the
  refresh token came back valid for 365 days, not the 180 the docs state (FourSight stores what
  the response says). The vault note's "Still open" item can be ticked.
- [x] **Drive across devices** (verified November 22, 2026): the phone's first sync read the PC's
  files (9 files, 385 snapshots, 29 edits).

## API details checked

Checked against the live reference pages on November 21, 2026.

- **TikTok:** `GET https://open.tiktokapis.com/v2/user/info/` (`follower_count`, `likes_count`,
  `video_count` need `user.info.stats`; `username` needs `user.info.profile`, which the app doesn't
  have). `POST /v2/video/list/` (`max_count` up to 20, `cursor` is a millisecond timestamp) and
  `POST /v2/video/query/` (`filters.video_ids`); video fields `id`, `create_time`, `title`,
  `video_description`, `duration`, `cover_image_url` (6-hour TTL), `share_url`, `embed_link`,
  `view_count`, `like_count`, `comment_count`, `share_count`. Rate limit 600 requests per minute per
  endpoint. Access token 2 hours, refresh token 180 days, rotated on every refresh. Desktop redirect
  `localhost`/`127.0.0.1` with a port (wildcard allowed); PKCE S256 with **hex-encoded** SHA256.
- **YouTube Analytics:** `GET https://youtubeanalytics.googleapis.com/v2/reports`. Metrics `views`,
  `engagedViews`, `estimatedMinutesWatched`, `averageViewDuration`, `averageViewPercentage`,
  `subscribersGained`, `subscribersLost`, `likes`, `comments`, `shares`, `audienceWatchRatio`,
  `relativeRetentionPerformance`, `viewerPercentage`. Dimensions `creatorContentType` (value
  `SHORTS`), `insightTrafficSourceType`, `elapsedVideoTimeRatio`.
- **YouTube Data API:** `channels` `statistics.subscriberCount` (rounded to 3 significant figures),
  `contentDetails.relatedPlaylists.uploads`; `videos` `statistics.viewCount`, `likeCount`,
  `commentCount`, `contentDetails.duration`.
- **Google OAuth (desktop):** loopback redirect `http://127.0.0.1:<port>`, PKCE `S256`.
- **Google Drive v3:** `GET /drive/v3/files` with `q` (`'<id>' in parents`, `trashed = false`,
  quotes and backslashes escaped with a backslash), `POST /upload/drive/v3/files?uploadType=multipart`,
  `PATCH /upload/drive/v3/files/{id}`, `GET /drive/v3/files/{id}?alt=media`, file field `version`.
  The `drive.file` wording doesn't say whether access is per project or per OAuth client.
- **Meta:** Graph API v27.0 is current. Reels `video_insights` (all lifetime):
  `blue_reels_play_count`, `fb_reels_replay_count`, `fb_reels_total_plays`,
  `post_impressions_unique`, `post_video_avg_time_watched`, `post_video_followers`,
  `post_video_likes_by_reaction_type`, `post_video_retention_graph`, `post_video_social_actions`,
  `post_video_view_time`. Page fields `followers_count`, `instagram_business_account`.
- **Instagram (API with Facebook Login):** media insights for Reels `views`, `reach`, `likes`,
  `comments`, `shares`, `saved`, `total_interactions`, `ig_reels_avg_watch_time`,
  `ig_reels_video_view_total_time`, `reels_skip_rate`, `reposts`, `total_views`; `follows` covers
  feed posts and stories only; `impressions` deprecated for media after July 2, 2024. User insights
  `follows_and_unfollows`, `follower_demographics`, `engaged_audience_demographics`, `reach`,
  `views`, `total_interactions` (`impressions` deprecated from v22.0); data may lag 48 hours; some
  metrics need 100+ followers; missing data comes back empty, not 0. IG User field `followers_count`.
