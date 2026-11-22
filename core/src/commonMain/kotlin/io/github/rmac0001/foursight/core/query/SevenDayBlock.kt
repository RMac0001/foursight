package io.github.rmac0001.foursight.core.query

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Availability
import io.github.rmac0001.foursight.core.metrics.Computed
import io.github.rmac0001.foursight.core.metrics.Day7Result
import io.github.rmac0001.foursight.core.metrics.Metrics

/**
 * The Playbook's 7-day log (R7.2), filled in from a [SevenDayReport] and ready to paste. The lines
 * FourSight can't know stay blank for Roger: where viewers stopped (TikTok Studio only), comments
 * with substance, and the lesson.
 */
object SevenDayBlock {
    /** The Playbook's names for each platform's post. */
    fun label(platform: SocialPlatform): String =
        when (platform) {
            SocialPlatform.TIKTOK -> "TikTok"
            SocialPlatform.YOUTUBE -> "Shorts"
            SocialPlatform.FACEBOOK -> "Reels"
            SocialPlatform.INSTAGRAM -> "Instagram"
        }

    fun render(report: SevenDayReport): String {
        val posts = report.posts.sortedBy { it.post.platform.ordinal }
        val locked = posts.mapNotNull { it.result as? Day7Result.Locked }
        val date = locked.maxOfOrNull { it.snapshot.captureDay }?.toString() ?: "not locked yet"
        val labels = posts.joinToString(" / ") { label(it.post.platform) }

        fun each(
            separator: String = "; ",
            cell: (PostSevenDay) -> String,
        ) = posts.joinToString(separator) { d -> stateOr(d) ?: cell(d) }

        val views = posts.joinToString(" / ") { d -> stateOr(d) ?: Format.number(d.values[Metrics.VIEWS.id]) }
        val watch =
            each { d ->
                val avg = valueOrNa(d, Metrics.AVERAGE_VIEW_DURATION.id) { Format.duration(it) }
                val pct = valueOrNa(d, Metrics.AVERAGE_VIEW_PERCENTAGE.id) { Format.number(it, 1) + "%" }
                "${label(d.post.platform)} $avg / $pct"
            }
        val perK =
            each { d ->
                val saves = d.savesPer1k?.let { rate(it) } ?: "n/a"
                "${label(d.post.platform)} $saves / ${d.sharesPer1k?.let { rate(it) } ?: "-"}"
            }
        val followers =
            each { d ->
                val name = label(d.post.platform)
                when {
                    d.followersPer1k != null -> {
                        "$name ${rate(d.followersPer1k)}"
                    }

                    else -> {
                        val change = d.accountFollowerChange
                        val account =
                            if (change?.value != null) {
                                "account ${Format.signed(
                                    change.value,
                                )} over the same days, not attributed to this video."
                            } else {
                                "no account change either: ${(change?.missingReason ?: "no capture.").replaceFirstChar {
                                    it
                                        .lowercase()
                                }}"
                            }
                        "$name not reported per video; $account"
                    }
                }
            }
        val ages = locked.joinToString(", ") { Format.number(it.ageDays, 1) }
        return buildString {
            appendLine("## 7-day numbers ($date)")
            appendLine("- Views ($labels): $views")
            appendLine("- Most viewers stopped at: ")
            appendLine("- Average watch time / % watched: $watch")
            appendLine("- Saves per 1,000 / shares per 1,000: $perK")
            appendLine("- Followers per 1,000: $followers")
            appendLine("- Comments with substance (count + best example): ")
            appendLine("- One lesson: ")
            append(
                "_FourSight, definitions v${report.definitionsVersion}" +
                    (if (ages.isNotEmpty()) "; captured at day $ages" else "") +
                    "; - is missing, n/a is not reported by that platform._",
            )
        }
    }

    /**
     * The note's frontmatter stats fields (Roger's decision, November 22, 2026: one field per
     * platform, nothing combined): `views_<platform>`, `shares_per_1k_<platform>`,
     * `followers_per_1k_<platform>` where the platform reports follows per video, and
     * `stats_day7_date`. Platforms use the Playbook's names (tiktok, shorts, reels, instagram). A
     * platform without a day-7 lock, or a missing number, gets null: missing, never 0.
     */
    fun fields(report: SevenDayReport): Map<String, String?> {
        val out = linkedMapOf<String, String?>()
        val posts = report.posts.sortedBy { it.post.platform.ordinal }
        posts.forEach { d ->
            val name = label(d.post.platform).lowercase()
            val locked = d.result is Day7Result.Locked
            out["views_$name"] =
                if (locked) d.values[Metrics.VIEWS.id]?.let { Format.number(it).replace(",", "") } else null
            out["shares_per_1k_$name"] =
                if (locked) d.sharesPer1k?.value?.let { Format.number(it, 1).replace(",", "") } else null
            if (d.followersPer1kUnavailable == null) {
                out["followers_per_1k_$name"] =
                    if (locked) d.followersPer1k?.value?.let { Format.number(it, 1).replace(",", "") } else null
            }
        }
        out["stats_day7_date"] =
            posts.mapNotNull { (it.result as? Day7Result.Locked)?.snapshot?.captureDay }.maxOrNull()?.toString()
        return out
    }

    private fun stateOr(d: PostSevenDay): String? =
        when (d.result) {
            is Day7Result.Locked -> null
            Day7Result.NotYetDue -> "${label(d.post.platform)} not due"
            Day7Result.Pending -> "${label(d.post.platform)} pending"
            is Day7Result.Missed -> "${label(d.post.platform)} missed"
        }

    private fun valueOrNa(
        d: PostSevenDay,
        metricId: String,
        show: (Double) -> String,
    ): String {
        if (Metrics.get(metricId).availability(d.post.platform) !is Availability.Available) return "n/a"
        return d.values[metricId]?.let(show) ?: "-"
    }

    private fun rate(c: Computed): String = Format.number(c.value, 1)
}
