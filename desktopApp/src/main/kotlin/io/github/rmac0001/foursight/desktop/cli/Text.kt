package io.github.rmac0001.foursight.desktop.cli

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Availability
import io.github.rmac0001.foursight.core.metrics.Computed
import io.github.rmac0001.foursight.core.metrics.MetricDefinition
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.query.AccountSummary
import io.github.rmac0001.foursight.core.query.AudienceReport
import io.github.rmac0001.foursight.core.query.Format
import io.github.rmac0001.foursight.core.query.LibraryEntry
import io.github.rmac0001.foursight.core.query.MatchSuggestion
import io.github.rmac0001.foursight.core.query.PlatformFreshness
import io.github.rmac0001.foursight.core.query.PlatformLimits
import io.github.rmac0001.foursight.core.query.PostStats
import io.github.rmac0001.foursight.core.query.QueryJson
import io.github.rmac0001.foursight.core.query.RankedPost
import io.github.rmac0001.foursight.core.query.StatsBasis
import io.github.rmac0001.foursight.core.query.Trends
import io.github.rmac0001.foursight.core.query.VideoComparison
import io.github.rmac0001.foursight.core.query.VideoStats
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Readable output for the CLI. Every number comes from core; this only lays it out. Missing values
 * print as "-" and platforms that don't report a metric as "n/a", never as 0.
 */
class Text(
    private val zone: TimeZone,
) {
    fun time(at: Instant?): String {
        if (at == null) return "never"
        val t = at.toLocalDateTime(zone)
        return "${t.date} ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    }

    private fun title(p: PlatformPost): String {
        val t =
            p.title
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()
        return if (t.length > 60) t.take(57) + "..." else t.ifEmpty { "(no title)" }
    }

    fun videos(
        entries: List<LibraryEntry>,
        latestViews: (PlatformPost) -> Pair<Double?, Instant?>,
    ): String =
        buildString {
            if (entries.isEmpty()) {
                appendLine("No videos yet. Run: foursight collect")
                return@buildString
            }
            appendLine("${entries.size} videos, newest first. Views are the latest capture.")
            entries.forEach { e ->
                val first = e.video.posts.minBy { it.publishedAt }
                val match = if (e.confirmed) "matched" else "unmatched"
                appendLine()
                appendLine("${time(e.video.firstPublishedAt)}  ${title(first)}  [$match]")
                e.video.posts.sortedBy { it.platform.ordinal }.forEach { p ->
                    val (views, at) = latestViews(p)
                    appendLine("  ${p.ref}  ${Format.number(views)} views  (captured ${time(at)})")
                }
            }
        }

    private fun metricLine(
        s: PostStats,
        m: MetricDefinition,
    ): String? {
        val v =
            when {
                m.id in s.unavailable -> return null
                m.id !in s.values -> return null
                else -> s.values[m.id]
            }
        val shown =
            when (m.unit) {
                "seconds" -> Format.duration(v)
                "percent" -> if (v == null) "-" else Format.number(v, 1) + "%"
                else -> Format.number(v)
            }
        return "    ${m.label}: $shown"
    }

    private fun computedLine(c: Computed): String {
        val label =
            when (c.id) {
                "views_per_day" -> "Views per day"
                "engagement_rate" -> "Engagement rate"
                "shares_per_1k" -> "Shares per 1,000"
                "saves_per_1k" -> "Saves per 1,000"
                "followers_per_1k" -> "Followers per 1,000"
                else -> c.id
            }
        val value =
            if (c.id == "engagement_rate" && c.value != null) {
                Format.number(c.value, 2) + "%"
            } else {
                Format.number(c.value, 1)
            }
        return "    $label: $value" + (c.missingReason?.let { "  ($it)" } ?: "")
    }

    fun stats(v: VideoStats): String =
        buildString {
            val basis = if (v.basis == StatsBasis.DAY_7) "day-7 numbers" else "latest numbers"
            appendLine(
                "${title(
                    v.entry.video.posts
                        .minBy { it.publishedAt },
                )} ($basis)",
            )
            v.posts.forEach { s -> append(postStats(s)) }
            if (v.totals.isNotEmpty()) {
                appendLine("  All platforms (counts only; rates and durations are never combined):")
                v.totals.forEach { c ->
                    val label = Metrics.get(c.id.removeSuffix("_total")).label
                    appendLine("    $label: ${Format.number(c.value)}" + (c.missingReason?.let { "  ($it)" } ?: ""))
                }
            }
            append(footer(v.generatedAt))
        }

    private fun postStats(s: PostStats): String =
        buildString {
            appendLine()
            appendLine("  ${s.post.platform.displayName}  ${s.post.ref}")
            if (s.capturedAt == null) {
                appendLine("    ${s.noCaptureReason}")
                return@buildString
            }
            appendLine("    Captured ${time(s.capturedAt)}, day ${Format.number(s.ageDays, 1)} after publishing")
            Metrics.all.forEach { m -> metricLine(s, m)?.let(::appendLine) }
            s.computed.forEach { appendLine(computedLine(it)) }
            s.accountFollowerChange?.let { c ->
                appendLine(
                    "    Account follower change (not per video): ${Format.signed(c.value)}" +
                        (c.missingReason?.let { "  ($it)" } ?: ""),
                )
            }
            if (s.unavailable.isNotEmpty()) {
                appendLine("    Not reported by ${s.post.platform.displayName}: ${s.unavailable.keys.joinToString()}")
            }
        }

    private fun footer(at: Instant): String =
        "\nGenerated ${time(at)}, definitions v${Metrics.DEFINITIONS_VERSION}. " +
            "'-' is missing; see: foursight definitions\n"

    fun summary(s: AccountSummary): String =
        buildString {
            appendLine("Summary, ${s.period.label}" + (s.start?.let { " (since ${time(it)})" } ?: ""))
            if (s.dataStartsAt != null && s.start != null && s.dataStartsAt!! > s.start!!) {
                appendLine("Partial: collection started ${time(s.dataStartsAt)}, after the period start.")
            }
            appendLine(
                "Followers on all platforms: ${Format.number(s.followersTotal.value)}" +
                    (s.followersTotal.missingReason?.let { "  ($it)" } ?: ""),
            )
            s.platforms.forEach { p ->
                appendLine()
                appendLine("${p.platform.displayName}  (latest capture ${time(p.latestCapture)})")
                appendLine(
                    "  Followers: ${Format.number(p.followers)}, change ${Format.signed(p.followerChange.value)}" +
                        (p.followerChange.missingReason?.let { "  ($it)" } ?: ""),
                )
                val gained =
                    p.gained.joinToString(", ") {
                        "${Metrics.get(it.id.removeSuffix("_gained")).label.lowercase()} ${Format.signed(it.value)}"
                    }
                appendLine("  Gained in period: $gained")
                if (p.postsWithoutBaseline > 0) {
                    appendLine(
                        "  (${p.postsWithoutBaseline} older posts left out: no capture from before the period start)",
                    )
                }
                appendLine("  Videos published: ${p.postsPublished}")
                val r = p.ranking
                val videos = if (r.ranked == 1) "video" else "videos"
                appendLine("  Ranked by ${r.measure.label}, ${r.ranked} $videos with day-7 numbers:")
                r.measure.note?.let { appendLine("    Note: $it") }
                if (r.best.isNotEmpty()) appendLine("    Best: " + r.best.joinToString("; ") { ranked(it) })
                if (r.weakest.isNotEmpty()) appendLine("    Weakest: " + r.weakest.joinToString("; ") { ranked(it) })
                if (r.notRanked.isNotEmpty()) {
                    appendLine("    Not ranked: " + r.notRanked.entries.joinToString { "${it.value} ${it.key}" })
                }
            }
            append(footer(s.generatedAt))
        }

    private fun ranked(r: RankedPost): String = "${title(r.post)} ${Format.number(r.value.value, 1)} (${r.post.ref})"

    fun trends(t: Trends): String =
        buildString {
            appendLine("Trends ${t.from} to ${t.to} (each day's latest capture; change is from the day before)")
            t.series.forEach { s ->
                appendLine()
                appendLine("${s.platform.displayName}, ${s.metric.label.lowercase()}")
                if (s.unavailableReason != null) {
                    appendLine("  n/a: ${s.unavailableReason}")
                    return@forEach
                }
                s.points.forEach { p ->
                    val line =
                        "  ${p.day}  ${Format.number(p.value).padStart(10)}  ${Format.signed(p.change).padStart(8)}"
                    appendLine(line + (p.missingReason?.let { "  ($it)" } ?: ""))
                }
            }
            append(footer(t.generatedAt))
        }

    fun compare(c: VideoComparison): String =
        buildString {
            val wanted = c.metrics.toSet()

            fun row(
                label: String,
                id: String,
                a: PostStats?,
                b: PostStats?,
                show: (Double?) -> String,
            ) {
                if (wanted.isNotEmpty() && id !in wanted) return

                fun cell(s: PostStats?): String =
                    when {
                        s == null -> "not posted"
                        s.capturedAt == null -> "-"
                        id in s.unavailable -> "n/a"
                        else -> show(s.values[id] ?: s.computed.firstOrNull { it.id == id }?.value)
                    }
                appendLine("    ${label.padEnd(26)} ${cell(a).padStart(12)} ${cell(b).padStart(12)}")
            }
            val aTitle =
                title(
                    c.a.entry.video.posts
                        .minBy { it.publishedAt },
                )
            val bTitle =
                title(
                    c.b.entry.video.posts
                        .minBy { it.publishedAt },
                )
            appendLine("A: $aTitle")
            appendLine("B: $bTitle")
            for ((heading, a, b) in listOf(Triple("Day 7", c.a, c.b), Triple("Latest", c.aLatest, c.bLatest))) {
                appendLine()
                appendLine("$heading (A / B)")
                val platforms =
                    (a.posts.map { it.post.platform } + b.posts.map { it.post.platform })
                        .distinct()
                        .sortedBy { it.ordinal }
                platforms.forEach { p ->
                    val sa = a.posts.firstOrNull { it.post.platform == p }
                    val sb = b.posts.firstOrNull { it.post.platform == p }
                    appendLine("  ${p.displayName}  (A: ${age(sa)}, B: ${age(sb)})")
                    Metrics.all.filter { it.itemKind == ItemKind.POST }.forEach { m ->
                        row(m.label, m.id, sa, sb) { v ->
                            when (m.unit) {
                                "seconds" -> Format.duration(v)
                                "percent" -> if (v == null) "-" else Format.number(v, 1) + "%"
                                else -> Format.number(v)
                            }
                        }
                    }
                    listOf(
                        "views_per_day" to "Views per day",
                        "engagement_rate" to "Engagement rate %",
                        "shares_per_1k" to "Shares per 1,000",
                        "saves_per_1k" to "Saves per 1,000",
                        "followers_per_1k" to "Followers per 1,000",
                    ).forEach { (id, label) ->
                        if ((sa?.computed.orEmpty() + sb?.computed.orEmpty()).any { it.id == id }) {
                            row(label, id, sa, sb) { Format.number(it, if (id == "engagement_rate") 2 else 1) }
                        }
                    }
                }
            }
            appendLine()
            appendLine("Day 7 compares both at the same age. These are differences, not causes.")
            append(footer(c.generatedAt))
        }

    private fun age(s: PostStats?): String =
        when {
            s == null -> "not posted"
            s.capturedAt == null -> s.noCaptureReason ?: "no capture"
            else -> "day ${Format.number(s.ageDays, 1)}"
        }

    fun status(list: List<PlatformFreshness>): String =
        buildString {
            list.forEach { f ->
                appendLine(f.platform.displayName)
                appendLine("  Latest capture: ${time(f.latestCapture)}")
                appendLine(
                    "  Last success: ${time(f.status?.lastSuccessAt)}, last attempt: ${time(f.status?.lastAttemptAt)}",
                )
                f.status?.lastError?.let { appendLine("  Last error: $it") }
                f.status?.tokenExpiresAt?.let { appendLine("  Sign-in expires: ${time(it)}") }
                PlatformLimits.of(f.platform).forEach { appendLine("  Limit: $it") }
                appendLine()
            }
        }

    fun definitions(list: List<MetricDefinition>): String =
        buildString {
            appendLine("Metric definitions (version ${Metrics.DEFINITIONS_VERSION})")
            list.forEach { m ->
                appendLine()
                val combine =
                    if (m.kind.summable) "can be summed across platforms" else "never combined across platforms"
                appendLine("${m.label} [${m.id}], ${m.unit}, $combine")
                appendLine("  ${m.definition}")
                SocialPlatform.entries.forEach { p ->
                    when (val a = m.availability(p)) {
                        is Availability.Available -> {
                            appendLine("  ${p.displayName}: ${a.source}")
                        }

                        is Availability.Unavailable -> {
                            appendLine("  ${p.displayName}: n/a, ${a.reason}")
                        }
                    }
                }
            }
            appendLine()
            appendLine("Computed numbers")
            QueryJson.computedDefinitions.forEach { (id, text) -> appendLine("  $id: $text") }
        }

    fun audience(
        r: AudienceReport,
        top: Int = 10,
    ): String =
        buildString {
            r.platforms.forEach { p ->
                appendLine(p.platform.displayName)
                p.breakdowns.forEach { v ->
                    appendLine("  ${v.breakdown.label} (${v.breakdown.unit}; ${v.breakdown.window})")
                    if (v.missingReason != null) {
                        appendLine("    ${v.missingReason}")
                        return@forEach
                    }
                    appendLine("    Captured ${time(v.capturedAt)}")
                    v.groups.take(top).forEach { g ->
                        val value = Format.number(g.value, if (v.breakdown.unit.startsWith("percent")) 1 else 0)
                        val share = g.shareOfListed?.let { "  (${Format.number(it, 1)}% of listed)" } ?: ""
                        appendLine("    ${g.group.padEnd(28)} ${value.padStart(8)}$share")
                    }
                    if (v.groups.size > top) appendLine("    ... ${v.groups.size - top} more (use --json for all)")
                }
                p.notAvailable.forEach { appendLine("  n/a: $it") }
                appendLine()
            }
            append(footer(r.generatedAt))
        }

    fun suggestions(
        list: List<MatchSuggestion>,
        groups: List<LibraryEntry>,
    ): String =
        buildString {
            if (groups.isNotEmpty()) {
                appendLine("Matched videos:")
                groups.forEach { g ->
                    val refs =
                        g.video.posts
                            .sortedBy { it.platform.ordinal }
                            .joinToString { it.ref.toString() }
                    appendLine("  ${g.video.videoId}: $refs")
                }
                appendLine()
            }
            if (list.isEmpty()) {
                appendLine("No match suggestions: every post is matched, or nothing published close together.")
                return@buildString
            }
            appendLine("Suggested matches (nothing is linked until you confirm):")
            list.forEachIndexed { i, s ->
                appendLine()
                appendLine("${i + 1}. ${s.confidence.name.lowercase()} confidence. ${s.reason}")
                s.posts.forEach { p -> appendLine("   ${p.ref}  ${time(p.publishedAt)}  ${title(p)}") }
                appendLine("   To confirm: foursight match confirm ${s.posts.joinToString(" ") { it.ref.toString() }}")
            }
        }
}
