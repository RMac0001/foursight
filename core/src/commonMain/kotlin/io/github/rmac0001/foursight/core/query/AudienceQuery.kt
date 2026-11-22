package io.github.rmac0001.foursight.core.query

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.AudienceBreakdown
import io.github.rmac0001.foursight.core.model.ItemKind
import kotlin.time.Instant

data class AudienceGroup(
    val group: String,
    val value: Double?,
    /** For counts: this group's share of the groups listed, in percent. Null for percentages. */
    val shareOfListed: Double?,
)

data class AudienceView(
    val breakdown: AudienceBreakdown,
    val capturedAt: Instant?,
    /** Largest first. Empty when there's no capture yet or the platform returned no groups. */
    val groups: List<AudienceGroup>,
    val missingReason: String?,
)

data class PlatformAudience(
    val platform: SocialPlatform,
    val breakdowns: List<AudienceView>,
    val notAvailable: List<String>,
)

data class AudienceReport(
    val platforms: List<PlatformAudience>,
    val generatedAt: Instant,
)

const val SHARE_OF_LISTED_DEFINITION =
    "group ÷ the sum of the groups listed × 100. Platforms list only their top groups (Instagram 45, YouTube " +
        "countries 25) and leave out people they can't place, so this is a share of the listed groups, not of " +
        "every follower or viewer."

/** The latest capture of each audience breakdown (spec: Audience, `get_audience`). */
fun QueryService.audience(
    now: Instant,
    platforms: Set<SocialPlatform> = SocialPlatform.entries.toSet(),
): AudienceReport {
    val list =
        SocialPlatform.entries.filter { it in platforms }.map { platform ->
            val latest =
                repo
                    .snapshotsOfKind(platform, ItemKind.AUDIENCE)
                    .groupBy { it.itemId }
                    .mapValues { (_, s) -> s.maxBy { it.capturedAt } }
            val views =
                AudienceBreakdown.of(platform).map { b ->
                    val s = latest[b.id]
                    when {
                        s == null -> {
                            AudienceView(b, null, emptyList(), "Not collected yet.")
                        }

                        s.values.isEmpty() -> {
                            AudienceView(
                                b,
                                s.capturedAt,
                                emptyList(),
                                "The platform returned no groups (too little data).",
                            )
                        }

                        else -> {
                            val isCount = b.unit != "percent of views"
                            val total =
                                s.values.values
                                    .filterNotNull()
                                    .sum()
                            val groups =
                                s.values
                                    .map { (g, v) ->
                                        AudienceGroup(
                                            g,
                                            v,
                                            if (isCount && v != null && total > 0) v / total * 100.0 else null,
                                        )
                                    }.sortedByDescending { it.value ?: -1.0 }
                            AudienceView(b, s.capturedAt, groups, null)
                        }
                    }
                }
            PlatformAudience(platform, views, AudienceBreakdown.gaps(platform))
        }
    return AudienceReport(list, now)
}
