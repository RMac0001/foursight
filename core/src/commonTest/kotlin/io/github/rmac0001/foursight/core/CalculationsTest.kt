package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.metrics.Calculations
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.Snapshot
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

// TEST DATA: every number below is made up for these tests.
class CalculationsTest {
    private val t0 = Instant.parse("2026-11-01T00:00:00Z")

    private fun snap(
        platform: SocialPlatform,
        values: Map<String, Double?>,
    ) = Snapshot("d", platform, ItemKind.POST, "p", t0, LocalDate(2026, 11, 1), 1, JsonObject(emptyMap()), values)

    @Test
    fun per1kDividesByViews() {
        val c = Calculations.per1k("shares_per_1k", 25.0, 5_000.0)
        assertEquals(5.0, c.value)
        assertEquals(mapOf("count" to 25.0, "views" to 5_000.0), c.inputs)
    }

    @Test
    fun per1kIsMissingNotZeroOrInfinite() {
        assertNull(Calculations.per1k("x", null, 1000.0).value)
        assertNull(Calculations.per1k("x", 3.0, null).value)
        val zeroViews = Calculations.per1k("x", 3.0, 0.0)
        assertNull(zeroViews.value)
        assertEquals("No views yet.", zeroViews.missingReason)
    }

    @Test
    fun viewsPerDayUsesExactAge() {
        val c = Calculations.viewsPerDay(1_200.0, t0, t0 + 2.days + 12.hours)
        assertEquals(480.0, c.value)
        assertNull(Calculations.viewsPerDay(10.0, t0, t0).value)
    }

    @Test
    fun engagementRateIncludesSavesOnlyWhereReported() {
        // Instagram reports saves: (40 + 5 + 3 + 2) / 1000 = 5%.
        val ig =
            snap(
                SocialPlatform.INSTAGRAM,
                mapOf("views" to 1000.0, "likes" to 40.0, "comments" to 5.0, "shares" to 3.0, "saves" to 2.0),
            )
        assertEquals(5.0, Calculations.engagementRate(ig, SocialPlatform.INSTAGRAM).value!!, 1e-9)

        // TikTok has no saves field, so a missing save count doesn't block the rate: (40 + 5 + 5) / 1000.
        val tt =
            snap(
                SocialPlatform.TIKTOK,
                mapOf(
                    "views" to 1000.0,
                    "likes" to 40.0,
                    "comments" to 5.0,
                    "shares" to 5.0,
                ),
            )
        val rate = Calculations.engagementRate(tt, SocialPlatform.TIKTOK)
        assertEquals(5.0, rate.value!!, 1e-9)
        assertEquals(setOf("views", "likes", "comments", "shares"), rate.inputs.keys)
    }

    @Test
    fun engagementRateIsMissingWhenAnInputIsMissing() {
        val s =
            snap(
                SocialPlatform.INSTAGRAM,
                mapOf(
                    "views" to 1000.0,
                    "likes" to 40.0,
                    "comments" to 5.0,
                    "shares" to 3.0,
                ),
            )
        val c = Calculations.engagementRate(s, SocialPlatform.INSTAGRAM)
        assertNull(c.value)
        assertEquals("Missing: saves.", c.missingReason)
    }

    @Test
    fun countsSumAcrossPlatforms() {
        val c =
            Calculations.sumAcrossPlatforms(
                Metrics.VIEWS,
                mapOf(SocialPlatform.TIKTOK to 100.0, SocialPlatform.YOUTUBE to 50.0),
            )
        assertEquals(150.0, c.value)
    }

    @Test
    fun aMissingPlatformMakesTheTotalMissing() {
        val c =
            Calculations.sumAcrossPlatforms(
                Metrics.VIEWS,
                mapOf(SocialPlatform.TIKTOK to 100.0, SocialPlatform.FACEBOOK to null),
            )
        assertNull(c.value)
        assertNotNull(c.missingReason)
    }

    @Test
    fun ratesAndDurationsCanNeverBeSummed() {
        assertFailsWith<IllegalArgumentException> {
            Calculations.sumAcrossPlatforms(Metrics.AVERAGE_VIEW_DURATION, mapOf(SocialPlatform.YOUTUBE to 10.0))
        }
        assertFailsWith<IllegalArgumentException> {
            Calculations.sumAcrossPlatforms(Metrics.SKIP_RATE, mapOf(SocialPlatform.INSTAGRAM to 10.0))
        }
    }

    @Test
    fun everyMetricHasADefinitionAndASourceOrReasonPerPlatform() {
        Metrics.all.forEach { m ->
            assert(m.definition.isNotBlank()) { m.id }
            SocialPlatform.entries.forEach { p -> assertNotNull(m.availability(p), "${m.id}/$p") }
        }
        assertEquals(
            Metrics.all.size,
            Metrics.all
                .map { it.id }
                .toSet()
                .size,
            "duplicate metric ids",
        )
    }
}
