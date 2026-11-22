package io.github.rmac0001.foursight.core.platform.youtube

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.collect.CollectionException
import io.github.rmac0001.foursight.core.collect.FailureKind
import io.github.rmac0001.foursight.core.platform.google.GoogleCalls
import io.github.rmac0001.foursight.core.platform.google.obj
import io.github.rmac0001.foursight.core.platform.google.str
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** One Analytics report: column names and rows (dimensions first, then metrics). */
data class AnalyticsTable(
    val columns: List<String>,
    val rows: List<List<JsonPrimitive>>,
) {
    fun rowMaps(): List<Map<String, JsonPrimitive>> = rows.map { columns.zip(it).toMap() }
}

/**
 * YouTube Data API v3 and YouTube Analytics API v2, checked against the live reference on
 * November 22, 2026:
 * - `channels.list` (`mine=true`, parts `id,statistics,contentDetails`): `statistics.subscriberCount`
 *   (rounded down to three significant figures), `statistics.hiddenSubscriberCount`,
 *   `contentDetails.relatedPlaylists.uploads`. 1 quota unit.
 * - `playlistItems.list` (`playlistId`, `maxResults` up to 50, `pageToken`): `contentDetails.videoId`.
 *   1 unit.
 * - `videos.list` (`id` comma list; parts `snippet,contentDetails,statistics,status`): statistics come
 *   as strings. 1 unit. Default quota 10,000 units a day.
 * - Analytics `GET https://youtubeanalytics.googleapis.com/v2/reports` (`ids=channel==MINE`,
 *   `startDate`, `endDate`, `metrics`, `dimensions`, `filters` with up to 500 video ids, `sort`,
 *   `maxResults`). Days are Pacific time. Needs `yt-analytics.readonly` and `youtube.readonly`.
 */
class YouTubeApi(
    private val http: HttpClient,
) {
    private val platform = SocialPlatform.YOUTUBE

    suspend fun myChannel(token: String): JsonObject {
        val body =
            GoogleCalls.api(platform, "channel") {
                http.get("$DATA/channels") {
                    auth(token)
                    parameter("part", "id,statistics,contentDetails")
                    parameter("mine", true)
                }
            }
        return (body["items"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw CollectionException(
                platform,
                FailureKind.BAD_RESPONSE,
                "this Google account has no YouTube channel",
            )
    }

    /** Every video id in the uploads playlist, newest first. */
    suspend fun uploadIds(
        token: String,
        uploadsPlaylist: String,
    ): List<String> {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        repeat(MAX_PAGES) {
            val body =
                GoogleCalls.api(platform, "uploads list") {
                    http.get("$DATA/playlistItems") {
                        auth(token)
                        parameter("part", "contentDetails")
                        parameter("playlistId", uploadsPlaylist)
                        parameter("maxResults", PAGE_SIZE)
                        pageToken?.let { parameter("pageToken", it) }
                    }
                }
            (body["items"] as? JsonArray).orEmpty().forEach { item ->
                (item as? JsonObject)?.obj("contentDetails")?.str("videoId")?.let(ids::add)
            }
            pageToken = body.str("nextPageToken") ?: return ids.distinct()
        }
        throw CollectionException(platform, FailureKind.BAD_RESPONSE, "uploads list didn't end after $MAX_PAGES pages")
    }

    /** Video resources for [ids], fetched [PAGE_SIZE] at a time. */
    suspend fun videos(
        token: String,
        ids: List<String>,
    ): List<JsonObject> =
        ids.chunked(PAGE_SIZE).flatMap { chunk ->
            val body =
                GoogleCalls.api(platform, "video details") {
                    http.get("$DATA/videos") {
                        auth(token)
                        parameter("part", "snippet,contentDetails,statistics,status")
                        parameter("id", chunk.joinToString(","))
                    }
                }
            (body["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        }

    suspend fun report(
        token: String,
        startDate: LocalDate,
        endDate: LocalDate,
        metrics: List<String>,
        dimensions: List<String> = emptyList(),
        filters: String? = null,
        sort: String? = null,
        maxResults: Int? = null,
    ): AnalyticsTable {
        val body =
            GoogleCalls.api(platform, "analytics report") {
                http.get(ANALYTICS) {
                    auth(token)
                    parameter("ids", "channel==MINE")
                    parameter("startDate", startDate.toString())
                    parameter("endDate", endDate.toString())
                    parameter("metrics", metrics.joinToString(","))
                    if (dimensions.isNotEmpty()) parameter("dimensions", dimensions.joinToString(","))
                    filters?.let { parameter("filters", it) }
                    sort?.let { parameter("sort", it) }
                    maxResults?.let { parameter("maxResults", it) }
                }
            }
        val columns =
            (body["columnHeaders"] as? JsonArray).orEmpty().map {
                (it as? JsonObject)?.str("name")
                    ?: throw CollectionException(platform, FailureKind.BAD_RESPONSE, "analytics column without a name")
            }
        // `rows` is left out when there's no data.
        val rows =
            (body["rows"] as? JsonArray).orEmpty().map { row ->
                (row as? JsonArray)?.map { it as? JsonPrimitive ?: JsonPrimitive(null as String?) }
                    ?: throw CollectionException(platform, FailureKind.BAD_RESPONSE, "analytics row isn't a list")
            }
        return AnalyticsTable(columns, rows)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(token: String) =
        header(HttpHeaders.Authorization, "Bearer $token")

    companion object {
        const val DATA = "https://www.googleapis.com/youtube/v3"
        const val ANALYTICS = "https://youtubeanalytics.googleapis.com/v2/reports"
        const val PAGE_SIZE = 50
        private const val MAX_PAGES = 200
    }
}

internal fun JsonPrimitive.asDouble(): Double? = if (isString) contentOrNull?.toDoubleOrNull() else doubleOrNull
