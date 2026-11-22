package io.github.rmac0001.foursight.core.query

import io.github.rmac0001.foursight.core.SocialPlatform

/**
 * The API limits that apply to each platform (spec: Accounts, "the API limits that apply"). Taken
 * from each platform's reference (see STATUS.md, "API details checked"); none of this is measured.
 */
object PlatformLimits {
    fun of(platform: SocialPlatform): List<String> =
        when (platform) {
            SocialPlatform.TIKTOK -> {
                listOf(
                    "Display API: 600 requests per minute per endpoint.",
                    "Lifetime totals only: no history, watch time or traffic sources for Personal accounts.",
                    "Sign-in lasts as long as the refresh token TikTok returns; each collection renews it.",
                )
            }

            SocialPlatform.YOUTUBE -> {
                listOf(
                    "Data API: a default quota of 10,000 units a day for the Google project (a run uses a few).",
                    "Analytics numbers run about three days behind; the newest videos have none yet.",
                    "Subscriber counts are rounded by YouTube.",
                )
            }

            SocialPlatform.FACEBOOK -> {
                listOf(
                    "Graph API rate limits per app and Page (Meta's Business Use Case limits).",
                    "Reach isn't available for Reels (Meta rejects post_impressions_unique).",
                )
            }

            SocialPlatform.INSTAGRAM -> {
                listOf(
                    "Graph API rate limits per app and account (Meta's Business Use Case limits).",
                    "Media insights can lag up to 48 hours; account-level metrics are kept only 90 days.",
                    "Missing metrics come back empty, not 0.",
                )
            }
        }
}
