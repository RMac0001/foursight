package io.github.rmac0001.foursight.core.platform.meta

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.collect.Collection
import io.github.rmac0001.foursight.core.collect.CollectionException
import io.github.rmac0001.foursight.core.collect.FailureKind
import io.github.rmac0001.foursight.core.collect.PlatformCollector
import io.github.rmac0001.foursight.core.metrics.AudienceBreakdown
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.ContentType
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.model.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToLong
import kotlin.time.Instant

/** Meta writes times as `2026-11-20T15:00:00+0000`; ISO 8601 parsers want `+00:00`. */
internal fun parseMetaTime(text: String): Instant =
    Instant.parse(text.replace(Regex("""([+-]\d{2})(\d{2})$"""), "$1:$2"))

/** Follows `paging.next` until it runs out. */
internal suspend fun MetaGraph.all(
    platform: SocialPlatform,
    what: String,
    path: String,
    token: String,
    params: Map<String, String>,
): List<JsonObject> {
    val items = mutableListOf<JsonObject>()
    var page = get(platform, what, path, token, params)
    repeat(MAX_PAGES) {
        items += page.list("data")
        val next = page.obj("paging")?.str("next") ?: return items
        page = getUrl(platform, what, next, token)
    }
    throw CollectionException(platform, FailureKind.BAD_RESPONSE, "$what didn't end after $MAX_PAGES pages")
}

private const val MAX_PAGES = 200

/** Sums an object of counts (reaction or action types); Meta leaves out types with no count. */
internal fun sumCounts(
    value: JsonElement?,
    keyFilter: (String) -> Boolean = { true },
): Double? {
    val obj = value as? JsonObject ?: return value?.number()
    return obj.filterKeys { keyFilter(it.lowercase()) }.values.sumOf { it.number() ?: 0.0 }
}

/**
 * Facebook Page Reels: `GET /{page-id}/video_reels` and each Reel's lifetime `video_insights`
 * (metric names checked against the Video Insights reference on November 22, 2026; the reference
 * still lists `post_impressions_unique`, but the API rejects it as "not a valid insights metric",
 * found live the same day, so Facebook reach is unavailable). Times arrive in milliseconds and are
 * stored in seconds. Meta leaves out reaction and action types with no count, so a missing type in a
 * returned object counts as zero. A Reel whose insights fail keeps its post entry and a
 * snapshot without values, with a warning.
 */
class FacebookCollector(
    private val graph: MetaGraph,
    private val auth: MetaAuth,
) : PlatformCollector {
    override val platform = SocialPlatform.FACEBOOK

    override suspend fun collect(
        now: Instant,
        deviceId: String,
        captureDay: LocalDate,
    ): Collection {
        val token = auth.pageToken(platform)
        val page = graph.get(platform, "Page", "me", token, mapOf("fields" to "id,name,followers_count"))
        val pageId = page.str("id") ?: throw CollectionException(platform, FailureKind.BAD_RESPONSE, "Page has no id")
        val reels =
            graph.all(
                platform,
                "Reels list",
                "$pageId/video_reels",
                token,
                mapOf("fields" to REEL_FIELDS, "limit" to "50"),
            )

        fun snapshot(
            kind: ItemKind,
            id: String,
            raw: JsonObject,
            values: Map<String, Double?>,
        ) = Snapshot(deviceId, platform, kind, id, now, captureDay, Metrics.DEFINITIONS_VERSION, raw, values)

        val warnings = mutableListOf<String>()
        val snapshots = mutableListOf<Snapshot>()
        snapshots +=
            snapshot(
                ItemKind.ACCOUNT,
                pageId,
                page,
                buildMap { page["followers_count"]?.number()?.let { put(Metrics.FOLLOWERS.id, it) } },
            )
        val posts = mutableListOf<PlatformPost>()
        for (reel in reels) {
            val id = reel.str("id") ?: continue
            val created = (reel.str("created_time") ?: reel.str("updated_time"))?.let(::parseMetaTime) ?: continue
            posts +=
                PlatformPost(
                    platform = platform,
                    postId = id,
                    title = reel.str("description") ?: reel.str("title"),
                    publishedAt = created,
                    url =
                        reel
                            .str(
                                "permalink_url",
                            )?.let { if (it.startsWith("/")) "https://www.facebook.com$it" else it },
                    durationSeconds = reel["length"]?.number()?.roundToLong(),
                    contentType = ContentType.SHORT_FORM,
                    thumbnailUrl = reel.str("picture"),
                )
            val insights =
                try {
                    insightValues(
                        graph.get(
                            platform,
                            "Reel insights",
                            "$id/video_insights",
                            token,
                            mapOf("metric" to REEL_METRICS),
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CollectionException) {
                    if (e.kind == FailureKind.SIGN_IN_EXPIRED || e.kind == FailureKind.RATE_LIMITED) throw e
                    warnings += "Reel $id insights are missing this run: ${e.message}"
                    null
                }
            val values =
                buildMap<String, Double?> {
                    if (insights == null) return@buildMap
                    insights["fb_reels_total_plays"]?.number()?.let { put(Metrics.VIEWS.id, it) }
                    sumCounts(insights["post_video_likes_by_reaction_type"])?.let { put(Metrics.LIKES.id, it) }
                    insights["post_video_social_actions"]?.let { actions ->
                        sumCounts(actions) { "comment" in it }?.let { put(Metrics.COMMENTS.id, it) }
                        sumCounts(actions) { "share" in it }?.let { put(Metrics.SHARES.id, it) }
                    }
                    insights["post_video_followers"]?.number()?.let { put(Metrics.FOLLOWERS_GAINED.id, it) }
                    insights["post_video_view_time"]?.number()?.let { put(Metrics.WATCH_TIME.id, it / 1000) }
                    insights["post_video_avg_time_watched"]?.number()?.let {
                        put(
                            Metrics.AVERAGE_VIEW_DURATION.id,
                            it / 1000,
                        )
                    }
                }
            val raw =
                buildJsonObject {
                    put("reel", reel)
                    if (insights != null) put("insights", JsonObject(insights))
                }
            snapshots += snapshot(ItemKind.POST, id, raw, values)
        }
        AudienceBreakdown.of(platform).forEach { b ->
            try {
                val body =
                    graph.get(
                        platform,
                        b.label,
                        "$pageId/insights",
                        token,
                        mapOf(
                            "metric" to b.metricName(),
                            "period" to "day",
                        ),
                    )
                snapshots += snapshot(ItemKind.AUDIENCE, b.id, body, pageBreakdown(body))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CollectionException) {
                if (e.kind == FailureKind.SIGN_IN_EXPIRED || e.kind == FailureKind.RATE_LIMITED) throw e
                warnings += "Facebook ${b.label.lowercase()} is missing this run: ${e.message}"
            }
        }
        return Collection(posts, snapshots, warnings = warnings)
    }

    private fun AudienceBreakdown.metricName(): String =
        when (this) {
            AudienceBreakdown.FACEBOOK_FOLLOWER_COUNTRY -> "page_follows_country"
            AudienceBreakdown.FACEBOOK_FOLLOWER_CITY -> "page_follows_city"
            else -> error("not a Facebook breakdown: $this")
        }

    companion object {
        /** The latest day's value object of a Page Insights breakdown metric: group to count. */
        internal fun pageBreakdown(body: JsonObject): Map<String, Double?> {
            val latest =
                body
                    .list("data")
                    .firstOrNull()
                    ?.list("values")
                    ?.lastOrNull()
                    ?.obj("value") ?: return emptyMap()
            return latest.mapValues { (_, v) -> v.number() }
        }

        const val REEL_FIELDS = "id,description,title,created_time,updated_time,length,permalink_url,picture"
        val REEL_METRICS =
            listOf(
                "blue_reels_play_count",
                "fb_reels_replay_count",
                "fb_reels_total_plays",
                "post_video_avg_time_watched",
                "post_video_followers",
                "post_video_likes_by_reaction_type",
                "post_video_social_actions",
                "post_video_view_time",
            ).joinToString(",")
    }
}

/**
 * Instagram Reels through the Page's linked professional account (API with Facebook Login):
 * `GET /{ig-user-id}/media` filtered to `media_product_type` REELS, and each Reel's
 * `/{ig-media-id}/insights` (lifetime). Missing metrics come back as an empty set and stay missing.
 * Media metrics can lag up to 48 hours. Watch times are stored in seconds from Meta's
 * milliseconds (unit confirmed on the first live run, see STATUS.md).
 */
class InstagramCollector(
    private val graph: MetaGraph,
    private val auth: MetaAuth,
) : PlatformCollector {
    override val platform = SocialPlatform.INSTAGRAM

    override suspend fun collect(
        now: Instant,
        deviceId: String,
        captureDay: LocalDate,
    ): Collection {
        val token = auth.pageToken(platform)
        val page =
            graph.get(
                platform,
                "linked Instagram account",
                "me",
                token,
                mapOf("fields" to "instagram_business_account{id,followers_count,media_count}"),
            )
        val account =
            page.obj("instagram_business_account")
                ?: throw CollectionException(
                    platform,
                    FailureKind.NOT_CONNECTED,
                    "the Facebook Page has no linked Instagram account",
                )
        val igId =
            account.str("id")
                ?: throw CollectionException(platform, FailureKind.BAD_RESPONSE, "Instagram account has no id")
        val reels =
            graph
                .all(platform, "media list", "$igId/media", token, mapOf("fields" to MEDIA_FIELDS, "limit" to "50"))
                .filter { it.str("media_product_type") == "REELS" }

        fun snapshot(
            kind: ItemKind,
            id: String,
            raw: JsonObject,
            values: Map<String, Double?>,
        ) = Snapshot(deviceId, platform, kind, id, now, captureDay, Metrics.DEFINITIONS_VERSION, raw, values)

        val warnings = mutableListOf<String>()
        val snapshots = mutableListOf<Snapshot>()
        snapshots +=
            snapshot(
                ItemKind.ACCOUNT,
                igId,
                account,
                buildMap { account["followers_count"]?.number()?.let { put(Metrics.FOLLOWERS.id, it) } },
            )
        val posts = mutableListOf<PlatformPost>()
        for (media in reels) {
            val id = media.str("id") ?: continue
            val published = media.str("timestamp")?.let(::parseMetaTime) ?: continue
            posts +=
                PlatformPost(
                    platform = platform,
                    postId = id,
                    title = media.str("caption"),
                    publishedAt = published,
                    url = media.str("permalink"),
                    durationSeconds = null,
                    contentType = ContentType.SHORT_FORM,
                    thumbnailUrl = media.str("thumbnail_url"),
                )
            val insights =
                try {
                    insightValues(
                        graph.get(
                            platform,
                            "Reel insights",
                            "$id/insights",
                            token,
                            mapOf("metric" to REEL_METRICS),
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CollectionException) {
                    if (e.kind == FailureKind.SIGN_IN_EXPIRED || e.kind == FailureKind.RATE_LIMITED) throw e
                    warnings += "Instagram Reel $id insights are missing this run: ${e.message}"
                    null
                }
            val values =
                buildMap<String, Double?> {
                    if (insights == null) return@buildMap
                    insights["views"]?.number()?.let { put(Metrics.VIEWS.id, it) }
                    insights["likes"]?.number()?.let { put(Metrics.LIKES.id, it) }
                    insights["comments"]?.number()?.let { put(Metrics.COMMENTS.id, it) }
                    insights["shares"]?.number()?.let { put(Metrics.SHARES.id, it) }
                    insights["saved"]?.number()?.let { put(Metrics.SAVES.id, it) }
                    insights["reach"]?.number()?.let { put(Metrics.REACH.id, it) }
                    insights["ig_reels_video_view_total_time"]?.number()?.let { put(Metrics.WATCH_TIME.id, it / 1000) }
                    insights["ig_reels_avg_watch_time"]?.number()?.let {
                        put(
                            Metrics.AVERAGE_VIEW_DURATION.id,
                            it / 1000,
                        )
                    }
                    insights["reels_skip_rate"]?.number()?.let { put(Metrics.SKIP_RATE.id, it) }
                }
            val raw =
                buildJsonObject {
                    put("media", media)
                    if (insights != null) put("insights", JsonObject(insights))
                }
            snapshots += snapshot(ItemKind.POST, id, raw, values)
        }
        AudienceBreakdown.of(platform).forEach { b ->
            try {
                val body =
                    graph.get(
                        platform,
                        b.label,
                        "$igId/insights",
                        token,
                        mapOf(
                            "metric" to "follower_demographics",
                            "period" to "lifetime",
                            "timeframe" to "this_month",
                            "breakdown" to b.breakdownName(),
                            "metric_type" to "total_value",
                        ),
                    )
                snapshots += snapshot(ItemKind.AUDIENCE, b.id, body, demographics(body))
            } catch (e: CancellationException) {
                throw e
            } catch (e: CollectionException) {
                if (e.kind == FailureKind.SIGN_IN_EXPIRED || e.kind == FailureKind.RATE_LIMITED) throw e
                warnings += "Instagram ${b.label.lowercase()} is missing this run: ${e.message}"
            }
        }
        return Collection(posts, snapshots, warnings = warnings)
    }

    private fun AudienceBreakdown.breakdownName(): String =
        when (this) {
            AudienceBreakdown.INSTAGRAM_FOLLOWER_AGE -> "age"
            AudienceBreakdown.INSTAGRAM_FOLLOWER_GENDER -> "gender"
            AudienceBreakdown.INSTAGRAM_FOLLOWER_COUNTRY -> "country"
            else -> error("not an Instagram breakdown: $this")
        }

    companion object {
        const val MEDIA_FIELDS = "id,caption,media_type,media_product_type,timestamp,permalink,thumbnail_url"
        val REEL_METRICS =
            listOf(
                "views",
                "reach",
                "likes",
                "comments",
                "shares",
                "saved",
                "total_interactions",
                "ig_reels_avg_watch_time",
                "ig_reels_video_view_total_time",
                "reels_skip_rate",
            ).joinToString(",")
    }
}

/**
 * An IG User demographics response (`total_value.breakdowns[0].results`): group to count. Meta
 * returns an empty set rather than zeros when there isn't enough data, so that stays empty.
 */
internal fun demographics(body: JsonObject): Map<String, Double?> {
    val results =
        body
            .list("data")
            .firstOrNull()
            ?.obj("total_value")
            ?.list("breakdowns")
            ?.firstOrNull()
            ?.list("results")
            .orEmpty()
    return results
        .mapNotNull { r ->
            val key =
                (r["dimension_values"] as? JsonArray)
                    ?.joinToString(" ") { (it as? JsonPrimitive)?.content.orEmpty() }
                    ?.takeIf { it.isNotBlank() }
            key?.let { it to r["value"]?.number() }
        }.toMap()
}
