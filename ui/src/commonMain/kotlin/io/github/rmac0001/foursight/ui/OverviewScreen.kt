package io.github.rmac0001.foursight.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Calculations
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.query.AccountSummary
import io.github.rmac0001.foursight.core.query.Format
import io.github.rmac0001.foursight.core.query.NO_POSTS_TO_COUNT
import io.github.rmac0001.foursight.core.query.RankedPost
import io.github.rmac0001.foursight.core.query.Trends
import io.github.rmac0001.foursight.core.query.accountSummaryBetween
import io.github.rmac0001.foursight.core.query.trendsBetween
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** The spec's date filters (Overview, "Date filters"). 28 days, to match YouTube's and TikTok's analytics. */
enum class DateFilter(
    val label: String,
) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    LAST_7("Last 7"),
    LAST_28("Last 28"),
    LAST_90("Last 90"),
    CUSTOM("Custom range"),
    ALL("All history"),
}

/** A resolved range: instants for the summary, local days for the trend charts. */
data class ResolvedRange(
    val start: Instant?,
    val end: Instant,
    val fromDay: LocalDate,
    val toDay: LocalDate,
    val label: String,
)

fun resolve(
    filter: DateFilter,
    now: Instant,
    zone: TimeZone,
    customFrom: LocalDate?,
    customTo: LocalDate?,
    dataStarts: Instant?,
): ResolvedRange? {
    val today = now.toLocalDateTime(zone).date

    fun last(n: Int) = ResolvedRange(now - n.days, now, today.minus(n - 1, DateTimeUnit.DAY), today, "last $n days")
    return when (filter) {
        DateFilter.TODAY -> {
            ResolvedRange(today.atStartOfDayIn(zone), now, today, today, "today")
        }

        DateFilter.YESTERDAY -> {
            val y = today.minus(1, DateTimeUnit.DAY)
            ResolvedRange(y.atStartOfDayIn(zone), today.atStartOfDayIn(zone), y, y, "yesterday")
        }

        DateFilter.LAST_7 -> {
            last(7)
        }

        DateFilter.LAST_28 -> {
            last(28)
        }

        DateFilter.LAST_90 -> {
            last(90)
        }

        DateFilter.ALL -> {
            val first = dataStarts?.toLocalDateTime(zone)?.date ?: today
            ResolvedRange(null, now, first, today, "all history")
        }

        DateFilter.CUSTOM -> {
            if (customFrom == null || customTo == null || customFrom > customTo) return null
            val end = minOf(customTo.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone), now)
            ResolvedRange(customFrom.atStartOfDayIn(zone), end, customFrom, customTo, "$customFrom to $customTo")
        }
    }
}

private data class OverviewData(
    val summary: AccountSummary,
    val trends: Trends,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewScreen(
    services: AppServices,
    reload: Int,
) {
    var filter by remember { mutableStateOf(DateFilter.LAST_28) }
    var platforms by remember { mutableStateOf(SocialPlatform.entries.toSet()) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    val zone = services.timeZone

    ScreenTitle("Overview", "Totals, followers, trends, and the best and weakest videos.")
    ChoiceChips(DateFilter.entries, filter, { it.label }) { filter = it }
    if (filter == DateFilter.CUSTOM) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(fromText, {
                fromText = it
            }, label = { Text("From (2026-11-01)") }, singleLine = true, modifier = Modifier.width(200.dp))
            OutlinedTextField(toText, {
                toText = it
            }, label = { Text("To (2026-11-22)") }, singleLine = true, modifier = Modifier.width(200.dp))
        }
    }
    MultiChips(SocialPlatform.entries, platforms, { it.displayName }) { platforms = it }

    val from = runCatching { LocalDate.parse(fromText.trim()) }.getOrNull()
    val to = runCatching { LocalDate.parse(toText.trim()) }.getOrNull()
    val state =
        rememberLoaded(reload, filter, platforms, from, to) {
            val now = services.now()
            val dataStarts = services.queries.accountSummaryBetween(null, now, "", platforms).dataStartsAt
            val range = resolve(filter, now, zone, from, to, dataStarts) ?: return@rememberLoaded null
            // Days before the first capture aren't gaps, there was simply no collection yet: start there.
            val firstDay = dataStarts?.toLocalDateTime(zone)?.date
            val chartFrom =
                if (firstDay != null &&
                    firstDay > range.fromDay
                ) {
                    minOf(firstDay, range.toDay)
                } else {
                    range.fromDay
                }
            OverviewData(
                services.queries.accountSummaryBetween(range.start, range.end, range.label, platforms),
                services.queries.trendsBetween(
                    chartFrom,
                    range.toDay,
                    listOf(Metrics.FOLLOWERS, Metrics.VIEWS),
                    now,
                    platforms,
                ),
            )
        }
    LoadedView(state) { data ->
        if (data == null) {
            EmptyState("Enter both dates as yyyy-mm-dd, with the first on or before the second.")
            return@LoadedView
        }
        OverviewBody(data, services)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewBody(
    data: OverviewData,
    services: AppServices,
) {
    val s = data.summary
    val zone = services.timeZone
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.dataStartsAt != null && (s.start == null || s.dataStartsAt!! > s.start!!)) {
            Muted("Data starts ${Labels.time(s.dataStartsAt, zone)}, so ${s.label} is only partly covered.")
        }
        Panel(
            "Followers",
            note =
                "Counts can be summed across platforms; the total is missing if any " +
                    "platform's count is.",
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile(
                    "All selected platforms",
                    Format.number(s.followersTotal.value),
                    s.followersTotal.missingReason,
                )
                s.platforms.forEach { p ->
                    val change = p.followerChange
                    StatTile(
                        p.platform.displayName,
                        Format.number(p.followers),
                        if (change.value != null) {
                            "${Format.signed(change.value)} in " +
                                "${s.label}"
                        } else {
                            change.missingReason
                        },
                    )
                }
            }
        }
        Panel(
            "Gained in ${s.label}",
            note =
                "Latest capture minus the last capture before the range starts, " +
                    "per post. Older posts with no earlier capture are left out and counted.",
        ) {
            val ids = listOf(Metrics.VIEWS, Metrics.LIKES, Metrics.COMMENTS, Metrics.SHARES)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ids.forEach { m ->
                    // A platform with no posts has nothing to add; it's left out rather than making the total missing.
                    val perPlatform =
                        s.platforms
                            .mapNotNull { p ->
                                p.gained.firstOrNull { it.id == "${m.id}_gained" }?.let {
                                    p.platform to
                                        it
                                }
                            }.filter { (_, c) -> c.value != null || c.missingReason != NO_POSTS_TO_COUNT }
                            .associate { (platform, c) -> platform to c.value }
                    val total = Calculations.sumAcrossPlatforms(m, perPlatform)
                    StatTile(
                        m.label,
                        Format.signed(total.value),
                        total.missingReason
                            ?: perPlatform.entries.joinToString { "${it.key.displayName} ${Format.signed(it.value)}" },
                    )
                }
            }
            s.platforms.filter { it.postsWithoutBaseline > 0 }.forEach {
                Muted(
                    "${it.platform.displayName}: ${it.postsWithoutBaseline} older posts left out (no capture " +
                        "from before the range).",
                )
            }
        }
        Panel(
            "Followers over time",
            note =
                "Each platform on its own chart: their sizes are too different to " +
                    "share a scale.",
        ) {
            data.trends.series.filter { it.metric == Metrics.FOLLOWERS }.forEach { series ->
                Text(series.platform.displayName)
                if (series.unavailableReason != null) {
                    Muted(series.unavailableReason!!)
                } else {
                    LineChart(
                        listOf(
                            trendSeries(
                                series.platform.displayName,
                                platformColor(series.platform),
                                series.points,
                                zone,
                                change = false,
                                source = sourceOf(series.platform, Metrics.FOLLOWERS.id),
                            ),
                        ),
                    )
                }
            }
        }
        Panel(
            "Views gained per day",
            note =
                "Each day's total lifetime views minus the day before's. Gaps " +
                    "mean a day wasn't fully captured.",
        ) {
            LineChart(
                data.trends.series.filter { it.metric == Metrics.VIEWS && it.unavailableReason == null }.map {
                    trendSeries(
                        it.platform.displayName,
                        platformColor(it.platform),
                        it.points,
                        zone,
                        change = true,
                        source = "sum of each post's ${sourceOf(it.platform, Metrics.VIEWS.id)}",
                    )
                },
            )
        }
        Panel("Best and weakest videos", note = "Ranked by day-7 numbers, each platform on its own.") {
            s.platforms.forEach { p ->
                val r = p.ranking
                Text("${p.platform.displayName}: ${r.measure.label}, ${r.ranked} ranked")
                r.measure.note?.let { Muted(it) }
                if (r.best.isNotEmpty()) RankedList("Best", r.best)
                if (r.weakest.isNotEmpty()) RankedList("Weakest", r.weakest)
                if (r.notRanked.isNotEmpty()) {
                    Muted("Not ranked: " + r.notRanked.entries.joinToString { "${it.value} ${it.key}" })
                }
            }
        }
        Muted(
            "Generated ${Labels.time(s.generatedAt, zone)}, definitions v${s.definitionsVersion}. '-' is " +
                "missing, never zero.",
        )
    }
}

@Composable
private fun RankedList(
    label: String,
    items: List<RankedPost>,
) {
    items.forEach {
        Muted(
            "  $label: ${Labels.title(it.post.title)}  ${Format.number(it.value.value, 1)}  (day " +
                "${Format.number(it.ageDays, 1)})",
        )
    }
}

/** Turns trend points into chart points: totals, or day-over-day changes when [change] is set. */
fun trendSeries(
    name: String,
    color: androidx.compose.ui.graphics.Color,
    points: List<io.github.rmac0001.foursight.core.query.TrendPoint>,
    zone: TimeZone,
    change: Boolean,
    source: String,
): ChartSeries =
    ChartSeries(
        name,
        color,
        points.mapIndexed { i, p ->
            val y = if (change) p.change else p.value
            val why = p.missingReason ?: if (change && y == null) "needs the day before" else null
            ChartPoint(
                x = i,
                xLabel = p.day.toString().drop(5),
                y = y,
                detail = "${p.day}, " + (why ?: "captured ${Labels.time(p.capturedAt, zone)}") + "; source: $source",
            )
        },
    )
