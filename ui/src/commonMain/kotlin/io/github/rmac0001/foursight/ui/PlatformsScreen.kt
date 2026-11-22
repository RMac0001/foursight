package io.github.rmac0001.foursight.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Availability
import io.github.rmac0001.foursight.core.metrics.Calculations
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.model.Snapshot
import io.github.rmac0001.foursight.core.query.AudienceReport
import io.github.rmac0001.foursight.core.query.Format
import io.github.rmac0001.foursight.core.query.PlatformFreshness
import io.github.rmac0001.foursight.core.query.PlatformLimits
import io.github.rmac0001.foursight.core.query.audience
import io.github.rmac0001.foursight.core.query.latestSnapshot

private data class PlatformData(
    val freshness: PlatformFreshness,
    val account: Snapshot?,
    val posts: List<Pair<PlatformPost, Snapshot?>>,
    val audience: AudienceReport,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlatformsScreen(
    services: AppServices,
    reload: Int,
) {
    var platform by remember { mutableStateOf(SocialPlatform.TIKTOK) }
    val zone = services.timeZone
    ScreenTitle("Platforms", "Each platform's own numbers, what it doesn't report, and its audience.")
    ChoiceChips(SocialPlatform.entries, platform, { it.displayName }) { platform = it }
    val state =
        rememberLoaded(reload, platform) {
            val q = services.queries
            val posts =
                q
                    .library(platform)
                    .flatMap {
                        it.video.posts
                    }.filter { it.platform == platform }
                    .sortedByDescending { it.publishedAt }
            PlatformData(
                q.freshness().single { it.platform == platform },
                q.latestAccount(platform),
                posts.map { it to q.latestSnapshot(it) },
                q.audience(services.now(), setOf(platform)),
            )
        }
    LoadedView(state) { d ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile(
                    "Followers",
                    Format.number(d.account?.value(Metrics.FOLLOWERS.id)),
                    "captured ${Labels.time(d.account?.capturedAt, zone)}",
                )
                StatTile("Posts", d.posts.size.toString(), "public posts FourSight has seen")
                StatTile(
                    "Latest capture",
                    Labels.time(
                        d.freshness.latestCapture,
                        zone,
                    ),
                    d.freshness.status?.lastError?.let {
                        "last error: $it"
                    },
                )
            }
            Panel(
                "Posts",
                note =
                    "Lifetime numbers from each post's latest capture. '-' is missing; columns " +
                        "the platform doesn't report are left out.",
            ) {
                PostTable(platform, d.posts, zone)
            }
            Panel("What ${platform.displayName} doesn't report") {
                Metrics.all.forEach { m ->
                    (m.availability(platform) as? Availability.Unavailable)?.let { Muted("${m.label}: ${it.reason}") }
                }
            }
            AudiencePanel(d.audience, zone)
            Panel("Limits") { PlatformLimits.of(platform).forEach { Muted(it) } }
        }
    }
}

@Composable
private fun PostTable(
    platform: SocialPlatform,
    posts: List<Pair<PlatformPost, Snapshot?>>,
    zone: kotlinx.datetime.TimeZone,
) {
    if (posts.isEmpty()) {
        EmptyState("No posts yet. Collect from the Accounts section.")
        return
    }
    val cols =
        listOf(Metrics.VIEWS, Metrics.LIKES, Metrics.COMMENTS, Metrics.SHARES, Metrics.SAVES)
            .filter { it.availability(platform) is Availability.Available }
    Row(Modifier.fillMaxWidth()) {
        Text("Video", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text("Published", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
        cols.forEach { Text(it.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp)) }
        Text("Engagement", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
    }
    HorizontalDivider()
    posts.forEach { (post, s) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(
                Labels.title(post.title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                Labels.date(post.publishedAt, zone),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(96.dp),
            )
            cols.forEach { m ->
                Text(
                    Labels.metric(m.id, s?.value(m.id)),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(80.dp),
                )
            }
            val rate = s?.let { Calculations.engagementRate(it, platform) }
            Text(
                rate?.let {
                    Labels.computed(it)
                } ?: "-",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(96.dp),
            )
        }
    }
    Muted("Engagement rate: ${Calculations.ENGAGEMENT_RATE_DEFINITION}")
}

@Composable
fun AudiencePanel(
    report: AudienceReport,
    zone: kotlinx.datetime.TimeZone,
) {
    report.platforms.forEach { p ->
        Panel("${p.platform.displayName} audience") {
            if (p.breakdowns.isEmpty()) Muted("No audience data for this platform.")
            p.breakdowns.forEach { v ->
                Text(v.breakdown.label, style = MaterialTheme.typography.titleSmall)
                Muted("${v.breakdown.unit}; ${v.breakdown.window}; captured ${Labels.time(v.capturedAt, zone)}")
                if (v.missingReason != null) {
                    Muted(v.missingReason!!)
                } else {
                    val top = v.groups.take(12)
                    val max = top.maxOfOrNull { it.value ?: 0.0 } ?: 0.0
                    val percent = v.breakdown.unit.startsWith("percent")
                    top.forEach { g ->
                        val shown =
                            if (percent) {
                                Format.number(g.value, 1) + "%"
                            } else {
                                Format.number(g.value) + (
                                    g.shareOfListed?.let {
                                        " (${Format.number(it, 1)}% " +
                                            "of listed)"
                                    } ?: ""
                                )
                            }
                        BarRow(g.group, g.value, max, shown, platformColor(p.platform))
                    }
                    if (v.groups.size > top.size) Muted("${v.groups.size - top.size} more groups (export for all)")
                }
            }
            p.notAvailable.forEach { Muted("Not available: $it") }
        }
    }
}
