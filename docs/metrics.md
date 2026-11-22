# Metric definitions

How every number in FourSight is sourced and calculated. The code is the source of truth
(`core/.../metrics/Metrics.kt` and `Calculations.kt`); a test checks that every metric id in the
code appears in this file. Definitions version: **1**. Sources checked against each platform's live
API reference on November 21, 2026.

**Rules that apply everywhere**

- A missing value is shown as missing, never as zero. Instagram returns an empty set for missing
  metrics; FourSight keeps that as missing.
- Counts can be summed across platforms. Rates and durations are only shown per platform.
- Every computed number keeps the inputs it came from and the definition below.
- Patterns are described, never causes.

## Stored metrics

| Id | Kind | TikTok | YouTube | Facebook | Instagram |
| --- | --- | --- | --- | --- | --- |
| `views` | count | `view_count` | `views` | `fb_reels_total_plays` (includes replays) | `views` |
| `likes` | count | `like_count` | `likes` | `post_video_likes_by_reaction_type` (all reactions) | `likes` |
| `comments` | count | `comment_count` | `comments` | `post_video_social_actions` (comments) | `comments` |
| `shares` | count | `share_count` | `shares` | `post_video_social_actions` (shares) | `shares` |
| `saves` | count | not available | not available | not available | `saved` |
| `followers_gained` | count | not available per post | `subscribersGained` (video filter) | `post_video_followers` | not available for Reels |
| `reach` | count | not available | not available | `post_impressions_unique` | `reach` |
| `watch_time_seconds` | duration | not available | `estimatedMinutesWatched` × 60 | `post_video_view_time` ÷ 1,000 | `ig_reels_video_view_total_time` |
| `average_view_duration_seconds` | duration | not available | `averageViewDuration` | `post_video_avg_time_watched` ÷ 1,000 (includes replays) | `ig_reels_avg_watch_time` |
| `average_view_percentage` | rate | not available | `averageViewPercentage` | retention graph only | not available |
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
- **TikTok** returns today's totals only, so a TikTok's past days can't be rebuilt.
- **YouTube** is the only platform with per-day history per video.

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
