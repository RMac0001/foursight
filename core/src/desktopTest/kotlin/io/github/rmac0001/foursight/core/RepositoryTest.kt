package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.Fixtures.published
import io.github.rmac0001.foursight.core.SocialPlatform.TIKTOK
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.db.FourSightDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

// TEST DATA: made-up posts and snapshots.
class RepositoryTest {
    @Test
    fun schemaIsVersionOne() {
        // Bump this test with every migration, and add a migration file (verifyMigrations checks it).
        assertEquals(1L, FourSightDatabase.Schema.version)
    }

    @Test
    fun deviceIdIsStable() {
        val repo = Fixtures.repo()
        assertEquals(repo.deviceId(), repo.deviceId())
    }

    @Test
    fun insertingTheSameSnapshotTwiceChangesNothing() {
        val repo = Fixtures.repo()
        val s = Fixtures.snapshot("dev-a", TIKTOK, "p1", published + 1.days, mapOf("views" to 100.0))
        assertEquals(1, repo.insertSnapshots(listOf(s)))
        assertEquals(0, repo.insertSnapshots(listOf(s)))
        assertEquals(1, repo.snapshots(TIKTOK, ItemKind.POST, "p1").size)
    }

    @Test
    fun missingValuesStayMissing() {
        val repo = Fixtures.repo()
        val s = Fixtures.snapshot("dev-a", TIKTOK, "p1", published, mapOf("views" to 100.0, "saves" to null))
        repo.insertSnapshots(listOf(s))
        val stored = repo.snapshots(TIKTOK, ItemKind.POST, "p1").single()
        assertEquals(100.0, stored.value("views"))
        assertNull(stored.value("saves"))
        assertEquals(true, "saves" in stored.values)
        assertEquals(s.raw, stored.raw)
    }

    @Test
    fun latestCapturePerDayWinsAcrossDevices() {
        val repo = Fixtures.repo()
        val day1Morning = published + 1.days
        repo.insertSnapshots(
            listOf(
                Fixtures.snapshot("dev-a", TIKTOK, "p1", day1Morning, mapOf("views" to 100.0)),
                Fixtures.snapshot("dev-b", TIKTOK, "p1", day1Morning + 3.hours, mapOf("views" to 140.0)),
                Fixtures.snapshot("dev-a", TIKTOK, "p1", published + 2.days, mapOf("views" to 200.0)),
            ),
        )
        val perDay = repo.latestPerDay(TIKTOK, ItemKind.POST, "p1")
        assertEquals(listOf(140.0, 200.0), perDay.map { it.value("views") })
    }

    @Test
    fun aFailedWriteLeavesNothingBehind() {
        val repo = Fixtures.repo()
        val good = Fixtures.snapshot("dev-a", TIKTOK, "p1", published, mapOf("views" to 1.0))
        assertFailsWith<IllegalStateException> {
            repo.transaction {
                repo.insertSnapshots(listOf(good))
                error("collector failed halfway")
            }
        }
        assertEquals(0, repo.snapshots(TIKTOK, ItemKind.POST, "p1").size)
    }

    @Test
    fun postsUpdateInPlace() {
        val repo = Fixtures.repo()
        val now = Clock.System.now()
        repo.upsertPosts(listOf(Fixtures.post(id = "p1")), now)
        repo.upsertPosts(listOf(Fixtures.post(id = "p1").copy(title = "Renamed")), now)
        assertEquals("Renamed", repo.posts().single().title)
    }

    @Test
    fun connectionStatusRecordsErrorsAndSuccess() {
        val repo = Fixtures.repo()
        repo.recordAttempt(TIKTOK, published, "HTTP 401")
        assertEquals("HTTP 401", repo.connectionStatus().single().lastError)
        assertNull(repo.connectionStatus().single().lastSuccessAt)
        repo.recordAttempt(TIKTOK, published + 1.hours, null)
        val status = repo.connectionStatus().single()
        assertNull(status.lastError)
        assertEquals(published + 1.hours, status.lastSuccessAt)
    }
}
