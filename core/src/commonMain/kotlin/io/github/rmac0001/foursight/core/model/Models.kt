package io.github.rmac0001.foursight.core.model

import io.github.rmac0001.foursight.core.SocialPlatform
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** Short-form now; long-form is planned, so the model already carries it (spec: Content library). */
@Serializable
enum class ContentType { SHORT_FORM, LONG_FORM }

/**
 * What a snapshot describes: one post, the whole account on that platform, or one audience
 * breakdown (item id = the breakdown's id, see AudienceBreakdown).
 */
@Serializable
enum class ItemKind { POST, ACCOUNT, AUDIENCE }

/** One post on one platform. */
data class PlatformPost(
    val platform: SocialPlatform,
    val postId: String,
    val title: String?,
    val publishedAt: Instant,
    val url: String?,
    val durationSeconds: Long?,
    val contentType: ContentType,
    val thumbnailUrl: String?,
) {
    val ref: PostRef get() = PostRef(platform, postId)
}

@Serializable
data class PostRef(
    val platform: SocialPlatform,
    val postId: String,
) {
    override fun toString(): String = "${platform.name.lowercase()}:$postId"
}

/**
 * One capture of an item's numbers. [raw] is exactly what the API returned; [values] holds the
 * normalized metrics parsed from it. A metric that is absent from [values], or present with a
 * null value, is missing: it is never treated as zero.
 */
data class Snapshot(
    val deviceId: String,
    val platform: SocialPlatform,
    val itemKind: ItemKind,
    val itemId: String,
    val capturedAt: Instant,
    val captureDay: LocalDate,
    val definitionsVersion: Int,
    val raw: JsonObject,
    val values: Map<String, Double?>,
) {
    fun value(metricId: String): Double? = values[metricId]
}

/** A user-confirmed group of posts that are the same original video on different platforms. */
data class Video(
    val videoId: String,
    val posts: List<PlatformPost>,
) {
    val firstPublishedAt: Instant? get() = posts.minOfOrNull { it.publishedAt }
}
