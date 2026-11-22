package io.github.rmac0001.foursight.core.query

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Availability
import io.github.rmac0001.foursight.core.metrics.Calculations
import io.github.rmac0001.foursight.core.metrics.Computed
import io.github.rmac0001.foursight.core.metrics.Day7Result
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.model.Video
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** A reporting period ending now: the last N days, or all history. */
data class Period(
    val days: Int?,
) {
    val label: String get() = if (days == null) "all history" else "last $days days"

    fun start(now: Instant): Instant? = days?.let { now - it.days }

    companion object {
        val ALL = Period(null)

        /** Parses "28d", "28", "7d" or "all". */
        fun parse(text: String): Period? {
            val t = text.trim().lowercase()
            if (t == "all") return ALL
            val n = t.removeSuffix("d").toIntOrNull() ?: return null
            return if (n in 1..3650) Period(n) else null
        }
    }
}

/**
 * What one platform's videos are ranked by. The Playbook's scoreboard is followers per 1,000, but
 * only YouTube and Facebook report follows per video; TikTok and Instagram rank by shares per 1,000
 * instead (Roger's decision, November 22, 2026), and the label says so.
 */
data class RankingMeasure(
    val metricId: String,
    val label: String,
    val note: String?,
) {
    companion object {
        fun forPlatform(platform: SocialPlatform): RankingMeasure =
            if (Metrics.FOLLOWERS_GAINED.availability(platform) is Availability.Available) {
                RankingMeasure("followers_per_1k", "Followers per 1,000 views (day 7)", null)
            } else {
                RankingMeasure(
                    "shares_per_1k",
                    "Shares per 1,000 views (day 7)",
                    "${platform.displayName} doesn't report followers per video, so this ranks by shares per 1,000 " +
                        "instead. It's a different measure from the Playbook's followers per 1,000.",
                )
            }
    }
}

data class RankedPost(
    val post: PlatformPost,
    val value: Computed,
    val day7CapturedAt: Instant,
    val ageDays: Double,
)

data class Ranking(
    val measure: RankingMeasure,
    /** Highest first. */
    val best: List<RankedPost>,
    /** Lowest first; never repeats a post in [best]. */
    val weakest: List<RankedPost>,
    /** How many posts had a day-7 value to rank (the sample size). */
    val ranked: Int,
    /** Posts published in the period that couldn't be ranked yet, with why. */
    val notRanked: Map<String, Int>,
)

data class PlatformSummary(
    val platform: SocialPlatform,
    val followers: Double?,
    val followersCapturedAt: Instant?,
    /** Account followers at the end minus at the start of the period. */
    val followerChange: Computed,
    /** Counts gained during the period, summed over the posts that have a baseline. */
    val gained: List<Computed>,
    /** Posts that existed at the start but have no capture from before it, so they're left out of [gained]. */
    val postsWithoutBaseline: Int,
    val postsPublished: Int,
    val ranking: Ranking,
    val latestCapture: Instant?,
)

data class AccountSummary(
    val period: Period,
    val start: Instant?,
    val end: Instant,
    val platforms: List<PlatformSummary>,
    /** Followers summed across the platforms (a count, so it may be summed). */
    val followersTotal: Computed,
    /** The first capture in the database; a period starting earlier is only partly covered. */
    val dataStartsAt: Instant?,
    val generatedAt: Instant,
    val definitionsVersion: Int,
)

const val GAINED_DEFINITION =
    "Sum over the platform's posts of (latest capture − last capture at or before the period start). A post " +
        "published during the period starts from 0, because it had no views before it existed. A post with no " +
        "capture from before the start is left out and counted separately; nothing is estimated."

/** Followers, growth, and best and weakest videos for a period (spec: `get_account_summary`). */
fun QueryService.accountSummary(
    period: Period,
    now: Instant,
    platforms: Set<SocialPlatform> = SocialPlatform.entries.toSet(),
): AccountSummary {
    val start = period.start(now)
    val summaries = SocialPlatform.entries.filter { it in platforms }.map { platformSummary(it, start, now) }
    val firstCapture =
        SocialPlatform.entries
            .flatMap { repo.accountSnapshots(it) }
            .minOfOrNull { it.capturedAt }
    return AccountSummary(
        period = period,
        start = start,
        end = now,
        platforms = summaries,
        followersTotal =
            Calculations.sumAcrossPlatforms(Metrics.FOLLOWERS, summaries.associate { it.platform to it.followers }),
        dataStartsAt = firstCapture,
        generatedAt = now,
        definitionsVersion = Metrics.DEFINITIONS_VERSION,
    )
}

private fun QueryService.platformSummary(
    platform: SocialPlatform,
    start: Instant?,
    now: Instant,
): PlatformSummary {
    val account = repo.accountSnapshots(platform).filter { it.capturedAt <= now }
    val latestAccount = account.lastOrNull()
    val baseAccount = if (start == null) account.firstOrNull() else account.lastOrNull { it.capturedAt <= start }
    val followerChange =
        Calculations.change(
            "follower_change",
            baseAccount?.value(Metrics.FOLLOWERS.id),
            latestAccount?.value(Metrics.FOLLOWERS.id),
        )
    val followerChangeLabeled =
        when {
            latestAccount == null -> {
                followerChange.copy(missingReason = "No account capture yet.")
            }

            baseAccount == null -> {
                followerChange.copy(missingReason = "No account capture from before the period start.")
            }

            else -> {
                followerChange.copy(
                    definition =
                        "Account followers at ${latestAccount.capturedAt} minus at ${baseAccount.capturedAt}.",
                )
            }
        }

    val posts = repo.posts(platform).filter { it.publishedAt <= now }
    var withoutBaseline = 0
    val gainedPerMetric = summedCounts.associateWith { mutableListOf<Double>() }
    val missingPerMetric = summedCounts.associateWith { 0 }.toMutableMap()
    posts.forEach { post ->
        val snaps = repo.snapshots(platform, ItemKind.POST, post.postId).filter { it.capturedAt <= now }
        val latest = snaps.lastOrNull() ?: return@forEach
        val publishedInPeriod = start == null || post.publishedAt >= start
        val base = if (publishedInPeriod) null else snaps.lastOrNull { it.capturedAt <= start }
        if (!publishedInPeriod && base == null) {
            withoutBaseline++
            return@forEach
        }
        summedCounts.forEach { m ->
            if (m.availability(platform) !is Availability.Available) return@forEach
            val end = latest.value(m.id)
            val from = if (publishedInPeriod) 0.0 else base!!.value(m.id)
            if (end == null || from == null) {
                missingPerMetric[m] = missingPerMetric.getValue(m) + 1
            } else {
                gainedPerMetric.getValue(m).add(end - from)
            }
        }
    }
    val gained =
        summedCounts
            .filter { it.availability(platform) is Availability.Available }
            .map { m ->
                val missing = missingPerMetric.getValue(m)
                val values = gainedPerMetric.getValue(m)
                Computed(
                    id = "${m.id}_gained",
                    value = if (missing > 0 || values.isEmpty()) null else values.sum(),
                    definition = GAINED_DEFINITION,
                    inputs =
                        mapOf(
                            "posts_counted" to values.size.toDouble(),
                            "posts_missing_value" to missing.toDouble(),
                        ),
                    missingReason =
                        when {
                            missing > 0 -> "$missing post(s) are missing ${m.label.lowercase()} in a capture."
                            values.isEmpty() -> "No posts with captures to count."
                            else -> null
                        },
                )
            }

    val inPeriod = posts.filter { start == null || it.publishedAt >= start }
    return PlatformSummary(
        platform = platform,
        followers = latestAccount?.value(Metrics.FOLLOWERS.id),
        followersCapturedAt = latestAccount?.capturedAt,
        followerChange = followerChangeLabeled,
        gained = gained,
        postsWithoutBaseline = withoutBaseline,
        postsPublished = inPeriod.size,
        ranking = ranking(platform, inPeriod, now),
        latestCapture = repo.latestCapture(platform),
    )
}

/** Ranks posts by their day-7 value: the best two and the weakest two (The Playbook, Sunday review). */
internal fun QueryService.ranking(
    platform: SocialPlatform,
    posts: List<PlatformPost>,
    now: Instant,
    count: Int = 2,
): Ranking {
    val measure = RankingMeasure.forPlatform(platform)
    val notRanked = mutableMapOf<String, Int>()
    val ranked =
        posts
            .mapNotNull { post ->
                val entry = LibraryEntry(Video("post:${post.ref}", listOf(post)), false)
                val d = sevenDayReport(entry, now).posts.single()
                val locked = d.result as? Day7Result.Locked
                if (locked == null) {
                    val reason =
                        when (d.result) {
                            Day7Result.NotYetDue -> "not 7 days old yet"
                            Day7Result.Pending -> "waiting for the day-7 capture"
                            else -> "no day-7 capture (missed)"
                        }
                    notRanked[reason] = (notRanked[reason] ?: 0) + 1
                    return@mapNotNull null
                }
                val value = if (measure.metricId == "followers_per_1k") d.followersPer1k else d.sharesPer1k
                if (value?.value == null) {
                    notRanked["day-7 value missing"] = (notRanked["day-7 value missing"] ?: 0) + 1
                    return@mapNotNull null
                }
                RankedPost(post, value, locked.snapshot.capturedAt, locked.ageDays)
            }.sortedByDescending { it.value.value }
    val best = ranked.take(count)
    val weakest = ranked.reversed().filter { it !in best }.take(count)
    return Ranking(measure, best, weakest, ranked.size, notRanked)
}
