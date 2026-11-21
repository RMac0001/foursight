package io.github.rmac0001.foursight.core.sync

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.store.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlin.time.Instant

data class SyncReport(
    val filesRead: Int,
    val filesSkipped: Int,
    val snapshotsAdded: Int,
    val eventsAdded: Int,
    val errors: List<String>,
)

/**
 * Publishes this device's files and merges everyone else's (spec: Sync, "Merging").
 *
 * - This device only ever writes files under its own device id.
 * - Merging uses insert-or-ignore on each snapshot's and event's natural key, so merging the same
 *   file twice changes nothing.
 * - Each file is merged in its own transaction; a bad or unreadable file is reported and skipped,
 *   and local data is never deleted or overwritten by a sync.
 */
class SyncEngine(
    private val store: SyncStore,
    private val repo: Repository,
    private val timeZone: TimeZone,
) {
    private val deviceId get() = repo.deviceId()

    /** Rewrites this device's snapshot file for [day] on [platform] from the local database. */
    suspend fun publishSnapshots(
        platform: SocialPlatform,
        day: LocalDate,
    ) {
        val snapshots = repo.snapshotsForDeviceDay(deviceId, platform, day)
        if (snapshots.isEmpty()) return
        val file =
            SnapshotFile(
                deviceId = deviceId,
                platform = platform,
                day = day,
                snapshots = snapshots.map { it.toEntry() },
            )
        store.write(
            SyncPaths.snapshotFile(deviceId, platform, day),
            syncJson.encodeToString(SnapshotFile.serializer(), file).encodeToByteArray(),
        )
    }

    /** Rewrites this device's event file for [day] (one JSON object per line). */
    suspend fun publishEvents(day: LocalDate) {
        val from = day.atStartOfDayIn(timeZone)
        val until = day.plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone)
        val events = repo.eventsForDevice(deviceId, from, until)
        if (events.isEmpty()) return
        val body =
            events.joinToString(separator = "\n", postfix = "\n") {
                syncJson.encodeToString(EventLine.serializer(), it.toLine())
            }
        store.write(SyncPaths.eventFile(deviceId, day), body.encodeToByteArray())
    }

    suspend fun registerDevice(
        name: String,
        kind: String,
        now: Instant,
    ) {
        val path = SyncPaths.deviceFile(deviceId)
        if (store.list(SyncPaths.DEVICE_REGISTRY_DIR).any { it.path == path }) return
        val entry = DeviceEntry(deviceId, name, kind, now)
        store.write(path, syncJson.encodeToString(DeviceEntry.serializer(), entry).encodeToByteArray())
    }

    /** Reads every snapshot and event file not yet seen at its current version and merges it. */
    suspend fun merge(): SyncReport {
        var read = 0
        var skipped = 0
        var snapshotsAdded = 0
        var eventsAdded = 0
        val errors = mutableListOf<String>()

        val files =
            try {
                store.list(SyncPaths.SNAPSHOTS) + store.list(SyncPaths.EVENTS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return SyncReport(0, 0, 0, 0, listOf("Couldn't list sync files: ${e.message}"))
            }

        for (file in files.sortedBy { it.path }) {
            if (repo.syncedVersion(file.path) == file.version) {
                skipped++
                continue
            }
            try {
                val text = store.read(file.path).decodeToString()
                repo.transaction {
                    when {
                        file.path.startsWith("${SyncPaths.SNAPSHOTS}/") -> {
                            val parsed = syncJson.decodeFromString(SnapshotFile.serializer(), text)
                            checkOwner(file.path, parsed.deviceId)
                            snapshotsAdded +=
                                repo.insertSnapshots(
                                    parsed.snapshots.map {
                                        it.toSnapshot(
                                            parsed.deviceId,
                                            parsed.platform,
                                            parsed.day,
                                        )
                                    },
                                )
                        }

                        file.path.startsWith("${SyncPaths.EVENTS}/") -> {
                            val lines = text.lineSequence().filter { it.isNotBlank() }
                            val events = lines.map { syncJson.decodeFromString(EventLine.serializer(), it) }.toList()
                            events.forEach { checkOwner(file.path, it.deviceId) }
                            eventsAdded += repo.insertEvents(events.map { it.toStored() })
                        }
                    }
                    repo.markSynced(file.path, file.version)
                }
                read++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "${file.path}: ${e.message}"
            }
        }
        return SyncReport(read, skipped, snapshotsAdded, eventsAdded, errors)
    }

    /** A file may only carry data from the device named in its path (the one-writer rule). */
    private fun checkOwner(
        path: String,
        deviceId: String,
    ) {
        val owner = path.split('/').getOrNull(1)
        require(owner == deviceId) { "File is in $owner's folder but contains data from $deviceId." }
    }
}
