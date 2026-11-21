package io.github.rmac0001.foursight.core.sync

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.model.PostRef
import io.github.rmac0001.foursight.core.store.StoredEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * User edits that group posts into videos (spec: Content library; Sync, "Events"). Every edit is an
 * event; the current grouping is rebuilt from all events, so both devices end up identical.
 */
object MatchEvents {
    const val LINK = "link_post"
    const val UNLINK = "unlink_post"

    fun link(
        eventId: String,
        deviceId: String,
        at: Instant,
        videoId: String,
        post: PostRef,
    ) = StoredEvent(
        eventId,
        deviceId,
        at,
        LINK,
        buildJsonObject {
            put("video_id", videoId)
            put("platform", post.platform.name)
            put("post_id", post.postId)
        },
    )

    fun unlink(
        eventId: String,
        deviceId: String,
        at: Instant,
        post: PostRef,
    ) = StoredEvent(
        eventId,
        deviceId,
        at,
        UNLINK,
        buildJsonObject {
            put("platform", post.platform.name)
            put("post_id", post.postId)
        },
    )

    /**
     * Current post → video links. For each post the latest link/unlink event wins (last writer wins
     * by timestamp, ties broken by event id so every device picks the same winner). Older events
     * stay in the log; they just don't decide the result.
     */
    fun currentLinks(events: List<StoredEvent>): Map<PostRef, String> {
        val latest = mutableMapOf<PostRef, StoredEvent>()
        events
            .filter { it.type == LINK || it.type == UNLINK }
            .sortedWith(compareBy<StoredEvent>({ it.createdAt }, { it.eventId }))
            .forEach { e ->
                val ref = e.payload.postRef() ?: return@forEach
                latest[ref] = e
            }
        return latest
            .filterValues { it.type == LINK }
            .mapValues { (_, e) -> e.payload.string("video_id")!! }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.jsonPrimitive?.content

    private fun JsonObject.postRef(): PostRef? {
        val platform = string("platform")?.let { name -> SocialPlatform.entries.firstOrNull { it.name == name } }
        val postId = string("post_id")
        return if (platform != null && postId != null) PostRef(platform, postId) else null
    }
}
