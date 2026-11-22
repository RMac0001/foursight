package io.github.rmac0001.foursight.core.query

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Availability
import io.github.rmac0001.foursight.core.metrics.MetricDefinition
import io.github.rmac0001.foursight.core.metrics.MetricKind
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.ItemKind
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** One day in a trend. A null [value] is a gap: shown as missing, never filled in. */
data class TrendPoint(
    val day: LocalDate,
    /** The total at that day's capture: followers, or lifetime counts summed over the platform's posts. */
    val value: Double?,
    /** [value] minus the previous day's; null when either day is missing. */
    val change: Double?,
    val capturedAt: Instant?,
    val missingReason: String?,
)

data class TrendSeries(
    val platform: SocialPlatform,
    val metric: MetricDefinition,
    val points: List<TrendPoint>,
    /** Why there's no series, when the platform doesn't provide the metric. */
    val unavailableReason: String?,
)

data class Trends(
    val days: Int,
    val from: LocalDate,
    val to: LocalDate,
    val series: List<TrendSeries>,
    val definition: String,
    val generatedAt: Instant,
    val definitionsVersion: Int,
)

const val TREND_DEFINITION =
    "Per platform and day, using each day's latest capture from any device. Followers: the account's count. " +
        "Post counts: the lifetime count summed over every post published before that day's capture; if any such " +
        "post wasn't captured that day, the day is a gap. Change: that day's total minus the previous day's."

/** Metrics a trend can follow: counts only, since rates and durations aren't summed. */
val trendMetrics: List<MetricDefinition> =
    listOf(Metrics.FOLLOWERS, Metrics.VIEWS, Metrics.LIKES, Metrics.COMMENTS, Metrics.SHARES, Metrics.SAVES)

/** Daily trends over the last [days] days, ending today in [timeZone] (spec: `get_trends`). */
fun QueryService.trends(
    days: Int,
    metrics: List<MetricDefinition>,
    now: Instant,
    timeZone: TimeZone,
    platforms: Set<SocialPlatform> = SocialPlatform.entries.toSet(),
): Trends {
    require(days in 1..3650) { "days must be between 1 and 3650" }
    metrics.forEach { require(it.kind == MetricKind.COUNT && it in trendMetrics) { "${it.id} can't be trended." } }
    val to = now.toLocalDateTime(timeZone).date
    val from = to.minus(days - 1, DateTimeUnit.DAY)
    val dayList = generateSequence(from) { it.plus(1, DateTimeUnit.DAY) }.takeWhile { it <= to }.toList()
    val series =
        SocialPlatform.entries.filter { it in platforms }.flatMap { platform ->
            metrics.map { metric ->
                when (val a = metric.availability(platform)) {
                    is Availability.Unavailable -> TrendSeries(platform, metric, emptyList(), a.reason)
                    is Availability.Available -> TrendSeries(platform, metric, points(platform, metric, dayList), null)
                }
            }
        }
    return Trends(days, from, to, series, TREND_DEFINITION, now, Metrics.DEFINITIONS_VERSION)
}

private data class DayValue(
    val value: Double?,
    val capturedAt: Instant?,
    val missingReason: String?,
)

private fun QueryService.points(
    platform: SocialPlatform,
    metric: MetricDefinition,
    dayList: List<LocalDate>,
): List<TrendPoint> {
    val account =
        repo
            .latestPerDay(
                platform,
                ItemKind.ACCOUNT,
                accountItemId(platform) ?: "",
            ).associateBy { it.captureDay }
    val values: Map<LocalDate, DayValue> =
        if (metric.itemKind == ItemKind.ACCOUNT) {
            dayList.associateWith { day ->
                val s = account[day]
                when {
                    s == null -> DayValue(null, null, "No capture that day.")
                    s.value(metric.id) == null -> DayValue(null, s.capturedAt, "Missing in that day's capture.")
                    else -> DayValue(s.value(metric.id), s.capturedAt, null)
                }
            }
        } else {
            postTotals(platform, metric, dayList)
        }
    var previous: Double? = null
    return dayList.map { day ->
        val v = values.getValue(day)
        val change = if (v.value != null && previous != null) v.value - previous else null
        previous = v.value
        TrendPoint(day, v.value, change, v.capturedAt, v.missingReason)
    }
}

/** The account item's id on a platform: FourSight tracks one account per platform. */
private fun QueryService.accountItemId(platform: SocialPlatform): String? =
    repo.accountSnapshots(platform).lastOrNull()?.itemId

private fun QueryService.postTotals(
    platform: SocialPlatform,
    metric: MetricDefinition,
    dayList: List<LocalDate>,
): Map<LocalDate, DayValue> {
    val posts = repo.posts(platform)
    val perPost =
        posts.associateWith { p ->
            repo.latestPerDay(platform, ItemKind.POST, p.postId).associateBy { it.captureDay }
        }
    return dayList.associateWith { day ->
        val captured = perPost.mapNotNull { (_, byDay) -> byDay[day] }
        if (captured.isEmpty()) return@associateWith DayValue(null, null, "No capture that day.")
        val lastCapture = captured.maxOf { it.capturedAt }
        val expected = posts.filter { it.publishedAt <= lastCapture }
        val notCaptured = expected.count { perPost.getValue(it)[day] == null }
        val missingValue = captured.count { it.value(metric.id) == null }
        when {
            notCaptured > 0 -> {
                DayValue(null, lastCapture, "$notCaptured post(s) weren't captured that day.")
            }

            missingValue > 0 -> {
                DayValue(null, lastCapture, "$missingValue post(s) are missing ${metric.label.lowercase()} that day.")
            }

            else -> {
                DayValue(captured.sumOf { it.value(metric.id)!! }, lastCapture, null)
            }
        }
    }
}
