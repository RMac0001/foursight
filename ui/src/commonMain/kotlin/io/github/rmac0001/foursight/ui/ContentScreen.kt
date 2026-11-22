package io.github.rmac0001.foursight.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.query.Format
import io.github.rmac0001.foursight.core.query.LibraryEntry
import io.github.rmac0001.foursight.core.query.MatchSuggestion
import io.github.rmac0001.foursight.core.query.Matching
import io.github.rmac0001.foursight.core.query.PostStats
import io.github.rmac0001.foursight.core.query.StatsBasis
import io.github.rmac0001.foursight.core.query.VersusAverage
import io.github.rmac0001.foursight.core.query.VideoComparison
import io.github.rmac0001.foursight.core.query.VideoStats
import io.github.rmac0001.foursight.core.query.compare
import io.github.rmac0001.foursight.core.query.postHistory
import io.github.rmac0001.foursight.core.query.versusAverage
import io.github.rmac0001.foursight.core.query.videoStats
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ContentScreen(
    services: AppServices,
    reload: Int,
    onChanged: () -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val zone = services.timeZone
    val library = rememberLoaded(reload) { services.queries.library() }
    LoadedView(library) { entries ->
        val open = selected?.let { id -> entries.firstOrNull { it.video.videoId == id } }
        if (open != null) {
            TextButton(onClick = { selected = null }) { Text("< All videos") }
            VideoDetail(services, open, entries, reload)
            return@LoadedView
        }
        ScreenTitle(
            "Content",
            "Every video, with its TikTok, Short, Reel and Instagram Reel grouped together once matched.",
        )
        MatchesPanel(services, entries, onChanged)
        OutlinedTextField(search, {
            search = it
        }, label = { Text("Search titles") }, singleLine = true, modifier = Modifier.width(360.dp))
        val shown =
            entries.filter { e ->
                search.isBlank() ||
                    e.video.posts.any { it.title?.contains(search.trim(), ignoreCase = true) == true }
            }
        Panel("${shown.size} videos", note = "Newest first. Click one for its numbers.") {
            shown.forEach { e ->
                val first = e.video.posts.minBy { it.publishedAt }
                Row(
                    Modifier.fillMaxWidth().clickable { selected = e.video.videoId }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        Labels.date(e.video.firstPublishedAt, zone),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(96.dp),
                    )
                    Text(
                        Labels.title(first.title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        e.video.posts
                            .sortedBy { it.platform.ordinal }
                            .joinToString(" · ") { it.platform.displayName } +
                            if (e.confirmed) "" else "  (unmatched)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun MatchesPanel(
    services: AppServices,
    entries: List<LibraryEntry>,
    onChanged: () -> Unit,
) {
    val suggestions =
        remember(entries) { Matching.suggest(entries.filter { !it.confirmed }.flatMap { it.video.posts }) }
    if (suggestions.isEmpty()) return
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    Panel(
        "Suggested matches",
        note = "Posts that look like the same video. Nothing is linked until you confirm. ${Matching.DEFINITION}",
    ) {
        suggestions.forEach { s ->
            SuggestionRow(s, services) { refs ->
                scope.launch {
                    message =
                        services.confirmMatch(refs)
                    ; onChanged()
                }
            }
        }
        message?.let { Muted(it) }
    }
}

@Composable
private fun SuggestionRow(
    s: MatchSuggestion,
    services: AppServices,
    onConfirm: (List<io.github.rmac0001.foursight.core.model.PostRef>) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${s.confidence.name.lowercase()} confidence: ${s.reason}", style = MaterialTheme.typography.bodySmall)
            s.posts.forEach { p ->
                Muted(
                    "${p.platform.displayName}  ${Labels.time(
                        p.publishedAt,
                        services.timeZone,
                    )}  ${Labels.title(p.title)}",
                )
            }
        }
        PrimaryButton(onClick = { onConfirm(s.posts.map { it.ref }) }) { Text("Confirm") }
    }
    HorizontalDivider()
}

private data class DetailData(
    val stats: VideoStats,
    val averages: Map<io.github.rmac0001.foursight.core.SocialPlatform, List<VersusAverage>>,
    val history: List<ChartSeriesSource>,
)

private data class ChartSeriesSource(
    val platform: io.github.rmac0001.foursight.core.SocialPlatform,
    val points: List<io.github.rmac0001.foursight.core.query.HistoryPoint>,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoDetail(
    services: AppServices,
    entry: LibraryEntry,
    all: List<LibraryEntry>,
    reload: Int,
) {
    var basis by remember { mutableStateOf(StatsBasis.LATEST) }
    var compareWith by remember { mutableStateOf<LibraryEntry?>(null) }
    val zone = services.timeZone
    val first = entry.video.posts.minBy { it.publishedAt }
    ScreenTitle(Labels.title(first.title), "First published ${Labels.time(entry.video.firstPublishedAt, zone)}")
    ChoiceChips(StatsBasis.entries, basis, {
        if (it ==
            StatsBasis.LATEST
        ) {
            "Latest numbers"
        } else {
            "Day 7"
        }
    }) { basis = it }
    val state =
        rememberLoaded(reload, entry, basis) {
            val now = services.now()
            DetailData(
                services.queries.videoStats(entry, basis, now),
                entry.video.posts.associate { it.platform to services.queries.versusAverage(it, now) },
                entry.video.posts.map { ChartSeriesSource(it.platform, services.queries.postHistory(it)) },
            )
        }
    LoadedView(state) { d ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                entry.video.posts.sortedBy { it.platform.ordinal }.forEach { p ->
                    p.url?.let { url ->
                        SecondaryButton(
                            onClick = { services.openUrl(url) },
                        ) { Text("Open on ${p.platform.displayName}") }
                    }
                }
            }
            if (d.stats.totals.isNotEmpty()) {
                Panel("All platforms", note = "Counts only. Rates and durations are never combined across platforms.") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        d.stats.totals.forEach {
                            StatTile(
                                Labels.computedLabel(it.id),
                                Labels.computed(it),
                                it.missingReason,
                            )
                        }
                    }
                }
            }
            d.stats.posts.forEach { s -> PostPanel(s, d.averages[s.post.platform].orEmpty(), zone) }
            Panel(
                "Views over time",
                note = "Each post's lifetime views at each day's capture, by days since it was published.",
            ) {
                LineChart(
                    d.history.map { h ->
                        ChartSeries(
                            h.platform.displayName,
                            platformColor(h.platform),
                            h.points.map { p ->
                                ChartPoint(
                                    x = (p.ageDays * 10).roundToInt(),
                                    xLabel = "day ${Format.number(p.ageDays, 1)}",
                                    y = p.value,
                                    detail =
                                        "day ${Format.number(
                                            p.ageDays,
                                            1,
                                        )}, captured ${Labels.time(p.capturedAt, zone)}, " +
                                            "source: ${sourceOf(h.platform, Metrics.VIEWS.id)}",
                                )
                            },
                        )
                    },
                )
            }
            ComparePanel(services, entry, all, compareWith, reload) { compareWith = it }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PostPanel(
    s: PostStats,
    averages: List<VersusAverage>,
    zone: kotlinx.datetime.TimeZone,
) {
    val title = "${s.post.platform.displayName}  (${s.post.ref})"
    val note =
        if (s.capturedAt == null) {
            s.noCaptureReason
        } else {
            "Captured ${Labels.time(s.capturedAt, zone)}, day ${Format.number(s.ageDays, 1)} after publishing"
        }
    Panel(title, note = note) {
        if (s.capturedAt == null) return@Panel
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.values.forEach { (id, v) -> StatTile(Metrics.get(id).label, Labels.metric(id, v)) }
            s.computed.forEach { c -> StatTile(Labels.computedLabel(c.id), Labels.computed(c), c.missingReason) }
            s.accountFollowerChange?.let { c ->
                StatTile(
                    "Account follower change",
                    Format.signed(c.value),
                    c.missingReason ?: "not attributed to this video",
                )
            }
        }
        if (averages.isNotEmpty()) {
            Text(
                "Compared with your other ${s.post.platform.displayName} posts at day 7",
                fontWeight = FontWeight.Medium,
            )
            averages.forEach { a ->
                val n = a.average.inputs["posts"]?.toInt() ?: 0
                KeyValue(
                    a.measure.label,
                    "${Format.number(
                        a.value,
                        if (a.measure.id == "views") 0 else 1,
                    )} vs ${Format.number(a.average.value, if (a.measure.id == "views") 0 else 1)}",
                    a.average.missingReason ?: "average of $n ${if (n == 1) "post" else "posts"}",
                )
            }
        }
        if (s.unavailable.isNotEmpty()) {
            Muted(
                "Not reported by ${s.post.platform.displayName}: " +
                    s.unavailable.keys.joinToString { Metrics.get(it).label },
            )
        }
    }
}

@Composable
private fun ComparePanel(
    services: AppServices,
    entry: LibraryEntry,
    all: List<LibraryEntry>,
    other: LibraryEntry?,
    reload: Int,
    onPick: (LibraryEntry) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Panel(
        "Compare with another video",
        note = "Day 7 compares both at the same age. These are differences, not causes.",
    ) {
        Row {
            SecondaryButton(onClick = { open = true }) {
                Text(
                    other?.let {
                        Labels.title(
                            it.video.posts
                                .minBy { p -> p.publishedAt }
                                .title,
                        )
                    } ?: "Choose a video",
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                all.filter { it != entry }.take(60).forEach { e ->
                    DropdownMenuItem(text = {
                        Text(
                            Labels.title(
                                e.video.posts
                                    .minBy { it.publishedAt }
                                    .title,
                            ),
                        )
                    }, onClick = {
                        open = false
                        onPick(e)
                    })
                }
            }
        }
        if (other == null) return@Panel
        val c = rememberLoaded(reload, entry, other) { services.queries.compare(entry, other, services.now()) }
        LoadedView(c) { CompareTable(it) }
    }
}

@Composable
private fun CompareTable(c: VideoComparison) {
    val platforms = (c.a.posts + c.b.posts).map { it.post.platform }.distinct().sortedBy { it.ordinal }
    Row(Modifier.fillMaxWidth()) {
        Text("Day 7", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text("This video", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(120.dp))
        Text("The other", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(120.dp))
    }
    platforms.forEach { p ->
        val a = c.a.posts.firstOrNull { it.post.platform == p }
        val b = c.b.posts.firstOrNull { it.post.platform == p }
        Text(p.displayName, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        val ids = (a?.values?.keys.orEmpty() + b?.values?.keys.orEmpty()).distinct()
        ids.forEach { id ->
            Row(Modifier.fillMaxWidth()) {
                Text(Metrics.get(id).label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(
                    cell(a) {
                        Labels.metric(id, it.values[id])
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(120.dp),
                )
                Text(
                    cell(b) {
                        Labels.metric(id, it.values[id])
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(120.dp),
                )
            }
        }
        val computed = (a?.computed.orEmpty() + b?.computed.orEmpty()).map { it.id }.distinct()
        computed.forEach { id ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    Labels.computedLabel(id),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    cell(a) { s ->
                        s.computed
                            .firstOrNull {
                                it.id == id
                            }?.let { Labels.computed(it) } ?: "-"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(120.dp),
                )
                Text(
                    cell(b) { s ->
                        s.computed
                            .firstOrNull {
                                it.id == id
                            }?.let { Labels.computed(it) } ?: "-"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(120.dp),
                )
            }
        }
    }
}

private fun cell(
    s: PostStats?,
    show: (PostStats) -> String,
): String =
    when {
        s == null -> "not posted"
        s.capturedAt == null -> s.noCaptureReason?.take(18) ?: "-"
        else -> show(s)
    }

/** The API field a platform's metric comes from, for chart details. */
fun sourceOf(
    platform: io.github.rmac0001.foursight.core.SocialPlatform,
    metricId: String,
): String =
    when (val a = Metrics.get(metricId).availability(platform)) {
        is io.github.rmac0001.foursight.core.metrics.Availability.Available -> a.source
        is io.github.rmac0001.foursight.core.metrics.Availability.Unavailable -> "not reported"
    }
