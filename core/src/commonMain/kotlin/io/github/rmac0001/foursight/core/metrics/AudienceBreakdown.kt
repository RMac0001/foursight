package io.github.rmac0001.foursight.core.metrics

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.SocialPlatform.FACEBOOK
import io.github.rmac0001.foursight.core.SocialPlatform.INSTAGRAM
import io.github.rmac0001.foursight.core.SocialPlatform.TIKTOK
import io.github.rmac0001.foursight.core.SocialPlatform.YOUTUBE

/**
 * An audience breakdown a platform provides (spec: Audience). Stored as an AUDIENCE snapshot whose
 * item id is [id] and whose values map each group (an age band, a country, a traffic source) to a
 * number in [unit]. Sources were checked live on November 22, 2026 (see STATUS.md).
 */
enum class AudienceBreakdown(
    val platform: SocialPlatform,
    val id: String,
    val label: String,
    val unit: String,
    /** The time the numbers cover. */
    val window: String,
    val source: String,
) {
    YOUTUBE_AGE_GENDER(
        YOUTUBE,
        "viewer_age_gender",
        "Viewers by age and gender",
        "percent of views",
        "the 28 days ending on the last day YouTube Analytics has data for",
        "viewerPercentage by ageGroup and gender (YouTube Analytics, channel report). Viewers YouTube can't " +
            "place in an age and gender aren't included.",
    ),
    YOUTUBE_TRAFFIC(
        YOUTUBE,
        "traffic_source",
        "Where views came from",
        "views",
        "the 28 days ending on the last day YouTube Analytics has data for",
        "views by insightTrafficSourceType (YouTube Analytics, channel report)",
    ),
    YOUTUBE_COUNTRY(
        YOUTUBE,
        "viewer_country",
        "Views by country (top 25)",
        "views",
        "the 28 days ending on the last day YouTube Analytics has data for",
        "views by country, sorted by views (YouTube Analytics, channel report); ZZ is an unknown country",
    ),
    FACEBOOK_FOLLOWER_COUNTRY(
        FACEBOOK,
        "follower_country",
        "Page followers by country",
        "followers",
        "the latest day Meta reports",
        "page_follows_country (Page Insights, period day)",
    ),
    FACEBOOK_FOLLOWER_CITY(
        FACEBOOK,
        "follower_city",
        "Page followers by city",
        "followers",
        "the latest day Meta reports",
        "page_follows_city (Page Insights, period day)",
    ),
    INSTAGRAM_FOLLOWER_AGE(
        INSTAGRAM,
        "follower_age",
        "Followers by age",
        "followers",
        "current followers",
        "follower_demographics, breakdown age (IG User insights; needs 100+ followers; top 45 groups)",
    ),
    INSTAGRAM_FOLLOWER_GENDER(
        INSTAGRAM,
        "follower_gender",
        "Followers by gender",
        "followers",
        "current followers",
        "follower_demographics, breakdown gender (IG User insights; F, M and U for unknown)",
    ),
    INSTAGRAM_FOLLOWER_COUNTRY(
        INSTAGRAM,
        "follower_country",
        "Followers by country",
        "followers",
        "current followers",
        "follower_demographics, breakdown country (IG User insights; top 45 groups)",
    ),
    ;

    companion object {
        fun of(platform: SocialPlatform): List<AudienceBreakdown> = entries.filter { it.platform == platform }

        fun byId(
            platform: SocialPlatform,
            id: String,
        ): AudienceBreakdown? = entries.firstOrNull { it.platform == platform && it.id == id }

        /** What each platform doesn't provide, and why (shown beside what it does). */
        fun gaps(platform: SocialPlatform): List<String> =
            when (platform) {
                TIKTOK -> {
                    listOf("TikTok's Display API has no audience data for Personal accounts.")
                }

                YOUTUBE -> {
                    listOf("YouTube reports who watched (viewers), not who subscribed.")
                }

                FACEBOOK -> {
                    listOf(
                        "Follower age and gender: Meta retired Page age and gender metrics (page_fans_gender_age, " +
                            "March 14, 2024).",
                        "Per-Reel audience: post_video_view_time_by_age_bucket_and_gender and " +
                            "post_video_view_time_by_country_id return an error for Reels (checked November 22, 2026).",
                        "Traffic sources aren't in the Page Insights API.",
                    )
                }

                INSTAGRAM -> {
                    listOf("Traffic sources aren't in the Instagram API; it reports followers, not viewers, by group.")
                }
            }
    }
}
