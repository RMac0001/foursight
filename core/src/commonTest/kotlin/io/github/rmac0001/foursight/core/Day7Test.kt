package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.metrics.Day7
import io.github.rmac0001.foursight.core.metrics.Day7Result
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.Snapshot
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

// TEST DATA: made-up snapshots.
class Day7Test {
    private val published = Instant.parse("2026-11-01T15:00:00Z")

    private fun at(age: kotlin.time.Duration) =
        Snapshot(
            "d",
            SocialPlatform.TIKTOK,
            ItemKind.POST,
            "p",
            published + age,
            LocalDate(2026, 11, 1),
            1,
            JsonObject(emptyMap()),
            emptyMap(),
        )

    @Test
    fun youngPostsAreNotDue() {
        assertEquals(Day7Result.NotYetDue, Day7.lock(published, listOf(at(6.days)), published + 6.days + 23.hours))
    }

    @Test
    fun locksOnTheFirstCaptureAtOrAfterSevenDaysAndKeepsTheExactAge() {
        val snapshots = listOf(at(6.days), at(7.days + 10.hours), at(7.days + 20.hours))
        val result = Day7.lock(published, snapshots, published + 10.days)
        assertIs<Day7Result.Locked>(result)
        assertEquals(7.days + 10.hours, result.age)
        assertEquals(7 + 10.0 / 24, result.ageDays, 1e-9)
    }

    @Test
    fun aCaptureExactlyAtSevenDaysLocks() {
        val result = Day7.lock(published, listOf(at(7.days)), published + 7.days)
        assertIs<Day7Result.Locked>(result)
    }

    @Test
    fun pendingWhileTheWindowIsOpen() {
        assertEquals(Day7Result.Pending, Day7.lock(published, listOf(at(6.days)), published + 7.days + 5.hours))
    }

    @Test
    fun aCaptureAfterTheWindowIsAMissNotALock() {
        val result = Day7.lock(published, listOf(at(6.days), at(9.days)), published + 10.days)
        assertIs<Day7Result.Missed>(result)
        assertEquals(9.days, result.firstCaptureAfterAge)
    }

    @Test
    fun noCapturesAtAllIsAMiss() {
        assertEquals(Day7Result.Missed(null), Day7.lock(published, emptyList(), published + 30.days))
    }
}
