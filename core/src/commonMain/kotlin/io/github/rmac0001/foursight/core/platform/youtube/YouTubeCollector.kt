package io.github.rmac0001.foursight.core.platform.youtube

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.collect.Collection
import io.github.rmac0001.foursight.core.collect.CollectionException
import io.github.rmac0001.foursight.core.collect.FailureKind
import io.github.rmac0001.foursight.core.collect.PlatformCollector
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.ContentType
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.model.Snapshot
import io.github.rmac0001.foursight.core.platform.google.GoogleAuth
import io.github.rmac0001.foursight.core.platform.google.num
import io.github.rmac0001.foursight.core.platform.google.obj
import io.github.rmac0001.foursight.core.platform.google.str
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * Collects YouTube. By Roger's decision (November 22, 2026), views, likes and comments come from the
 * Data API's current counters, so a day-7 number is really day 7. Shares, subscribers gained, watch
 * time, average view duration and average percentage viewed exist only in YouTube Analytics, which
 * runs behind; each snapshot keeps the last Analytics day included (`analytics_through`), because
 * the API doesn't publish how far behind it is. If Analytics fails, the Data API numbers are still
 * stored and the Analytics ones stay missing, with a warning.
 */
class YouTubeCollector(
    private val api: YouTubeApi,
    private val auth: GoogleAuth,
) : PlatformCollector {
    override val platform = SocialPlatform.YOUTUBE

    override suspend fun collect(
        now: Instant,
        deviceId: String,
        captureDay: LocalDate,
    ): Collection {
        val token = auth.accessToken(now)
        val warnings = mutableListOf<String>()

        val channel = api.myChannel(token)
        val channelId = channel.str("id") ?: throw bad("channel has no id")
        val uploads =
            channel.obj("contentDetails")?.obj("relatedPlaylists")?.str("uploads")
                ?: throw bad("channel has no uploads playlist")
        val videos = api.videos(token, api.uploadIds(token, uploads)).filter(::isPublic)

        val today = now.toLocalDateTime(PACIFIC).date
        val analytics =
            try {
                analytics(token, videos, today)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CollectionException) {
                if (e.kind == FailureKind.SIGN_IN_EXPIRED) throw e
                warnings += "YouTube Analytics numbers are missing this run: ${e.message}"
                null
            }

        fun snapshot(
            kind: ItemKind,
            id: String,
            raw: JsonObject,
            values: Map<String, Double?>,
        ) = Snapshot(deviceId, platform, kind, id, now, captureDay, Metrics.DEFINITIONS_VERSION, raw, values)

        val stats = channel.obj("statistics")
        val hidden = stats?.get("hiddenSubscriberCount")?.let { (it as? JsonPrimitive)?.content == "true" } ?: false
        if (hidden) warnings += "The channel hides its subscriber count, so followers are missing."
        val snapshots = mutableListOf<Snapshot>()
        snapshots +=
            snapshot(
                ItemKind.ACCOUNT,
                channelId,
                channel,
                buildMap {
                    if (!hidden) stats?.num("subscriberCount")?.let { put(Metrics.FOLLOWERS.id, it.toDouble()) }
                },
            )

        val posts = mutableListOf<PlatformPost>()
        for (video in videos) {
            val id = video.str("id") ?: throw bad("a video has no id")
            val snippet = video.obj("snippet")
            val published =
                snippet?.str("publishedAt")?.let(Instant::parse) ?: throw bad("video $id has no publishedAt")
            val duration = video.obj("contentDetails")?.str("duration")?.let(::isoDurationSeconds)
            posts +=
                PlatformPost(
                    platform = platform,
                    postId = id,
                    title = snippet.str("title"),
                    publishedAt = published,
                    url = "https://www.youtube.com/watch?v=$id",
                    durationSeconds = duration,
                    contentType = contentType(duration),
                    thumbnailUrl = thumbnail(snippet),
                )
            val s = video.obj("statistics")
            val row = analytics?.rows?.get(id)
            val values =
                buildMap<String, Double?> {
                    s?.num("viewCount")?.let { put(Metrics.VIEWS.id, it.toDouble()) }
                    s?.num("likeCount")?.let { put(Metrics.LIKES.id, it.toDouble()) }
                    s?.num("commentCount")?.let { put(Metrics.COMMENTS.id, it.toDouble()) }
                    if (row != null) {
                        row["shares"]?.asDouble()?.let { put(Metrics.SHARES.id, it) }
                        row["subscribersGained"]?.asDouble()?.let { put(Metrics.FOLLOWERS_GAINED.id, it) }
                        row["estimatedMinutesWatched"]?.asDouble()?.let { put(Metrics.WATCH_TIME.id, it * 60) }
                        row["averageViewDuration"]?.asDouble()?.let { put(Metrics.AVERAGE_VIEW_DURATION.id, it) }
                        row["averageViewPercentage"]?.asDouble()?.let { put(Metrics.AVERAGE_VIEW_PERCENTAGE.id, it) }
                    }
                }
            val raw =
                buildJsonObject {
                    put("video", video)
                    if (analytics != null) {
                        put("analytics_through", analytics.through?.toString())
                        put("analytics", JsonObject(row.orEmpty()))
                    }
                }
            snapshots += snapshot(ItemKind.POST, id, raw, values)
        }
        return Collection(posts, snapshots, tokenExpiresAt = null, warnings = warnings)
    }

    private class Analytics(
        val through: LocalDate?,
        val rows: Map<String, Map<String, JsonPrimitive>>,
    )

    /**
     * Lifetime Analytics per video, plus the last day Analytics has data for (found by asking the
     * channel's daily views over the last two weeks and taking the latest row).
     */
    private suspend fun analytics(
        token: String,
        videos: List<JsonObject>,
        today: LocalDate,
    ): Analytics {
        val daily =
            api.report(
                token,
                today.minus(DatePeriod(days = 14)),
                today,
                listOf("views"),
                listOf("day"),
                sort = "day",
            )
        val through =
            daily.rows
                .lastOrNull()
                ?.firstOrNull()
                ?.content
                ?.let(LocalDate::parse)
        val ids = videos.mapNotNull { it.str("id") }
        if (ids.isEmpty()) return Analytics(through, emptyMap())
        val start =
            videos
                .mapNotNull { it.obj("snippet")?.str("publishedAt")?.let(Instant::parse) }
                .min()
                .toLocalDateTime(PACIFIC)
                .date
        val rows =
            ids.chunked(TOP_VIDEOS_MAX).flatMap { chunk ->
                api
                    .report(
                        token,
                        start,
                        today,
                        VIDEO_METRICS,
                        listOf("video"),
                        filters = "video==${chunk.joinToString(",")}",
                        sort = "-views",
                        maxResults = TOP_VIDEOS_MAX,
                    ).rowMaps()
            }
        return Analytics(through, rows.mapNotNull { r -> r["video"]?.content?.let { it to r } }.toMap())
    }

    private fun isPublic(video: JsonObject): Boolean =
        video
            .obj("status")
            ?.str("privacyStatus")
            ?.lowercase()
            ?.removePrefix("privacy_") == "public"

    private fun thumbnail(snippet: JsonObject): String? {
        val thumbs = snippet.obj("thumbnails") ?: return null
        return listOf("high", "medium", "default").firstNotNullOfOrNull { thumbs.obj(it)?.str("url") }
    }

    private fun bad(detail: String) = CollectionException(platform, FailureKind.BAD_RESPONSE, detail)

    companion object {
        private val PACIFIC = TimeZone.of("America/Los_Angeles")

        /** The "Top videos" report allows at most 200 rows and needs a sort. */
        private const val TOP_VIDEOS_MAX = 200

        /** YouTube Shorts can be up to 3 minutes long. */
        const val SHORTS_MAX_SECONDS = 180L

        val VIDEO_METRICS =
            listOf(
                "views",
                "engagedViews",
                "shares",
                "subscribersGained",
                "subscribersLost",
                "estimatedMinutesWatched",
                "averageViewDuration",
                "averageViewPercentage",
            )

        /**
         * Short-form when the video is 3 minutes or less (the Shorts limit), else long-form. A rule
         * on duration, not YouTube's own label; see docs/metrics.md.
         */
        fun contentType(durationSeconds: Long?): ContentType =
            if (durationSeconds == null ||
                durationSeconds <= SHORTS_MAX_SECONDS
            ) {
                ContentType.SHORT_FORM
            } else {
                ContentType.LONG_FORM
            }

        private val DURATION = Regex("""^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")

        /** ISO 8601 duration as YouTube writes it (`PT15M33S`, `PT1H2M3S`, `P1DT2H`) in seconds. */
        fun isoDurationSeconds(iso: String): Long? {
            val m = DURATION.matchEntire(iso) ?: return null
            val (d, h, min, s) = m.destructured
            return listOf(d to 86_400L, h to 3_600L, min to 60L, s to 1L).sumOf { (v, unit) ->
                (v.toLongOrNull() ?: 0L) *
                    unit
            }
        }
    }
}
