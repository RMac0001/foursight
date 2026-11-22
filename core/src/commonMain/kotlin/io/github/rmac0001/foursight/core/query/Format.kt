package io.github.rmac0001.foursight.core.query

import kotlin.math.abs
import kotlin.math.roundToLong

/** Number formatting shared by the CLI, the MCP text and the 7-day block (common code has no String.format). */
object Format {
    /** "12,345" or "12.3" style: [decimals] places, thousands separated, "-" for missing. */
    fun number(
        value: Double?,
        decimals: Int = 0,
    ): String {
        if (value == null) return "-"
        var factor = 1L
        repeat(decimals) { factor *= 10 }
        val scaled = (abs(value) * factor).roundToLong()
        val whole =
            (scaled / factor)
                .toString()
                .reversed()
                .chunked(3)
                .joinToString(",")
                .reversed()
        val fraction = if (decimals > 0) "." + (scaled % factor).toString().padStart(decimals, '0') else ""
        val sign = if (value < 0 && scaled != 0L) "-" else ""
        return sign + whole + fraction
    }

    /** A signed change: "+12", "-3", "0". */
    fun signed(
        value: Double?,
        decimals: Int = 0,
    ): String =
        when {
            value == null -> "-"
            value > 0 && number(value, decimals) != number(0.0, decimals) -> "+" + number(value, decimals)
            else -> number(value, decimals)
        }

    /** Seconds as "1:05" (or "0:07"). */
    fun duration(seconds: Double?): String {
        if (seconds == null) return "-"
        val total = seconds.roundToLong()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
}
