package io.github.rmac0001.foursight.core.metrics

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.SocialPlatform.FACEBOOK
import io.github.rmac0001.foursight.core.SocialPlatform.INSTAGRAM
import io.github.rmac0001.foursight.core.SocialPlatform.TIKTOK
import io.github.rmac0001.foursight.core.SocialPlatform.YOUTUBE
import io.github.rmac0001.foursight.core.model.ItemKind

/**
 * How a metric may be combined across platforms (spec: Overview, "Combining rules"). Counts can be
 * summed; rates and durations are only ever shown per platform.
 */
enum class MetricKind(
    val summable: Boolean,
) {
    COUNT(summable = true),
    RATE(summable = false),
    DURATION(summable = false),
}

/** Where a platform gets a metric from, or why it can't. */
sealed interface Availability {
    /** [source] names the exact API field or metric, checked against the reference on the given date. */
    data class Available(
        val source: String,
    ) : Availability

    data class Unavailable(
        val reason: String,
    ) : Availability
}

/**
 * A normalized metric. Collectors map each platform's raw API values onto these ids.
 * Bump [DEFINITIONS_VERSION] whenever a definition or source changes.
 */
data class MetricDefinition(
    val id: String,
    val label: String,
    val kind: MetricKind,
    val unit: String,
    val itemKind: ItemKind,
    val definition: String,
    val sources: Map<SocialPlatform, Availability>,
) {
    fun availability(platform: SocialPlatform): Availability =
        sources[platform] ?: Availability.Unavailable("Not provided by the ${platform.displayName} API.")
}

/**
 * The normalized metrics FourSight stores. Source names were checked against each platform's live
 * API reference on November 21, 2026 (see STATUS.md, "API details checked").
 */
object Metrics {
    const val DEFINITIONS_VERSION = 1

    private fun available(source: String) = Availability.Available(source)

    private fun unavailable(reason: String) = Availability.Unavailable(reason)

    val VIEWS =
        MetricDefinition(
            id = "views",
            label = "Views",
            kind = MetricKind.COUNT,
            unit = "views",
            itemKind = ItemKind.POST,
            definition =
                "Lifetime views of the post as each platform counts them. Platforms count views " +
                    "differently, so the sum across platforms is a total of four different counts.",
            sources =
                mapOf(
                    TIKTOK to available("video.view_count (Display API /v2/video/query/)"),
                    YOUTUBE to available("views (YouTube Analytics API)"),
                    FACEBOOK to available("fb_reels_total_plays (video_insights, lifetime, includes replays)"),
                    INSTAGRAM to available("views (IG Media insights)"),
                ),
        )

    val LIKES =
        MetricDefinition(
            id = "likes",
            label = "Likes",
            kind = MetricKind.COUNT,
            unit = "likes",
            itemKind = ItemKind.POST,
            definition = "Lifetime likes (Facebook: reactions) on the post.",
            sources =
                mapOf(
                    TIKTOK to available("video.like_count"),
                    YOUTUBE to available("likes (YouTube Analytics API)"),
                    FACEBOOK to available("post_video_likes_by_reaction_type (summed over reaction types)"),
                    INSTAGRAM to available("likes (IG Media insights)"),
                ),
        )

    val COMMENTS =
        MetricDefinition(
            id = "comments",
            label = "Comments",
            kind = MetricKind.COUNT,
            unit = "comments",
            itemKind = ItemKind.POST,
            definition = "Lifetime comments on the post.",
            sources =
                mapOf(
                    TIKTOK to available("video.comment_count"),
                    YOUTUBE to available("comments (YouTube Analytics API)"),
                    FACEBOOK to available("post_video_social_actions (comments part)"),
                    INSTAGRAM to available("comments (IG Media insights)"),
                ),
        )

    val SHARES =
        MetricDefinition(
            id = "shares",
            label = "Shares",
            kind = MetricKind.COUNT,
            unit = "shares",
            itemKind = ItemKind.POST,
            definition = "Lifetime shares of the post.",
            sources =
                mapOf(
                    TIKTOK to available("video.share_count"),
                    YOUTUBE to available("shares (YouTube Analytics API)"),
                    FACEBOOK to available("post_video_social_actions (shares part)"),
                    INSTAGRAM to available("shares (IG Media insights)"),
                ),
        )

    val SAVES =
        MetricDefinition(
            id = "saves",
            label = "Saves",
            kind = MetricKind.COUNT,
            unit = "saves",
            itemKind = ItemKind.POST,
            definition = "Lifetime saves of the post.",
            sources =
                mapOf(
                    TIKTOK to unavailable("The Display API's video object has no saves field."),
                    YOUTUBE to unavailable("The YouTube Analytics API has no saves metric."),
                    FACEBOOK to unavailable("Reels video_insights has no saves metric."),
                    INSTAGRAM to available("saved (IG Media insights)"),
                ),
        )

    val FOLLOWERS_GAINED =
        MetricDefinition(
            id = "followers_gained",
            label = "Followers gained from the post",
            kind = MetricKind.COUNT,
            unit = "followers",
            itemKind = ItemKind.POST,
            definition =
                "Follows the platform attributes to this post. Only platforms that report it per post " +
                    "have a value; account-level follower change is a separate, labeled number.",
            sources =
                mapOf(
                    TIKTOK to unavailable("TikTok's Display API reports only the account's total follower count."),
                    YOUTUBE to available("subscribersGained (YouTube Analytics API, filtered to the video)"),
                    FACEBOOK to available("post_video_followers (video_insights, lifetime)"),
                    INSTAGRAM to
                        unavailable("Instagram's per-media follows metric covers feed posts and stories, not Reels."),
                ),
        )

    val REACH =
        MetricDefinition(
            id = "reach",
            label = "Reach",
            kind = MetricKind.COUNT,
            unit = "accounts",
            itemKind = ItemKind.POST,
            definition = "Unique people or accounts that saw the post at least once. Estimated by the platform.",
            sources =
                mapOf(
                    TIKTOK to unavailable("Not in the Display API."),
                    YOUTUBE to unavailable("Not in the YouTube Analytics API."),
                    FACEBOOK to available("post_impressions_unique (video_insights, lifetime)"),
                    INSTAGRAM to available("reach (IG Media insights)"),
                ),
        )

    val WATCH_TIME =
        MetricDefinition(
            id = "watch_time_seconds",
            label = "Total watch time",
            kind = MetricKind.DURATION,
            unit = "seconds",
            itemKind = ItemKind.POST,
            definition = "Total time the post was played, including replays where the platform includes them.",
            sources =
                mapOf(
                    TIKTOK to unavailable("Not available for TikTok Personal accounts through the Display API."),
                    YOUTUBE to available("estimatedMinutesWatched (converted to seconds)"),
                    FACEBOOK to available("post_video_view_time (milliseconds, includes replays)"),
                    INSTAGRAM to available("ig_reels_video_view_total_time (includes replays; metric in development)"),
                ),
        )

    val AVERAGE_VIEW_DURATION =
        MetricDefinition(
            id = "average_view_duration_seconds",
            label = "Average view duration",
            kind = MetricKind.DURATION,
            unit = "seconds",
            itemKind = ItemKind.POST,
            definition =
                "Average time per play. Facebook's figure includes replays, so it can exceed the video length.",
            sources =
                mapOf(
                    TIKTOK to unavailable("Not available for TikTok Personal accounts through the Display API."),
                    YOUTUBE to available("averageViewDuration"),
                    FACEBOOK to available("post_video_avg_time_watched (milliseconds, includes replays)"),
                    INSTAGRAM to available("ig_reels_avg_watch_time"),
                ),
        )

    val AVERAGE_VIEW_PERCENTAGE =
        MetricDefinition(
            id = "average_view_percentage",
            label = "Average percentage viewed",
            kind = MetricKind.RATE,
            unit = "percent",
            itemKind = ItemKind.POST,
            definition = "Average share of the video watched per view.",
            sources =
                mapOf(
                    TIKTOK to unavailable("Not available for TikTok Personal accounts through the Display API."),
                    YOUTUBE to available("averageViewPercentage"),
                    FACEBOOK to unavailable("Facebook reports a retention graph instead (post_video_retention_graph)."),
                    INSTAGRAM to unavailable("Not in IG Media insights."),
                ),
        )

    val SKIP_RATE =
        MetricDefinition(
            id = "skip_rate",
            label = "Skip rate",
            kind = MetricKind.RATE,
            unit = "percent",
            itemKind = ItemKind.POST,
            definition = "Share of views that skipped during the first 3 seconds.",
            sources =
                mapOf(
                    INSTAGRAM to available("reels_skip_rate (IG Media insights)"),
                ),
        )

    val FOLLOWERS =
        MetricDefinition(
            id = "followers",
            label = "Followers",
            kind = MetricKind.COUNT,
            unit = "followers",
            itemKind = ItemKind.ACCOUNT,
            definition = "The account's total followers (YouTube: subscribers) at capture time.",
            sources =
                mapOf(
                    TIKTOK to available("user.follower_count (user.info.stats scope)"),
                    YOUTUBE to available("channel statistics.subscriberCount (rounded by YouTube)"),
                    FACEBOOK to available("Page followers_count field"),
                    INSTAGRAM to available("IG User followers_count field"),
                ),
        )

    val all: List<MetricDefinition> =
        listOf(
            VIEWS,
            LIKES,
            COMMENTS,
            SHARES,
            SAVES,
            FOLLOWERS_GAINED,
            REACH,
            WATCH_TIME,
            AVERAGE_VIEW_DURATION,
            AVERAGE_VIEW_PERCENTAGE,
            SKIP_RATE,
            FOLLOWERS,
        )

    private val byId = all.associateBy { it.id }

    fun get(id: String): MetricDefinition = byId[id] ?: error("Unknown metric id: $id")
}
