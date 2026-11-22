package io.github.rmac0001.foursight.core.query

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Availability
import io.github.rmac0001.foursight.core.metrics.Calculations
import io.github.rmac0001.foursight.core.metrics.Computed
import io.github.rmac0001.foursight.core.metrics.Day7
import io.github.rmac0001.foursight.core.metrics.Day7Result
import io.github.rmac0001.foursight.core.metrics.MetricDefinition
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.model.PostRef
import io.github.rmac0001.foursight.core.model.Video
import io.github.rmac0001.foursight.core.store.ConnectionStatus
import io.github.rmac0001.foursight.core.store.Repository
import io.github.rmac0001.foursight.core.sync.MatchEvents
import kotlin.time.Instant

/** One entry in the content library: a confirmed group, or a single post not yet matched. */
data class LibraryEntry(
    val video: Video,
    val confirmed: Boolean,
)

data class PlatformFreshness(
    val platform: SocialPlatform,
    val latestCapture: Instant?,
    val status: ConnectionStatus?,
)

/** The 7-day numbers for one post (spec: Day-7 lock). */
data class PostSevenDay(
    val post: PlatformPost,
    val result: Day7Result,
    /** Values from the locked snapshot; empty unless [result] is [Day7Result.Locked]. */
    val values: Map<String, Double?>,
    val savesPer1k: Computed?,
    val sharesPer1k: Computed?,
    /** Present only where the platform reports follows per post (YouTube, Facebook). */
    val followersPer1k: Computed?,
    /** Why there's no per-post follower number, where the platform doesn't report one. */
    val followersPer1kUnavailable: String?,
    /**
     * Where per-post follows aren't reported: the account's follower change from the last capture
     * before publishing to the day-7 capture. All follows in that window count, whatever brought
     * them in, so this is never attributed to the post.
     */
    val accountFollowerChange: Computed?,
)

data class SevenDayReport(
    val entry: LibraryEntry,
    val posts: List<PostSevenDay>,
    val generatedAt: Instant,
    val definitionsVersion: Int,
)

/**
 * Read-only queries shared by the UI, CLI and MCP (spec: Architecture, "One engine"). Nothing here
 * writes, so the MCP can call any of it safely.
 */
class QueryService(
    internal val repo: Repository,
) {
    fun definitions(): List<MetricDefinition> = Metrics.all

    fun freshness(): List<PlatformFreshness> {
        val statuses = repo.connectionStatus().associateBy { it.platform }
        return SocialPlatform.entries.map { PlatformFreshness(it, repo.latestCapture(it), statuses[it]) }
    }

    /** Confirmed groups plus every unmatched post as its own entry, newest first. */
    fun library(platform: SocialPlatform? = null): List<LibraryEntry> {
        val posts = repo.posts().associateBy { it.ref }
        val links = MatchEvents.currentLinks(repo.events())
        val grouped =
            links.entries
                .groupBy({ it.value }, { it.key })
                .mapNotNull { (videoId, refs) ->
                    val members = refs.mapNotNull { posts[it] }
                    if (members.isEmpty()) null else LibraryEntry(Video(videoId, members), confirmed = true)
                }
        val unmatched =
            posts.values
                .filter { it.ref !in links }
                .map { LibraryEntry(Video("post:${it.ref}", listOf(it)), confirmed = false) }
        return (grouped + unmatched)
            .filter { platform == null || it.video.posts.any { p -> p.platform == platform } }
            .sortedByDescending { it.video.firstPublishedAt }
    }

    /** Finds an entry by video id, by "platform:postId", or by a bare post id. */
    fun findEntry(key: String): LibraryEntry? {
        val entries = library()
        return entries.firstOrNull { it.video.videoId == key }
            ?: entries.firstOrNull { e -> e.video.posts.any { it.ref.toString() == key } }
            ?: entries.firstOrNull { e -> e.video.posts.any { it.postId == key } }
    }

    fun sevenDayReport(
        entry: LibraryEntry,
        now: Instant,
    ): SevenDayReport =
        SevenDayReport(
            entry = entry,
            posts =
                entry.video.posts
                    .sortedBy { it.platform.ordinal }
                    .map { sevenDayFor(it, now) },
            generatedAt = now,
            definitionsVersion = Metrics.DEFINITIONS_VERSION,
        )

    private fun sevenDayFor(
        post: PlatformPost,
        now: Instant,
    ): PostSevenDay {
        val snapshots = repo.snapshots(post.platform, ItemKind.POST, post.postId)
        val result = Day7.lock(post.publishedAt, snapshots, now)
        val followersAvailability = Metrics.FOLLOWERS_GAINED.availability(post.platform)
        val unavailableReason = (followersAvailability as? Availability.Unavailable)?.reason
        if (result !is Day7Result.Locked) {
            return PostSevenDay(post, result, emptyMap(), null, null, null, unavailableReason, null)
        }
        val s = result.snapshot
        val views = s.value(Metrics.VIEWS.id)
        val saves =
            if (Metrics.SAVES.availability(post.platform) is Availability.Available) {
                Calculations.per1k("saves_per_1k", s.value(Metrics.SAVES.id), views)
            } else {
                null
            }
        val shares = Calculations.per1k("shares_per_1k", s.value(Metrics.SHARES.id), views)
        val followers =
            if (unavailableReason == null) {
                Calculations.per1k("followers_per_1k", s.value(Metrics.FOLLOWERS_GAINED.id), views)
            } else {
                null
            }
        val accountChange =
            if (unavailableReason != null) accountFollowerChange(post, result) else null
        return PostSevenDay(post, result, s.values, saves, shares, followers, unavailableReason, accountChange)
    }

    private fun accountFollowerChange(
        post: PlatformPost,
        lock: Day7Result.Locked,
    ): Computed {
        val account = repo.accountSnapshots(post.platform)
        val before = account.lastOrNull { it.capturedAt <= post.publishedAt }
        // The account's own day-7 capture, under the same window rule as the post's.
        val at =
            (Day7.lock(post.publishedAt, account, lock.snapshot.capturedAt + Day7.LOCK_WINDOW) as? Day7Result.Locked)
                ?.snapshot
        val change =
            Calculations.change(
                "account_follower_change",
                before?.value(Metrics.FOLLOWERS.id),
                at?.value(Metrics.FOLLOWERS.id),
            )
        return when {
            before == null -> {
                change.copy(value = null, missingReason = "No account capture from before the post was published.")
            }

            at == null -> {
                change.copy(value = null, missingReason = "No account capture in the day-7 window.")
            }

            else -> {
                change.copy(
                    definition =
                        "Account followers at ${at.capturedAt} minus at ${before.capturedAt}. Counts every " +
                            "follow in that window, whatever brought it in; not attributed to this post.",
                )
            }
        }
    }

    fun findPost(ref: PostRef): PlatformPost? = repo.post(ref)
}
