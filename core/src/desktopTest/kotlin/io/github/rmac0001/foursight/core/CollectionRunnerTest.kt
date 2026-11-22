package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.SocialPlatform.TIKTOK
import io.github.rmac0001.foursight.core.SocialPlatform.YOUTUBE
import io.github.rmac0001.foursight.core.collect.Collection
import io.github.rmac0001.foursight.core.collect.CollectionException
import io.github.rmac0001.foursight.core.collect.CollectionRunner
import io.github.rmac0001.foursight.core.collect.FailureKind
import io.github.rmac0001.foursight.core.collect.PlatformCollector
import io.github.rmac0001.foursight.core.model.ItemKind
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** TEST DATA ONLY: fake collectors returning labeled fixture snapshots. */
class CollectionRunnerTest {
    private class FakeCollector(
        override val platform: SocialPlatform,
        val fail: CollectionException? = null,
        val views: Double = 100.0,
    ) : PlatformCollector {
        var calls = 0

        override suspend fun collect(
            now: Instant,
            deviceId: String,
            captureDay: LocalDate,
        ): Collection {
            calls++
            fail?.let { throw it }
            val post = Fixtures.post(platform, "p-${platform.name}")
            return Collection(
                posts = listOf(post),
                snapshots = listOf(Fixtures.snapshot(deviceId, platform, post.postId, now, mapOf("views" to views))),
                tokenExpiresAt = now + 180.days,
            )
        }
    }

    @Test
    fun oneFailingPlatformDoesNotBlockOthersAndKeepsEarlierData() =
        runTest {
            val repo = Fixtures.repo()
            var now = Fixtures.published + 1.days
            val good = FakeCollector(YOUTUBE)
            CollectionRunner(repo, listOf(FakeCollector(TIKTOK), good), Fixtures.zone, { now }).run()
            assertEquals(1, repo.snapshots(TIKTOK, ItemKind.POST, "p-TIKTOK").size)

            now += 1.days
            val failing =
                FakeCollector(TIKTOK, CollectionException(TIKTOK, FailureKind.SIGN_IN_EXPIRED, "token refresh failed"))
            val results = CollectionRunner(repo, listOf(failing, good), Fixtures.zone, { now }).run()

            assertEquals(FailureKind.SIGN_IN_EXPIRED, results[0].failure)
            assertTrue(results[1].ok)
            assertEquals(1, repo.snapshots(TIKTOK, ItemKind.POST, "p-TIKTOK").size, "earlier data untouched")
            assertEquals(2, repo.snapshots(YOUTUBE, ItemKind.POST, "p-YOUTUBE").size)
            val status = repo.connectionStatus().associateBy { it.platform }
            assertEquals("TikTok: token refresh failed", status.getValue(TIKTOK).lastError)
            assertEquals(Fixtures.published + 1.days, status.getValue(TIKTOK).lastSuccessAt)
            assertNull(status.getValue(YOUTUBE).lastError)
            assertEquals(now + 180.days, status.getValue(YOUTUBE).tokenExpiresAt)
        }

    @Test
    fun syncFailureIsAWarningAndKeepsStoredData() =
        runTest {
            val repo = Fixtures.repo()
            val runner =
                CollectionRunner(
                    repo,
                    listOf(FakeCollector(TIKTOK)),
                    Fixtures.zone,
                    { Fixtures.published + 1.days },
                ) { _, _ ->
                    error("drive down")
                }
            val result = runner.run().single()
            assertTrue(result.ok)
            assertEquals(1, result.warnings.size)
            assertEquals(1, repo.snapshots(TIKTOK, ItemKind.POST, "p-TIKTOK").size)
        }

    @Test
    fun day7RunCollectsOnlyPlatformsWithAPostInTheWindow() =
        runTest {
            val repo = Fixtures.repo()
            val tiktok = FakeCollector(TIKTOK)
            val youtube = FakeCollector(YOUTUBE)
            var now = Fixtures.published + 6.days
            val runner = CollectionRunner(repo, listOf(tiktok, youtube), Fixtures.zone, { now })
            runner.run()

            now = Fixtures.published + 6.days + 12.hours
            assertTrue(runner.runDay7Due().isEmpty(), "not due before day 7.0")

            now = Fixtures.published + 7.days + 2.hours
            // Give YouTube its day-7 capture already, so only TikTok is due.
            repo.insertSnapshots(
                listOf(Fixtures.snapshot(repo.deviceId(), YOUTUBE, "p-YOUTUBE", now - 1.hours, mapOf("views" to 1.0))),
            )
            val results = runner.runDay7Due()
            assertEquals(listOf(TIKTOK), results.map { it.platform })
            assertEquals(2, tiktok.calls)
            assertEquals(1, youtube.calls)
            assertTrue(runner.runDay7Due().isEmpty(), "locked after the capture")
        }
}
