package io.github.rmac0001.foursight.core.query

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Computed
import io.github.rmac0001.foursight.core.metrics.Day7Result
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.model.Video
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** One capture of a post's metric, for its performance-over-time chart. */
data class HistoryPoint(
    val capturedAt: Instant,
    /** Days from publishing to the capture, exact. */
    val ageDays: Double,
    /** Null when the capture is missing this metric (a gap, never 0). */
    val value: Double?,
)

/** A post's metric over its life: each day's latest capture from any device. */
fun QueryService.postHistory(
    post: PlatformPost,
    metricId: String = Metrics.VIEWS.id,
): List<HistoryPoint> =
    repo
        .latestPerDay(post.platform, ItemKind.POST, post.postId)
        .map { s -> HistoryPoint(s.capturedAt, (s.capturedAt - post.publishedAt) / 1.days, s.value(metricId)) }

const val AVERAGE_DEFINITION =
    "Mean of the same platform's other posts with a day-7 lock, each at its own day-7 capture. Compared " +
        "within one platform only; never averaged across platforms."

/** Which day-7 number an average is taken of. */
enum class Day7Measure(
    val id: String,
    val label: String,
) {
    VIEWS("views", "Views at day 7"),
    SHARES_PER_1K("shares_per_1k", "Shares per 1,000 at day 7"),
    FOLLOWERS_PER_1K("followers_per_1k", "Followers per 1,000 at day 7"),
    SAVES_PER_1K("saves_per_1k", "Saves per 1,000 at day 7"),
}

data class VersusAverage(
    val measure: Day7Measure,
    /** This post's own day-7 value; null if it isn't locked or the value is missing. */
    val value: Double?,
    /** The mean of the platform's other day-7 posts; inputs carry the sample size. */
    val average: Computed,
)

private fun PostSevenDay.measure(m: Day7Measure): Double? =
    if (result !is Day7Result.Locked) {
        null
    } else {
        when (m) {
            Day7Measure.VIEWS -> values[Metrics.VIEWS.id]
            Day7Measure.SHARES_PER_1K -> sharesPer1k?.value
            Day7Measure.FOLLOWERS_PER_1K -> followersPer1k?.value
            Day7Measure.SAVES_PER_1K -> savesPer1k?.value
        }
    }

/**
 * A post's day-7 numbers against the average of the same platform's other posts at day 7 (spec:
 * Per-video view, "comparison with your averages"; Comparisons, "performance against your own
 * average"). The sample size is in each average's inputs.
 */
fun QueryService.versusAverage(
    post: PlatformPost,
    now: Instant,
): List<VersusAverage> {
    fun sevenDay(p: PlatformPost) =
        sevenDayReport(LibraryEntry(Video("post:${p.ref}", listOf(p)), false), now).posts.single()
    val own = sevenDay(post)
    val others = repo.posts(post.platform).filter { it.ref != post.ref }.map { sevenDay(it) }
    val measures =
        buildList {
            add(Day7Measure.VIEWS)
            add(Day7Measure.SHARES_PER_1K)
            if (own.followersPer1kUnavailable == null) add(Day7Measure.FOLLOWERS_PER_1K)
            if (post.platform == SocialPlatform.INSTAGRAM) add(Day7Measure.SAVES_PER_1K)
        }
    return measures.map { m ->
        val values = others.mapNotNull { it.measure(m) }
        val average =
            Computed(
                id = "${m.id}_average",
                value = if (values.isEmpty()) null else values.average(),
                definition = AVERAGE_DEFINITION,
                inputs = mapOf("posts" to values.size.toDouble()),
                missingReason =
                    if (values.isEmpty()) {
                        "No other ${post.platform.displayName} post has a day-7 " +
                            "value yet."
                    } else {
                        null
                    },
            )
        VersusAverage(m, own.measure(m), average)
    }
}
