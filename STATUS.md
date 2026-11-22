# Implementation status

Tracks what's built against [`docs/SPEC.md`](docs/SPEC.md), what's been verified against the
live APIs, and what still needs testing. Update it with every change that moves a build-order
step forward.

## Build order

| Step | State |
| --- | --- |
| 1. Setup (developer apps) | Done by Roger, November 21, 2026 (details in the vault spec's Setup status) |
| 2. Core | Built and tested on branch `claude/step-2-core` (see below) |
| 3. Collectors | In progress on branch `claude/step-3-collectors`: TikTok built and verified live |
| 4. CLI and MCP | Not started |
| 5. Obsidian writer | Not started |
| 6. Windows UI | Not started (placeholder shell with the six sections) |
| 7. Android | Not started (placeholder app showing the shared shell) |
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

## Step 3: collectors (in progress)

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
- **Not yet:** the YouTube, Facebook and Instagram collectors; Drive publishing after collection
  (needs the Google sign-in); the installed `foursight` console command and the Task Scheduler
  commands for Roger to run.
- **Tests:** 57 JVM tests (TikTok client, auth and collector against doc-shaped fixtures; runner).

## To verify live

From the spec's "Still open" list; neither can be known until it's tested.

- [x] **TikTok first sign-in** (verified November 22, 2026): in Sandbox, with One Fat Man's Journey
  as target user, the sign-in granted `user.info.basic`, `user.info.stats` and `video.list`, and the
  first collection returned every public video with views, likes, comments and
  shares, plus the follower count. Two live findings: the token endpoint rejects a `Content-Type`
  with a `charset` parameter (`invalid_request`, "The request parameters are malformed"), and the
  refresh token came back valid for 365 days, not the 180 the docs state (FourSight stores what
  the response says). The vault note's "Still open" item can be ticked.
- [ ] **Drive across devices:** files the Windows client creates under `drive.file` are visible
  to the Android client. Test before building the merge logic on it.

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
