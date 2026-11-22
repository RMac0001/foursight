---
created: '2026-11-21'
status: spec
tags:
  - project
  - creator-stats
  - foursight
  - social-media
title: FourSight Spec
type: project
updated: '2026-11-21'
---
# FourSight: Spec

> **Build-only copy.** The master is Roger's vault spec note. This copy leaves out the Setup
> status section, account and app IDs and vault paths, because the repo is public. When the
> master changes, recopy it here; don't edit this file on its own.

*Roger's personal creator-analytics and posting app for One Fat Man's Journey. This note is the source of truth: Claude writes and maintains the spec here (with Roger's approval), and Claude Code builds from it. Replaces the November 21 Creator Stats plan. Last revised November 21, 2026.*

## Decisions locked

- **Name:** FourSight.
- **Personal use only.** One user: Roger. Metricool is the feature reference, never a vendor. No subscriptions, payments, multi-user features or public hosting.
- **Kotlin Multiplatform + Compose Multiplatform.** One Kotlin codebase; Windows and Android share every screen.
- **Same features on Windows and Android.** Windows-only extras: the CLI, the local MCP server and the Obsidian writer. Android-only extra: the home-screen widget.
- **Google Drive is the shared store.** Both apps read and write a FourSight folder in Roger's Google Drive so they show the same data (see Sync). Tokens never go to Drive. (Box was dropped on November 21, 2026: its developer console wouldn't save app settings without a separate Box Developer account.)
- **Four platforms:** TikTok, YouTube, Facebook Page, Instagram (professional account, linked to the Facebook Page).
- **TikTok stays a Personal account** for now. Switch to Business only if the basic numbers prove too thin.
- **Daily background collection** on both devices, plus refresh on launch (if stale) and on demand.
- **One v1, no phases.** Everything in this spec ships in v1. The build order below exists only because some pieces depend on others, and posting depends on platform reviews outside our control.
- **No embedded AI.** Analysis by Claude happens through the MCP server. The app is fully useful without Claude.
- **The channel comes first.** The first thing that must work end to end is replacing the Sunday browser review.
- **Code lives in a public GitHub repo** (`RMac0001/foursight`), public so GitHub Actions builds are free. A build-only copy of this spec goes in the repo as `docs/SPEC.md`, with `CLAUDE.md` pointing to it: everything Claude Code needs to build, but without the Setup status section, account and app IDs, or vault paths. This vault note stays the full master copy.
- **Automated builds (GitHub Actions):** on every push, build and test the shared code, the Android app and the desktop app; on release tags, build the Windows installer too.
- **Who does what:** Claude writes and maintains this spec and walks Roger through setup. Claude Code writes all the code, on Roger's PC.

---

## What each platform gives (checked November 21, 2026)

| | Analytics we can read | Posting |
| --- | --- | --- |
| **TikTok (Personal, Display API)** | Per video: views, likes, comments, shares. Profile: follower count. **Not available:** watch time, % watched, traffic source, comments, "most viewers stopped at." Today's totals only, no history. | Content Posting API. Unaudited apps post **private** only; the audit takes days to weeks. "Upload to inbox as a draft" may be the workable route: test it. Sandbox mode doesn't allow public posting. |
| **YouTube (Data API v3 + Analytics API)** | Views, watch time, average view duration, audience retention, subscribers gained, traffic sources, demographics, with history. | `videos.insert`. The reference page says uploads from unverified projects created after July 28, 2020 are locked to private until the project passes an audit. **Plan for YouTube posting needing Google's audit**; one test upload confirms it. |
| **Facebook Page (Graph API)** | Reels via `video_insights`: plays, reach, average and total minutes viewed, reactions, comments, shares, with lifetime history. Page Insights needs 100+ Page likes (the Page has about 3.5k). Meta deprecated a batch of Page Insights metrics on June 15, 2026. **Claude Code verifies every metric name against the live reference before using it.** | Reels Publishing API. Limit: 30 API-published posts per 24 hours. |
| **Instagram (professional, API with Facebook Login)** | Reels: views, reach, likes, comments, shares, saves, average watch time, total watch time, skip rate. Account: followers, views, interactions, demographics (`instagram_manage_insights`, Facebook Login only). Some totals (including boosted engagement) exist only with Facebook Login. Account-level metrics are kept only 90 days, and media metrics can lag up to 48 hours. Missing metrics come back as an empty set, not 0. | Content Publishing API (`instagram_content_publish`). |

**Access levels:** Meta's Standard Access covers apps used only by people with a role on the app, so no App Review is needed for Roger's own Page and Instagram account. TikTok's Sandbox mode lets Roger's own account (as a target user) use the app without review; whether the Display API keeps working long-term without a production review is **an open question to settle during setup.**

**What this means for history:** YouTube and Facebook return history. TikTok returns only today's totals, and Instagram's account metrics expire after 90 days, so **FourSight takes a daily snapshot of every platform.** A day nobody collected stays a gap (dotted line, flagged), never invented.

---

## Architecture

```
foursight/
  core/          commonMain: platform clients (Ktor), models, SQLite (SQLDelight),
                 metrics engine, collector, sync engine, query services
                 (shared by the UI, CLI and MCP)
  ui/            Compose Multiplatform: every screen, shared by both apps
  desktopApp/    Windows: window + tray, scheduled collector, CLI, MCP server
                 (official Kotlin MCP SDK, stdio), Obsidian writer
  androidApp/    Android: same screens, WorkManager daily collector,
                 Glance home-screen widget
  docs/          SPEC.md (copy of this note), metric definitions
```

- **One engine.** The UI, CLI and MCP all call the same query services in `core`. No calculation exists twice.
- **Tokens stay on the device:** Windows Credential Manager (DPAPI) and the Android Keystore. Each device signs in to each platform itself. Tokens never appear in Google Drive, exports, logs or MCP output.
- **Sign-in:**
  - Windows: loopback redirect. TikTok's desktop rules allow only `localhost` or `127.0.0.1`, require a port, support a wildcard port, and require PKCE. Register `http://127.0.0.1:*/callback/`. Google also accepts loopback redirects, and Meta allows `http://localhost` automatically in development mode.
  - Android: TikTok requires an App Link or deep link. Google uses its Android OAuth client. Meta uses its official Facebook Android SDK.
- **Google Drive access:** the Drive REST API (v3) through Ktor, from `commonMain`, on both platforms, using the same Google sign-in as YouTube (one consent covers both). Scope `drive.file` only: it's non-sensitive and lets FourSight see just the files it creates, never the rest of Roger's Drive. Don't rely on the Google Drive for desktop sync folder. Drive sits behind a `SyncStore` interface so another provider can be swapped in with one new adapter (Roger may consolidate his cloud storage).
- **Daily collection:** Windows Task Scheduler runs `foursight collect` once a day. Android uses WorkManager periodic work (Doze may delay it, which is fine for a daily snapshot). Each platform is collected independently: one failing never blocks the others.

## Sync through Google Drive

**Rule: never put a live database in Drive.** Each device keeps its own SQLite database, and Drive holds only plain files, each with exactly one writer.

```
Google Drive: /FourSight/
  snapshots/<device-id>/<platform>/<yyyy-mm-dd>.json   one file per device, platform and day
  events/<device-id>/<yyyy-mm-dd>.jsonl                append-only user edits
  devices/<device-id>.json                             one per device: its id and name (written once by that device)
```

- **Snapshots:** every collection writes the raw API values with a capture timestamp, the device id and the metric definitions version.
- **Events:** user edits (confirming a cross-platform match, unlinking, notes) are written as timestamped events. Conflicts resolve last-writer-wins by timestamp, and the losing event is kept in the log.
- **Merging:** on launch, after collection and on demand, each app reads files it hasn't seen yet and merges them into its database. For a given platform, item and day, the latest capture wins. Merging the same files twice changes nothing.
- **Derived data is never synced.** Day-7 locks, per-1,000 numbers and rankings are computed from the merged snapshots, so both devices get identical results.
- **Offline:** each device works on its own and catches up at the next sync. A sync failure never touches local data.
- **Restore:** a fresh install (or a wiped device) signs in to Google and rebuilds its database from the Drive folder. That's the backup and restore.

---

## Features (all in v1)

### Accounts
- Connect TikTok, YouTube, the Facebook Page and Instagram. Google Drive sync rides on the same Google sign-in as YouTube.
- Per connection: status, last successful refresh, last attempt, last error, token expiry, and the API limits that apply.

### Content library
- One entry per original video, grouping its TikTok, Short, Reel and Instagram Reel.
- Each vault video note gets `tiktok_id`, `youtube_id`, `facebook_id` and `instagram_id` frontmatter.
- The app suggests matches by publish time and caption, and Roger confirms. Nothing merges automatically when confidence is low.
- Each entry stores titles or captions, publish dates, post IDs, URLs, content type (short-form now; long-form is planned for later, so the model supports it), duration and thumbnails.

### Per-video view
- A combined overview, platform-by-platform numbers, performance over time, engagement, retention where available, comparison with your averages, and links to the original posts.

### Day-7 lock
- On day 7, the app freezes a snapshot. That's the Playbook's 7-day log (R7.2), with per-1,000 math (saves, shares, followers).

### Overview (includes trends)
- Totals per platform, followers over time, best and weakest videos by followers per 1,000 (the Playbook's scoreboard), and trend charts.
- **Date filters:** Today, Yesterday, Last 7, Last 28, Last 90, Custom range, All history. (28 instead of 30, to match YouTube's and TikTok's own analytics.)
- **Platform filters:** all, any single platform, or any combination.
- **Combining rules:** counts (views, likes, comments, shares, followers) can be summed across platforms. Rates and durations (average view duration, retention, engagement rate) are never summed or averaged across platforms; they're shown per platform. The UI marks which is which.
- Interactive charts: tap or hover any point to see its value, date, source and capture time.

### Comparisons and indicators
- Compare videos, groups of videos, platforms, publishing periods, and recent against historical performance. The short- vs. long-form comparison is built into the model and turns on once long-form videos exist.
- Indicators: views per day, engagement rate, follower growth rate, per-1,000 rates, performance against your own average, retention changes, and differences between platforms.
- Every number is computed in code, stored with the inputs it came from, and has a written definition.
- **Honesty rules:** show the sample size, comparison period, missing-data markers, data freshness and the metric definition. Describe patterns; never claim causes.

### Audience
- Demographics and traffic sources where the API provides them (YouTube, Facebook, Instagram). Marked as unavailable for TikTok Personal.

### Posting
- Post to Facebook Reels, YouTube Shorts, Instagram Reels and TikTok from either app.
- **Every post needs an explicit confirmation in the app.** An MCP post request creates a pending post that Roger approves in the app; the MCP can never publish on its own.
- Per platform, the app shows whether posting is fully live or limited (private-only, drafts only, awaiting review) and never hides the limit.

### Obsidian writer (Windows)
- After the day-7 lock, writes the `## 7-day numbers` block into the video note, updates the stats fields in its frontmatter (`views_tiktok`, `views_shorts`, `views_reels`, `views_instagram`, `followers_per_1k`...), and regenerates the vault's Stats Overview note.
- It touches only the fields, the block and the one note it owns. Re-running it rewrites the same values.

### Android widget
- The latest video's views per platform, total followers, and the change since yesterday. Tap it to open the app.

### Export
- JSON and CSV export of any view or of everything. Exports never contain tokens or secrets.

### Look and feel
- Light and dark themes, clean typography, readable charts, clear loading, empty and error states. Windows uses the space; Android is built for touch.
- **Sections:** Overview, Platforms, Content, Posting, Accounts, Settings.

---

## MCP server and CLI (Windows)

Both run without the dashboard open, use the same database and query services, return definitions and timestamps with every number, and give meaningful errors. The CLI prints readable output by default and JSON with `--json`.

| MCP tool | CLI | What it does |
| --- | --- | --- |
| `list_videos` | `foursight videos [--platform] [--since]` | Every video, grouped across platforms |
| `get_video_stats` | `foursight stats <video> [--day 7]` | One video's numbers, live or at a given day |
| `get_seven_day_report` | `foursight report <video>` | The Playbook's 7-day block, ready to paste |
| `get_account_summary` | `foursight summary [--period 28d]` | Followers, totals, best and weakest videos |
| `get_trends` | `foursight trends [--days 28] [--metric]` | Trends over time |
| `compare_videos` | `foursight compare <a> <b> [--metric]` | Side by side |
| `get_audience` | `foursight audience [--platform]` | Demographics and traffic sources where available |
| `get_freshness` | `foursight status` | Last refresh, errors and limits per platform |
| `get_metric_definitions` | `foursight definitions` | How every number is calculated |
| `refresh` | `foursight refresh [--platform]` | Pull fresh numbers now |
| `export` | `foursight export --format json\|csv` | Export data (no secrets) |
| `write_obsidian_stats` | `foursight obsidian <video>` | Write the 7-day block and fields to the vault |
| `request_post` | `foursight post ...` | Creates a pending post; Roger confirms it in the app |
| | `foursight connect <platform>` | Sign in to a platform (opens the browser) |
| | `foursight collect` | The daily collection run (used by Task Scheduler) |

**The MCP cannot:** publish without in-app confirmation, delete or edit posts, change account or app settings, modify stored metrics, or read tokens. It can only change local data through `refresh` (the normal collector) and the vault through `write_obsidian_stats`.

When this works, the Sunday scoreboard review switches from TikTok Studio in Chrome to these tools. TikTok Studio stays the only source for "most viewers stopped at."

---

## Setup only Roger can do (Claude walks through each one)

1. **TikTok for Developers:** create the app; add Login Kit and the Display API (scopes `user.info.basic`, `user.info.stats`, `video.list`) and the Content Posting API; register the desktop redirect `http://127.0.0.1:*/callback/` and an Android App Link; create a sandbox and add the One Fat Man's Journey account as a target user; find out whether reading your own account needs a production review.
2. **Google Cloud:** create a project; enable YouTube Data API v3, the YouTube Analytics API and the Google Drive API (scope `drive.file`); set up the OAuth consent screen and **publish it to production** (an external app left in "Testing" loses its sign-in after about 7 days); create two OAuth clients, Desktop and Android.
3. **Meta for Developers:** create one app; add Facebook Login and the Instagram product (API with Facebook Login); request `pages_show_list`, `pages_read_engagement`, `read_insights`, `instagram_basic`, `instagram_manage_insights`, and for posting `pages_manage_posts`, `publish_video` and `instagram_content_publish` (confirm exact names when setting up); confirm Standard Access works for your own Page and Instagram account as the app's admin.


## Build order (one v1, built in dependency order)

1. **Setup:** the three developer apps above (done). Nothing else can be tested live without them.
2. **Core:** models, SQLite schema and migrations, the metrics engine and definitions, the `SyncStore` and Google Drive adapter, secure token storage.
3. **Collectors:** one per platform, with refresh status, error recording and daily scheduling on Windows.
4. **CLI and MCP:** the first usable milestone. The Sunday review runs from here.
5. **Obsidian writer.**
6. **Windows UI:** every section.
7. **Android:** the same UI, WorkManager collection, the widget.
8. **Posting:** all four platforms, live as each platform's review allows.
9. **Hardening:** parity testing, failure and recovery testing, documentation.

## Testing and completion

- Unit tests for every metric calculation; adapter tests against recorded API responses; token-renewal tests; migration tests; sync merge tests (duplicate files, conflicting events, missing days); refresh failure and recovery tests; MCP tool tests; CLI tests; Windows and Android app tests.
- **Mock data is always labeled, lives only in tests and developer builds, and never appears as live data.**
- A failed refresh or sync never overwrites good data; writes are transactional.
- **A feature is done only when** it works as specified, handles its errors, has validated output, passes its tests, works on both platforms where it applies, and has its limits documented.

## Rules for Claude Code

- Build from this spec. If something is ambiguous, list the options and tradeoffs and ask Roger. Don't make architectural calls alone.
- Check the current platform API reference before using any endpoint or metric name. Don't invent API capabilities.
- Don't fabricate, estimate without a label, or silently substitute analytics values.
- No scraping or unofficial sign-in workarounds without Roger's explicit approval.
- No embedded AI, no server, no extra infrastructure, no commercial features.
- Ask Roger before creating, changing or deleting files outside the repo (including the vault).
- **The repo is public.** No secrets, tokens, client keys, account IDs or personal data in code, commits, test fixtures or build logs (Actions logs are public too). Recorded API responses used in tests must be scrubbed. Turn on GitHub secret scanning and push protection for the repo.
- American English in all code comments, UI text and docs.

## Tabled ideas (not in v1, kept so they aren't forgotten)

- **Best time to post** from Roger's own results (views and followers per 1,000 by posting hour and day).
- **Calendar view** tied to the Chapter Plan note.
- **Comment reader:** YouTube, Facebook and Instagram comments in one list (TikTok Personal doesn't offer comments).
- **Competitor tracking:** YouTube public channels only. TikTok's public-data API is for academic research, so it's out.

## Resolved questions (November 21, 2026)

- **TikTok Sandbox:** the docs list only the Content Posting API for public videos and the Data Portability API as unavailable in Sandbox, and mention no expiry for sandboxes or tokens. Plan: run FourSight in Sandbox with the One Fat Man's Journey account as a target user. Confirm on the first live sign-in.
- **YouTube uploads:** the docs say uploads from unverified projects are private by default until the project passes Google's audit. Plan for YouTube posting needing the audit; one test upload confirms it.
- **Sync storage:** switched from Box to Google Drive (see Decisions). Drive uses the existing Google sign-in and the non-sensitive `drive.file` scope.
- **Meta on Android:** use Meta's official Facebook Android SDK for sign-in. Meta's redirect-URI rules apply only to custom OAuth flows, not to apps using its SDKs.
- **Facebook metrics after June 15, 2026:** the cuts were mainly unique impressions, 3-second video views and engagement metrics (Page Engaged Users, Post Engaged Users, Page Consumptions, Page CTA Clicks). The Reels metrics FourSight relies on aren't on the deprecation lists found. Claude Code still verifies every field name against the live reference.

## Still open

- [x] **Meta on Windows** (resolved November 21, 2026): Meta allows `http://localhost` redirects automatically while the app is in development mode.
- [x] **TikTok first sign-in** (resolved November 22, 2026): in Sandbox, the sign-in granted `user.info.basic`, `user.info.stats` and `video.list`, and the first collection returned every public video with its views, likes, comments and shares, plus the follower count. The refresh token came back valid for 365 days (the docs say 180).
- [ ] **Drive across devices:** confirm that files the Windows client creates under `drive.file` are visible to the Android client (both are clients of the same Google project). Test this before building the merge logic on it; if it fails, use one shared Drive folder that both clients open explicitly.
