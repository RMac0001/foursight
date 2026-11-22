package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.Fixtures.published
import io.github.rmac0001.foursight.core.Fixtures.zone
import io.github.rmac0001.foursight.core.SocialPlatform.TIKTOK
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.PostRef
import io.github.rmac0001.foursight.core.sync.MatchEvents
import io.github.rmac0001.foursight.core.sync.SyncEngine
import io.github.rmac0001.foursight.core.sync.SyncPaths
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

// TEST DATA: two fake devices sharing a fake store.
class SyncEngineTest {
    private val store = FakeSyncStore()
    private val windows = Fixtures.repo()
    private val android = Fixtures.repo()
    private val windowsSync = SyncEngine(store, windows, zone)
    private val androidSync = SyncEngine(store, android, zone)
    private val day = (published + 1.days).toLocalDateTime(zone).date

    @Test
    fun snapshotsReachTheOtherDevice() =
        runTest {
            windows.insertSnapshots(
                listOf(Fixtures.snapshot(windows.deviceId(), TIKTOK, "p1", published + 1.days, mapOf("views" to 10.0))),
            )
            windowsSync.publishSnapshots(TIKTOK, day)
            val report = androidSync.merge()
            assertEquals(1, report.snapshotsAdded)
            assertEquals(10.0, android.snapshots(TIKTOK, ItemKind.POST, "p1").single().value("views"))
        }

    @Test
    fun mergingTwiceChangesNothing() =
        runTest {
            windows.insertSnapshots(
                listOf(Fixtures.snapshot(windows.deviceId(), TIKTOK, "p1", published + 1.days, mapOf("views" to 10.0))),
            )
            windowsSync.publishSnapshots(TIKTOK, day)
            androidSync.merge()
            val second = androidSync.merge()
            assertEquals(0, second.snapshotsAdded)
            assertEquals(1, second.filesSkipped)
        }

    @Test
    fun aRewrittenFileIsMergedAgainWithoutDuplicates() =
        runTest {
            val first = Fixtures.snapshot(windows.deviceId(), TIKTOK, "p1", published + 1.days, mapOf("views" to 10.0))
            windows.insertSnapshots(listOf(first))
            windowsSync.publishSnapshots(TIKTOK, day)
            androidSync.merge()
            windows.insertSnapshots(
                listOf(
                    first.copy(
                        capturedAt = first.capturedAt + 30.minutes,
                        values =
                            mapOf("views" to 12.0),
                    ),
                ),
            )
            windowsSync.publishSnapshots(TIKTOK, day)
            val report = androidSync.merge()
            assertEquals(1, report.snapshotsAdded)
            assertEquals(2, android.snapshots(TIKTOK, ItemKind.POST, "p1").size)
        }

    @Test
    fun aDeviceMergingItsOwnFilesIsANoOp() =
        runTest {
            windows.insertSnapshots(
                listOf(Fixtures.snapshot(windows.deviceId(), TIKTOK, "p1", published + 1.days, mapOf("views" to 10.0))),
            )
            windowsSync.publishSnapshots(TIKTOK, day)
            assertEquals(0, windowsSync.merge().snapshotsAdded)
        }

    @Test
    fun conflictingEditsResolveTheSameWayOnBothDevicesAndKeepTheLoser() =
        runTest {
            val post = PostRef(TIKTOK, "p1")
            val t = published + 2.days
            windows.insertEvents(listOf(MatchEvents.link("e-1", windows.deviceId(), t, "video-a", post)))
            android.insertEvents(listOf(MatchEvents.unlink("e-2", android.deviceId(), t + 1.minutes, post)))
            val eventDay = t.toLocalDateTime(zone).date
            windowsSync.publishEvents(eventDay)
            androidSync.publishEvents(eventDay)
            windowsSync.merge()
            androidSync.merge()

            assertEquals(2, windows.events().size)
            assertEquals(2, android.events().size)
            assertEquals(emptyMap(), MatchEvents.currentLinks(windows.events()))
            assertEquals(MatchEvents.currentLinks(windows.events()), MatchEvents.currentLinks(android.events()))
        }

    @Test
    fun simultaneousEditsBreakTiesByEventId() {
        val post = PostRef(TIKTOK, "p1")
        val t = published
        val events =
            listOf(
                MatchEvents.link("b", "dev-2", t, "video-b", post),
                MatchEvents.link("a", "dev-1", t, "video-a", post),
            )
        assertEquals(mapOf(post to "video-b"), MatchEvents.currentLinks(events))
        assertEquals(mapOf(post to "video-b"), MatchEvents.currentLinks(events.reversed()))
    }

    @Test
    fun aCorruptFileIsReportedAndLocalDataIsUntouched() =
        runTest {
            android.insertSnapshots(
                listOf(Fixtures.snapshot(android.deviceId(), TIKTOK, "p1", published, mapOf("views" to 5.0))),
            )
            store.write(SyncPaths.snapshotFile("dev-x", TIKTOK, day), "{ not json".encodeToByteArray())
            val report = androidSync.merge()
            assertEquals(1, report.errors.size)
            assertEquals(1, android.snapshots(TIKTOK, ItemKind.POST, "p1").size)
        }

    @Test
    fun aFileInAnotherDevicesFolderIsRejected() =
        runTest {
            windows.insertSnapshots(
                listOf(Fixtures.snapshot(windows.deviceId(), TIKTOK, "p1", published + 1.days, mapOf("views" to 10.0))),
            )
            windowsSync.publishSnapshots(TIKTOK, day)
            val (path, content) =
                store.files.entries
                    .single()
                    .let { it.key to it.value.first }
            store.files.remove(path)
            store.write(path.replace(windows.deviceId(), "impostor"), content)
            val report = androidSync.merge()
            assertEquals(0, report.snapshotsAdded)
            assertTrue(report.errors.single().contains("impostor"))
        }

    @Test
    fun aSyncThatCantListReportsWithoutThrowing() =
        runTest {
            val broken =
                object : io.github.rmac0001.foursight.core.sync.SyncStore by store {
                    override suspend fun list(prefix: String) = throw RuntimeException("offline")
                }
            val report = SyncEngine(broken, android, zone).merge()
            assertTrue(report.errors.single().contains("offline"))
        }
}
