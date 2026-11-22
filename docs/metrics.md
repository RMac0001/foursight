# Metric definitions

How every number in FourSight is sourced and calculated. The code is the source of truth
(`core/.../metrics/Metrics.kt` and `Calculations.kt`); a test checks that every metric id in the
code appears in this file. Definitions version: **3**. Sources checked against each platform's live
API reference on November 21, 2026 (YouTube rechecked November 22, 2026).

**Rules that apply everywhere**

- A missing value is shown as missing, never as zero. Instagram returns an empty set for missing
  metrics; FourSight keeps that as missing.
- Counts can be summed across platforms. Rates and durations are only shown per platform.
- Every computed number keeps the inputs it came from and the definition below.
- Patterns are described, never causes.

## Stored metrics

| Id | Kind | TikTok | YouTube | Facebook | Instagram |
| --- | --- | --- | --- | --- | --- |
| `views` | count | `view_count` | `statistics.viewCount` (Data API, current) | `fb_reels_total_plays` (includes replays) | `views` |
| `likes` | count | `like_count` | `statistics.likeCount` (Data API, current) | `post_video_likes_by_reaction_type` (all reactions) | `likes` |
| `comments` | count | `comment_count` | `statistics.commentCount` (Data API, current) | `post_video_social_actions` (comments) | `comments` |
| `shares` | count | `share_count` | `shares` (Analytics) | `post_video_social_actions` (shares) | `shares` |
| `saves` | count | not available | not available | not available | `saved` |
| `followers_gained` | count | not available per post | `subscribersGained` (Analytics, video filter) | `post_video_followers` (net, can be negative) | not available for Reels |
| `reach` | count | not available | not available | not available (retired, see notes) | `reach` |
| `watch_time_seconds` | duration | not available | `estimatedMinutesWatched` × 60 (Analytics) | `post_video_view_time` ÷ 1,000 | `ig_reels_video_view_total_time` |
| `average_view_duration_seconds` | duration | not available | `averageViewDuration` (Analytics) | `post_video_avg_time_watched` ÷ 1,000 (includes replays) | `ig_reels_avg_watch_time` |
| `average_view_percentage` | rate | not available | `averageViewPercentage` (Analytics) | retention graph only | not available |
| `skip_rate` | rate | not available | not available | not available | `reels_skip_rate` |
| `followers` (account) | count | `follower_count` | `subscriberCount` (rounded by YouTube to 3 significant figures) | Page `followers_count` | IG User `followers_count` |

Notes from the references:

- **Views aren't the same thing on each platform.** Facebook's total plays include replays; YouTube
  also offers `engagedViews` (views past the first frame). A cross-platform view total is a sum of
  four different counts and is labeled that way.
- **Instagram** media metrics can lag up to 48 hours, and account metrics need at least 100
  followers. `ig_reels_video_view_total_time`, `views` and `total_interactions` are marked "in
  development" by Meta.
- **Facebook** Reels insights are lifetime totals only, so a Reel's past days can't be rebuilt.
- **Facebook followers gained** is net: `post_video_followers` came back as -1 for one Reel on
  November 22, 2026, so it's follows minus unfollows from the Reel, not new follows alone.
- **Facebook reach** (definitions version 3): the Video Insights reference still lists
  `post_impressions_unique`, but on November 22, 2026 the API rejected it as "not a valid insights
  metric" (unique impressions were in Meta's June 15, 2026 cuts). Facebook reach is unavailable.
- **Facebook likes, comments and shares** come from objects of counts per type
  (`post_video_likes_by_reaction_type`, `post_video_social_actions`). Meta leaves out types with no
  count (a Reel with one share and no comments returns `{"SHARE": 1}`), so a type missing from a
  returned object is zero; if the metric itself is missing, the value is missing.
- **TikTok** returns today's totals only, so a TikTok's past days can't be rebuilt.
- **YouTube** is the only platform with per-day history per video.
- **YouTube has two sources** (Roger's decision, November 22, 2026, definitions version 2). Views,
  likes and comments come from the Data API's current counters, so a day-7 number is from day 7.
  Shares, subscribers gained, watch time, average view duration and average percentage viewed come
  from YouTube Analytics, which runs behind by a period Google doesn't publish. Each snapshot keeps
  `analytics_through`, the last day Analytics had data for when it was captured, and those numbers
  are shown with that date. Analytics days are Pacific time.
- **YouTube `subscribersGained` per video** counts only subscriptions made on that video's watch
  page (Analytics' rule when a report is filtered to a video).
- **YouTube content type:** short-form when the video is 3 minutes or less (YouTube's Shorts
  limit), long-form otherwise. This is a rule on duration, not YouTube's own label.
- **YouTube posts** are the channel's public videos; private and unlisted ones are left out, as
  TikTok's API lists public posts only.

## Calculations

| Id | Definition |
| --- | --- |
| `*_per_1k` | count ÷ views × 1,000, using the post's own views from the same snapshot. No value when views are missing or zero. |
| `views_per_day` | views ÷ days since publishing, using the snapshot's exact capture time. |
| `engagement_rate` | (likes + comments + shares + saves) ÷ views × 100. Saves count only on platforms that report them, so the rate compares within one platform, not across platforms. |
| `account_follower_change` | account followers at the later capture − at the earlier capture. Counts every follow in the window, whatever brought it in; never attributed to a post. |
| `*_total` | sum of a count across the selected platforms. Missing if any platform's value is missing. |

## Day-7 lock

- The day-7 number is the **first snapshot captured at least 7 × 24 hours after publishing**, and
  no more than 24 hours after that (so by day 8.0). Its exact age is kept and shown (for example
  "day 7.4").
- Before day 7: not yet due. Between day 7.0 and 8.0 without a capture: pending. After day 8.0
  without a capture in the window: **missed**, shown as a gap and never filled in.
- **Followers per 1,000** uses `followers_gained` where the platform reports it per post (YouTube,
  Facebook). TikTok and Instagram show "not available per post" plus the labeled
  `account_follower_change` from the last capture before publishing to the day-7 capture.
