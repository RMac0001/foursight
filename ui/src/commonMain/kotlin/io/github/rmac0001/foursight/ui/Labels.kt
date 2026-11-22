package io.github.rmac0001.foursight.ui

import io.github.rmac0001.foursight.core.metrics.Computed
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.query.Format
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Display helpers: formatting only. Every number they show is computed in core. */
object Labels {
    fun time(
        at: Instant?,
        zone: TimeZone,
    ): String {
        if (at == null) return "never"
        val t = at.toLocalDateTime(zone)
        return "${t.date} ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    }

    fun date(
        at: Instant?,
        zone: TimeZone,
    ): String = at?.toLocalDateTime(zone)?.date?.toString() ?: "-"

    fun computedLabel(id: String): String =
        when (id.removeSuffix("_total").removeSuffix("_gained").removeSuffix("_average")) {
            "views_per_day" -> {
                "Views per day"
            }

            "engagement_rate" -> {
                "Engagement rate"
            }

            "shares_per_1k" -> {
                "Shares per 1,000"
            }

            "saves_per_1k" -> {
                "Saves per 1,000"
            }

            "followers_per_1k" -> {
                "Followers per 1,000"
            }

            else -> {
                runCatching {
                    Metrics
                        .get(
                            id.removeSuffix("_total").removeSuffix("_gained"),
                        ).label
                }.getOrDefault(id)
            }
        }

    /** A stored metric's value in its unit; "-" when missing (never 0). */
    fun metric(
        id: String,
        value: Double?,
    ): String {
        val unit = runCatching { Metrics.get(id).unit }.getOrNull()
        return when {
            value == null -> "-"
            unit == "seconds" -> Format.duration(value)
            unit == "percent" -> Format.number(value, 1) + "%"
            else -> Format.number(value)
        }
    }

    fun computed(c: Computed): String =
        when {
            c.value == null -> "-"
            c.id == "engagement_rate" -> Format.number(c.value, 2) + "%"
            c.id.startsWith("views_per_day") -> Format.number(c.value, 1)
            c.id.contains("per_1k") -> Format.number(c.value, 1)
            else -> Format.number(c.value)
        }

    fun title(text: String?): String {
        val t = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        return if (t.length > 70) t.take(67) + "..." else t.ifEmpty { "(no title)" }
    }
}
