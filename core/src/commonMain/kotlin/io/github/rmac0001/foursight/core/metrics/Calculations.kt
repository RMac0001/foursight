package io.github.rmac0001.foursight.core.metrics

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.model.Snapshot
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * A computed number together with the inputs it came from and its definition, so every value
 * FourSight shows can explain itself (spec: Comparisons and indicators). [value] is null when an
 * input is missing; [missingReason] then says why. Missing is never shown as zero.
 */
data class Computed(
    val id: String,
    val value: Double?,
    val definition: String,
    val inputs: Map<String, Double?>,
    val missingReason: String? = null,
)

object Calculations {
    const val PER_1K_DEFINITION = "count ÷ views × 1,000, using the post's own views from the same snapshot."
    const val VIEWS_PER_DAY_DEFINITION = "views ÷ days since publishing, using the snapshot's exact capture time."
    const val ENGAGEMENT_RATE_DEFINITION =
        "(likes + comments + shares + saves) ÷ views × 100. Saves count only on platforms that report " +
            "them, so the rate is comparable within one platform, not across platforms."
    const val CHANGE_DEFINITION = "later value − earlier value, between two captures of the same item."

    /** [count] per 1,000 [views]. Missing or zero views give no value rather than infinity. */
    fun per1k(
        id: String,
        count: Double?,
        views: Double?,
    ): Computed {
        val inputs = mapOf("count" to count, "views" to views)
        return when {
            count == null -> Computed(id, null, PER_1K_DEFINITION, inputs, "The count is missing.")
            views == null -> Computed(id, null, PER_1K_DEFINITION, inputs, "Views are missing.")
            views <= 0.0 -> Computed(id, null, PER_1K_DEFINITION, inputs, "No views yet.")
            else -> Computed(id, count / views * 1000.0, PER_1K_DEFINITION, inputs)
        }
    }

    fun viewsPerDay(
        views: Double?,
        publishedAt: Instant,
        capturedAt: Instant,
    ): Computed {
        val days = (capturedAt - publishedAt) / 1.days
        val inputs = mapOf("views" to views, "days_since_publishing" to days)
        return when {
            views == null -> {
                Computed("views_per_day", null, VIEWS_PER_DAY_DEFINITION, inputs, "Views are missing.")
            }

            days <= 0.0 -> {
                Computed("views_per_day", null, VIEWS_PER_DAY_DEFINITION, inputs, "Captured before publishing.")
            }

            else -> {
                Computed("views_per_day", views / days, VIEWS_PER_DAY_DEFINITION, inputs)
            }
        }
    }

    /**
     * Engagement rate in percent. Likes, comments, shares and views are required; saves are added
     * only when the platform reports them (so a missing save count isn't counted as zero).
     */
    fun engagementRate(
        snapshot: Snapshot,
        platform: SocialPlatform,
    ): Computed {
        val views = snapshot.value(Metrics.VIEWS.id)
        val likes = snapshot.value(Metrics.LIKES.id)
        val comments = snapshot.value(Metrics.COMMENTS.id)
        val shares = snapshot.value(Metrics.SHARES.id)
        val savesSupported = Metrics.SAVES.availability(platform) is Availability.Available
        val saves = if (savesSupported) snapshot.value(Metrics.SAVES.id) else null
        val inputs =
            buildMap {
                put("views", views)
                put("likes", likes)
                put("comments", comments)
                put("shares", shares)
                if (savesSupported) put("saves", saves)
            }
        val missing = inputs.filterValues { it == null }.keys
        if (missing.isNotEmpty()) {
            return Computed(
                "engagement_rate",
                null,
                ENGAGEMENT_RATE_DEFINITION,
                inputs,
                "Missing: ${missing.joinToString()}.",
            )
        }
        if (views!! <= 0.0) {
            return Computed("engagement_rate", null, ENGAGEMENT_RATE_DEFINITION, inputs, "No views yet.")
        }
        val interactions = likes!! + comments!! + shares!! + (saves ?: 0.0)
        return Computed("engagement_rate", interactions / views * 100.0, ENGAGEMENT_RATE_DEFINITION, inputs)
    }

    fun change(
        id: String,
        earlier: Double?,
        later: Double?,
    ): Computed {
        val inputs = mapOf("earlier" to earlier, "later" to later)
        return if (earlier == null || later == null) {
            Computed(id, null, CHANGE_DEFINITION, inputs, "A capture is missing.")
        } else {
            Computed(id, later - earlier, CHANGE_DEFINITION, inputs)
        }
    }

    /**
     * Sums one metric across platforms. Only counts can be summed (spec: Combining rules); asking to
     * sum a rate or duration is a programming error. If any platform's value is missing, the total
     * is missing too, and the reason names the platforms.
     */
    fun sumAcrossPlatforms(
        metric: MetricDefinition,
        perPlatform: Map<SocialPlatform, Double?>,
    ): Computed {
        require(metric.kind.summable) { "${metric.id} is a ${metric.kind.name.lowercase()} and can't be summed." }
        val inputs = perPlatform.mapKeys { it.key.name.lowercase() }
        val missing = perPlatform.filterValues { it == null }.keys
        return if (missing.isNotEmpty()) {
            Computed(
                "${metric.id}_total",
                null,
                "Sum of ${metric.label.lowercase()} across the selected platforms.",
                inputs,
                "Missing for ${missing.joinToString { it.displayName }}.",
            )
        } else {
            Computed(
                "${metric.id}_total",
                perPlatform.values.sumOf { it!! },
                "Sum of ${metric.label.lowercase()} across the selected platforms.",
                inputs,
            )
        }
    }
}

/**
 * The day-7 lock (spec: Day-7 lock; decision November 21, 2026): the first snapshot captured at
 * least 7 × 24 hours after publishing, and no later than [Day7.LOCK_WINDOW] after that. The exact
 * age is kept, so a capture at day 7.4 is never presented as exactly day 7.
 */
sealed interface Day7Result {
    data class Locked(
        val snapshot: Snapshot,
        val age: Duration,
    ) : Day7Result {
        val ageDays: Double get() = age / 1.days
    }

    /** The post is younger than 7 days. */
    data object NotYetDue : Day7Result

    /** The post is past day 7 but no capture is in the window yet; it may still arrive. */
    data object Pending : Day7Result

    /** No capture landed in the window (a gap). Shown as missed, never filled in. */
    data class Missed(
        val firstCaptureAfterAge: Duration?,
    ) : Day7Result
}

object Day7 {
    val LOCK_AGE: Duration = 7.days

    /** How late a capture may be and still count as the day-7 number. */
    val LOCK_WINDOW: Duration = 1.days

    fun lock(
        publishedAt: Instant,
        snapshots: List<Snapshot>,
        now: Instant,
    ): Day7Result {
        val age = now - publishedAt
        if (age < LOCK_AGE) return Day7Result.NotYetDue
        val first =
            snapshots
                .filter { it.capturedAt - publishedAt >= LOCK_AGE }
                .minByOrNull { it.capturedAt }
        val firstAge = first?.let { it.capturedAt - publishedAt }
        return when {
            first != null && firstAge!! <= LOCK_AGE + LOCK_WINDOW -> Day7Result.Locked(first, firstAge)
            age <= LOCK_AGE + LOCK_WINDOW -> Day7Result.Pending
            else -> Day7Result.Missed(firstAge)
        }
    }
}
