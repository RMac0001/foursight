package io.github.rmac0001.foursight.core.query

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Availability
import io.github.rmac0001.foursight.core.metrics.Calculations
import io.github.rmac0001.foursight.core.metrics.Computed
import io.github.rmac0001.foursight.core.metrics.Day7Result
import io.github.rmac0001.foursight.core.metrics.MetricDefinition
import io.github.rmac0001.foursight.core.metrics.MetricKind
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.model.Snapshot
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Which capture a set of numbers comes from. */
enum class StatsBasis {
    /** The most recent capture from any device. */
    LATEST,

    /** The day-7 lock (spec: Day-7 lock). */
    DAY_7,
}

/** One post's numbers from one capture, with what's unavailable and why. */
data class PostStats(
    val post: PlatformPost,
    val basis: StatsBasis,
    /** The capture the numbers come from; null when there isn't one for this basis. */
    val capturedAt: Instant?,
    /** Days from publishing to [capturedAt], exact (a day-7 capture at 7.4 days says 7.4). */
    val ageDays: Double?,
    /** Why there are no numbers, when [capturedAt] is null. */
    val noCaptureReason: String?,
    /** The day-7 state; set only for [StatsBasis.DAY_7]. */
    val day7: Day7Result?,
    /** Metrics the platform provides. A null value is missing in the capture, never zero. */
    val values: Map<String, Double?>,
    /** Metrics the platform doesn't provide, with the reason. */
    val unavailable: Map<String, String>,
    val computed: List<Computed>,
    /** Where the platform doesn't report follows per post: the labeled account change instead. */
    val accountFollowerChange: Computed?,
)

data class VideoStats(
    val entry: LibraryEntry,
    val basis: StatsBasis,
    val posts: List<PostStats>,
    /** Counts summed across platforms (spec: Combining rules). Rates and durations never are. */
    val totals: List<Computed>,
    val generatedAt: Instant,
    val definitionsVersion: Int,
)

/** The library, optionally limited to one platform and to videos first published at or after [since]. */
fun QueryService.videos(
    platform: SocialPlatform? = null,
    since: Instant? = null,
): List<LibraryEntry> =
    library(platform).filter { since == null || (it.video.firstPublishedAt ?: Instant.DISTANT_PAST) >= since }

/** The most recent capture of a post, from any device. */
fun QueryService.latestSnapshot(post: PlatformPost): Snapshot? =
    repo.snapshots(post.platform, ItemKind.POST, post.postId).maxByOrNull { it.capturedAt }

/** One video's numbers on every platform it's on, live or at day 7. */
fun QueryService.videoStats(
    entry: LibraryEntry,
    basis: StatsBasis,
    now: Instant,
): VideoStats {
    val posts = entry.video.posts.sortedBy { it.platform.ordinal }
    val stats =
        when (basis) {
            StatsBasis.LATEST -> {
                posts.map { latestStats(it) }
            }

            StatsBasis.DAY_7 -> {
                val report = sevenDayReport(entry, now)
                report.posts.map { day7Stats(it) }
            }
        }
    return VideoStats(entry, basis, stats, totals(stats), now, Metrics.DEFINITIONS_VERSION)
}

private val postMetrics: List<MetricDefinition> = Metrics.all.filter { it.itemKind == ItemKind.POST }

/** The post-level counts that are summed across a video's platforms. */
internal val summedCounts: List<MetricDefinition> =
    listOf(Metrics.VIEWS, Metrics.LIKES, Metrics.COMMENTS, Metrics.SHARES, Metrics.SAVES)

private fun split(
    platform: SocialPlatform,
    snapshot: Snapshot?,
): Pair<Map<String, Double?>, Map<String, String>> {
    val values = linkedMapOf<String, Double?>()
    val unavailable = linkedMapOf<String, String>()
    postMetrics.forEach { m ->
        when (val a = m.availability(platform)) {
            is Availability.Available -> values[m.id] = snapshot?.value(m.id)
            is Availability.Unavailable -> unavailable[m.id] = a.reason
        }
    }
    return values to unavailable
}

/** Views per day, engagement rate and the per-1,000 rates the platform supports, from one capture. */
internal fun computedFor(
    post: PlatformPost,
    s: Snapshot,
): List<Computed> {
    val views = s.value(Metrics.VIEWS.id)
    return buildList {
        add(Calculations.viewsPerDay(views, post.publishedAt, s.capturedAt))
        add(Calculations.engagementRate(s, post.platform))
        add(Calculations.per1k("shares_per_1k", s.value(Metrics.SHARES.id), views))
        if (Metrics.SAVES.availability(post.platform) is Availability.Available) {
            add(Calculations.per1k("saves_per_1k", s.value(Metrics.SAVES.id), views))
        }
        if (Metrics.FOLLOWERS_GAINED.availability(post.platform) is Availability.Available) {
            add(Calculations.per1k("followers_per_1k", s.value(Metrics.FOLLOWERS_GAINED.id), views))
        }
    }
}

private fun QueryService.latestStats(post: PlatformPost): PostStats {
    val s = latestSnapshot(post)
    val (values, unavailable) = split(post.platform, s)
    return PostStats(
        post = post,
        basis = StatsBasis.LATEST,
        capturedAt = s?.capturedAt,
        ageDays = s?.let { (it.capturedAt - post.publishedAt) / 1.days },
        noCaptureReason = if (s == null) "Not collected yet." else null,
        day7 = null,
        values = values,
        unavailable = unavailable,
        computed = s?.let { computedFor(post, it) } ?: emptyList(),
        accountFollowerChange = null,
    )
}

private fun day7Stats(d: PostSevenDay): PostStats {
    val locked = d.result as? Day7Result.Locked
    val (values, unavailable) = split(d.post.platform, locked?.snapshot)
    return PostStats(
        post = d.post,
        basis = StatsBasis.DAY_7,
        capturedAt = locked?.snapshot?.capturedAt,
        ageDays = locked?.ageDays,
        noCaptureReason = if (locked == null) day7Reason(d.result) else null,
        day7 = d.result,
        values = values,
        unavailable = unavailable,
        computed = locked?.let { computedFor(d.post, it.snapshot) } ?: emptyList(),
        accountFollowerChange = d.accountFollowerChange,
    )
}

/** Plain words for a day-7 state with no locked numbers. */
fun day7Reason(result: Day7Result): String? =
    when (result) {
        is Day7Result.Locked -> {
            null
        }

        Day7Result.NotYetDue -> {
            "Not 7 days old yet."
        }

        Day7Result.Pending -> {
            "Past day 7; waiting for the day-7 capture (the hourly check collects it)."
        }

        is Day7Result.Missed -> {
            val first = result.firstCaptureAfterAge?.let { " The first capture after day 7 was at day ${it / 1.days}." }
            "Missed: no capture between day 7.0 and day 8.0, so there's no day-7 number.${first ?: ""}"
        }
    }

/**
 * Sums each count across the video's platforms that provide it. A platform with the metric but no
 * value makes the total missing (never a partial sum presented as complete).
 */
private fun totals(stats: List<PostStats>): List<Computed> {
    if (stats.size < 2) return emptyList()
    return summedCounts.mapNotNull { m ->
        check(m.kind == MetricKind.COUNT)
        val perPlatform =
            stats
                .filter { m.id in it.values }
                .groupBy { it.post.platform }
                .mapValues { (_, list) ->
                    val vs = list.map { it.values[m.id] }
                    if (vs.any { it == null }) null else vs.sumOf { it!! }
                }
        if (perPlatform.size < 2) null else Calculations.sumAcrossPlatforms(m, perPlatform)
    }
}

/**
 * The 7-day reports for every video with a post that locked its day-7 numbers in the last [days]
 * days: the Sunday review's list (The Playbook, "Claude pulls the 7-day numbers for every video
 * that reached day 7").
 */
fun QueryService.recentSevenDayReports(
    days: Int,
    now: Instant,
): List<SevenDayReport> =
    library()
        .map { sevenDayReport(it, now) }
        .filter { r ->
            r.posts.any { d ->
                val locked = d.result as? Day7Result.Locked
                locked != null && locked.snapshot.capturedAt >= now - days.days
            }
        }
