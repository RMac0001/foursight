package io.github.rmac0001.foursight.core.collect

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Day7
import io.github.rmac0001.foursight.core.metrics.Day7Result
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.store.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** How one platform's part of a run went. [error] is safe to show and store (no secrets). */
data class PlatformRunResult(
    val platform: SocialPlatform,
    val failure: FailureKind?,
    val error: String?,
    val posts: Int,
    val newSnapshots: Int,
    val warnings: List<String>,
) {
    val ok: Boolean get() = failure == null && error == null
}

/**
 * Runs collectors and stores what they return (spec: Architecture, "Daily collection"). Each
 * platform runs on its own: one failing never blocks the others. A platform's posts and snapshots are
 * written in one transaction, so a failed run leaves its earlier data untouched. Every attempt is
 * recorded in the connection status, success or not.
 *
 * [afterStore] runs after a platform's data is stored (the Drive publish once Google is connected); a
 * failure there becomes a warning and never undoes the stored data.
 */
class CollectionRunner(
    private val repo: Repository,
    private val collectors: List<PlatformCollector>,
    private val timeZone: TimeZone,
    private val clock: () -> Instant,
    private val afterStore: suspend (SocialPlatform, LocalDate) -> Unit = { _, _ -> },
) {
    val platforms: List<SocialPlatform> get() = collectors.map { it.platform }

    suspend fun run(only: Set<SocialPlatform>? = null): List<PlatformRunResult> =
        collectors
            .filter { only == null || it.platform in only }
            .map { runOne(it) }

    /**
     * The extra run for posts near day 7: collects only the platforms that have a post inside the
     * day-7 window (day 7.0 to 8.0) without a capture there yet. Returns an empty list when none do.
     */
    suspend fun runDay7Due(): List<PlatformRunResult> {
        val due = day7DuePosts(clock()).map { it.platform }.toSet()
        return if (due.isEmpty()) emptyList() else run(due)
    }

    /** Posts past day 7.0, not past day 8.0, with no capture in the window yet. */
    fun day7DuePosts(now: Instant): List<PlatformPost> =
        repo.posts().filter { post ->
            Day7.lock(post.publishedAt, repo.snapshots(post.platform, ItemKind.POST, post.postId), now) ==
                Day7Result.Pending
        }

    private suspend fun runOne(collector: PlatformCollector): PlatformRunResult {
        val platform = collector.platform
        val now = clock()
        val day = now.toLocalDateTime(timeZone).date
        val collection =
            try {
                collector.collect(now, repo.deviceId(), day)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CollectionException) {
                repo.recordAttempt(platform, now, e.message)
                return PlatformRunResult(platform, e.kind, e.message, 0, 0, emptyList())
            } catch (e: Exception) {
                // Only the class name: an unexpected exception's message could carry anything.
                val message = "${platform.displayName}: unexpected ${e::class.simpleName}"
                repo.recordAttempt(platform, now, message)
                return PlatformRunResult(platform, FailureKind.PLATFORM_ERROR, message, 0, 0, emptyList())
            }
        val inserted =
            repo.transaction {
                repo.upsertPosts(collection.posts, now)
                repo.insertSnapshots(collection.snapshots)
            }
        repo.recordAttempt(platform, now, null)
        collection.tokenExpiresAt?.let { repo.setTokenExpiry(platform, it) }
        val warnings = collection.warnings.toMutableList()
        try {
            afterStore(platform, day)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnings += "Stored locally; sync didn't finish (${e::class.simpleName}). It catches up next run."
        }
        return PlatformRunResult(platform, null, null, collection.posts.size, inserted, warnings)
    }
}
