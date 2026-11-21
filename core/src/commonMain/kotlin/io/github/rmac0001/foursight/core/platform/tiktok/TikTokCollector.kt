package io.github.rmac0001.foursight.core.platform.tiktok

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
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/**
 * Collects TikTok (Personal account, Display API): every public video's lifetime counts and the
 * account's follower count. TikTok returns today's totals only, so each run is one more point in the
 * history; nothing past is rebuilt. Fields the API leaves out are stored as missing, never as zero.
 */
class TikTokCollector(
    private val api: TikTokApi,
    private val auth: TikTokAuth,
) : PlatformCollector {
    override val platform = SocialPlatform.TIKTOK

    override suspend fun collect(
        now: Instant,
        deviceId: String,
        captureDay: LocalDate,
    ): Collection {
        val token = auth.accessToken(now)
        val warnings = mutableListOf<String>()
        val missing = TikTokApi.READ_SCOPES.toSet() - token.scopes
        if (missing.isNotEmpty()) warnings += "TikTok sign-in is missing scopes: ${missing.sorted().joinToString()}"

        val user = api.userInfo(token.value)
        val openId = user.string("open_id") ?: throw bad("user info has no open_id")
        val videos = allVideos(token.value)

        fun snapshot(
            kind: ItemKind,
            id: String,
            raw: JsonObject,
            values: Map<String, Double?>,
        ) = Snapshot(deviceId, platform, kind, id, now, captureDay, Metrics.DEFINITIONS_VERSION, raw, values)

        val posts = mutableListOf<PlatformPost>()
        val snapshots = mutableListOf<Snapshot>()
        snapshots +=
            snapshot(
                ItemKind.ACCOUNT,
                openId,
                user,
                listOfNotNull(user.long("follower_count")?.let { Metrics.FOLLOWERS.id to it.toDouble() }).toMap(),
            )
        for (video in videos) {
            val id = video.string("id") ?: throw bad("a video has no id")
            val created = video.long("create_time") ?: throw bad("video $id has no create_time")
            posts +=
                PlatformPost(
                    platform = platform,
                    postId = id,
                    // TikTok's title is often empty; the caption is in video_description.
                    title = video.string("title")?.takeIf { it.isNotBlank() } ?: video.string("video_description"),
                    publishedAt = Instant.fromEpochSeconds(created),
                    url = video.string("share_url"),
                    durationSeconds = video.long("duration"),
                    contentType = ContentType.SHORT_FORM,
                    // A CDN link with a 6-hour lifetime; refreshed on every collection.
                    thumbnailUrl = video.string("cover_image_url"),
                )
            val values =
                buildMap<String, Double?> {
                    video.long("view_count")?.let { put(Metrics.VIEWS.id, it.toDouble()) }
                    video.long("like_count")?.let { put(Metrics.LIKES.id, it.toDouble()) }
                    video.long("comment_count")?.let { put(Metrics.COMMENTS.id, it.toDouble()) }
                    video.long("share_count")?.let { put(Metrics.SHARES.id, it.toDouble()) }
                }
            snapshots += snapshot(ItemKind.POST, id, video, values)
        }
        return Collection(posts, snapshots, tokenExpiresAt = token.signInExpiresAt, warnings = warnings)
    }

    /** Pages through `/v2/video/list/` (newest first) until `has_more` is false. */
    private suspend fun allVideos(accessToken: String): List<JsonObject> {
        val all = mutableListOf<JsonObject>()
        var cursor: Long? = null
        repeat(MAX_PAGES) {
            val page = api.listVideos(accessToken, cursor)
            all += page.videos
            if (!page.hasMore) return all.distinctBy { it.string("id") }
            val next = page.cursor ?: throw bad("video list says has_more but gives no cursor")
            if (next == cursor) throw bad("video list cursor didn't advance")
            cursor = next
        }
        throw bad("video list didn't end after $MAX_PAGES pages")
    }

    private fun bad(detail: String) = CollectionException(platform, FailureKind.BAD_RESPONSE, detail)

    private companion object {
        /** 20 videos a page, so 5,000 videos; a guard against a cursor loop, not a real limit. */
        const val MAX_PAGES = 250
    }
}
