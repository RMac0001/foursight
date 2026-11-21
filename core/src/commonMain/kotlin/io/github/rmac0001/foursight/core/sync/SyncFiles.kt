package io.github.rmac0001.foursight.core.sync

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.Snapshot
import io.github.rmac0001.foursight.core.store.StoredEvent
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant

/** File layout in the FourSight Drive folder (spec: Sync through Google Drive). */
object SyncPaths {
    const val SNAPSHOTS = "snapshots"
    const val EVENTS = "events"
    const val DEVICE_REGISTRY_DIR = "devices"

    fun snapshotFile(
        deviceId: String,
        platform: SocialPlatform,
        day: LocalDate,
    ) = "$SNAPSHOTS/$deviceId/${platform.name}/$day.json"

    fun eventFile(
        deviceId: String,
        day: LocalDate,
    ) = "$EVENTS/$deviceId/$day.jsonl"

    /**
     * One registry file per device instead of the spec's single `device-registry.json`, so the
     * one-writer-per-file rule holds when two devices register.
     */
    fun deviceFile(deviceId: String) = "$DEVICE_REGISTRY_DIR/$deviceId.json"
}

@Serializable
data class SnapshotFile(
    val formatVersion: Int = 1,
    val deviceId: String,
    val platform: SocialPlatform,
    val day: LocalDate,
    val snapshots: List<SnapshotEntry>,
)

@Serializable
data class SnapshotEntry(
    val itemKind: ItemKind,
    val itemId: String,
    val capturedAt: Instant,
    val definitionsVersion: Int,
    val raw: JsonObject,
    val values: Map<String, Double?>,
)

@Serializable
data class EventLine(
    val eventId: String,
    val deviceId: String,
    val createdAt: Instant,
    val type: String,
    val payload: JsonObject,
)

@Serializable
data class DeviceEntry(
    val deviceId: String,
    val name: String,
    val kind: String,
    val registeredAt: Instant,
)

internal val syncJson =
    Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = true
    }

internal fun Snapshot.toEntry() = SnapshotEntry(itemKind, itemId, capturedAt, definitionsVersion, raw, values)

internal fun SnapshotEntry.toSnapshot(
    deviceId: String,
    platform: SocialPlatform,
    day: LocalDate,
) = Snapshot(deviceId, platform, itemKind, itemId, capturedAt, day, definitionsVersion, raw, values)

internal fun StoredEvent.toLine() = EventLine(eventId, deviceId, createdAt, type, payload)

internal fun EventLine.toStored() = StoredEvent(eventId, deviceId, createdAt, type, payload)
