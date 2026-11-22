package io.github.rmac0001.foursight.core.store

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.model.ContentType
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.model.PostRef
import io.github.rmac0001.foursight.core.model.Snapshot
import io.github.rmac0001.foursight.db.FourSightDatabase
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import io.github.rmac0001.foursight.db.Event as EventRow
import io.github.rmac0001.foursight.db.Post as PostRow
import io.github.rmac0001.foursight.db.Snapshot as SnapshotRow

/** A stored user edit (spec: Sync, "Events"). [payload] is the event's JSON body. */
data class StoredEvent(
    val eventId: String,
    val deviceId: String,
    val createdAt: Instant,
    val type: String,
    val payload: JsonObject,
)

data class ConnectionStatus(
    val platform: SocialPlatform,
    val lastSuccessAt: Instant?,
    val lastAttemptAt: Instant?,
    val lastError: String?,
    val tokenExpiresAt: Instant?,
)

/**
 * The only code that talks to the database. Every multi-row write runs in one transaction, so a
 * failed refresh or merge never leaves partial data behind (spec: Testing and completion).
 */
class Repository(
    private val db: FourSightDatabase,
) {
    private val q get() = db.fourSightQueries

    @OptIn(ExperimentalUuidApi::class)
    fun deviceId(): String =
        q.getSetting(DEVICE_ID_KEY).executeAsOneOrNull() ?: Uuid.random().toString().also {
            q.putSetting(DEVICE_ID_KEY, it)
        }

    fun setting(key: String): String? = q.getSetting(key).executeAsOneOrNull()

    fun putSetting(
        key: String,
        value: String,
    ) = q.putSetting(key, value)

    // posts

    fun upsertPosts(
        posts: List<PlatformPost>,
        now: Instant,
    ) = db.transaction {
        posts.forEach { p ->
            q.insertPostIfMissing(
                platform = p.platform.name,
                post_id = p.postId,
                title = p.title,
                published_at = p.publishedAt.toEpochMilliseconds(),
                url = p.url,
                duration_seconds = p.durationSeconds,
                content_type = p.contentType.name,
                thumbnail_url = p.thumbnailUrl,
                updated_at = now.toEpochMilliseconds(),
            )
            q.updatePost(
                title = p.title,
                published_at = p.publishedAt.toEpochMilliseconds(),
                url = p.url,
                duration_seconds = p.durationSeconds,
                content_type = p.contentType.name,
                thumbnail_url = p.thumbnailUrl,
                updated_at = now.toEpochMilliseconds(),
                platform = p.platform.name,
                post_id = p.postId,
            )
        }
    }

    fun posts(platform: SocialPlatform? = null): List<PlatformPost> =
        (if (platform == null) q.allPosts() else q.postsByPlatform(platform.name))
            .executeAsList()
            .map { it.toModel() }

    fun post(ref: PostRef): PlatformPost? = q.getPost(ref.platform.name, ref.postId).executeAsOneOrNull()?.toModel()

    // snapshots

    /** Stores snapshots and their values in one transaction. Returns how many were new. */
    fun insertSnapshots(snapshots: List<Snapshot>): Int =
        db.transactionWithResult {
            var inserted = 0
            snapshots.forEach { s ->
                q.insertSnapshot(
                    device_id = s.deviceId,
                    platform = s.platform.name,
                    item_kind = s.itemKind.name,
                    item_id = s.itemId,
                    captured_at = s.capturedAt.toEpochMilliseconds(),
                    capture_day = s.captureDay.toString(),
                    definitions_version = s.definitionsVersion.toLong(),
                    raw_json = json.encodeToString(JsonObject.serializer(), s.raw),
                )
                if (q.changes().executeAsOne() > 0) {
                    inserted++
                    val id =
                        q
                            .findSnapshotId(
                                s.deviceId,
                                s.platform.name,
                                s.itemKind.name,
                                s.itemId,
                                s.capturedAt.toEpochMilliseconds(),
                            ).executeAsOne()
                    s.values.forEach { (metric, value) -> q.insertSnapshotValue(id, metric, value) }
                }
            }
            inserted
        }

    fun snapshots(
        platform: SocialPlatform,
        kind: ItemKind,
        itemId: String,
    ): List<Snapshot> = q.snapshotsForItem(platform.name, kind.name, itemId).executeAsList().map { it.toModel() }

    /** One snapshot per capture day: the latest from any device (spec: Sync, "Merging"). */
    fun latestPerDay(
        platform: SocialPlatform,
        kind: ItemKind,
        itemId: String,
    ): List<Snapshot> = q.latestPerDayForItem(platform.name, kind.name, itemId).executeAsList().map { it.toModel() }

    /** Account-level snapshots for a platform (FourSight tracks one account per platform). */
    fun accountSnapshots(platform: SocialPlatform): List<Snapshot> =
        q.accountSnapshots(platform.name).executeAsList().map { it.toModel() }

    /** Every snapshot of one kind on a platform, oldest first (used for audience breakdowns). */
    fun snapshotsOfKind(
        platform: SocialPlatform,
        kind: ItemKind,
    ): List<Snapshot> = q.snapshotsOfKind(platform.name, kind.name).executeAsList().map { it.toModel() }

    fun snapshotsForDeviceDay(
        deviceId: String,
        platform: SocialPlatform,
        day: LocalDate,
    ): List<Snapshot> =
        q.snapshotsForDeviceDay(deviceId, platform.name, day.toString()).executeAsList().map { it.toModel() }

    fun latestCapture(platform: SocialPlatform): Instant? =
        q.latestCaptureForPlatform(platform.name).executeAsOneOrNull()?.MAX?.let {
            Instant.fromEpochMilliseconds(it)
        }

    // events

    fun insertEvents(events: List<StoredEvent>): Int =
        db.transactionWithResult {
            var inserted = 0
            events.forEach { e ->
                q.insertEvent(
                    e.eventId,
                    e.deviceId,
                    e.createdAt.toEpochMilliseconds(),
                    e.type,
                    json.encodeToString(JsonObject.serializer(), e.payload),
                )
                if (q.changes().executeAsOne() > 0) inserted++
            }
            inserted
        }

    fun events(): List<StoredEvent> = q.allEvents().executeAsList().map { it.toModel() }

    fun eventsForDevice(
        deviceId: String,
        from: Instant,
        until: Instant,
    ): List<StoredEvent> =
        q
            .eventsForDeviceDay(deviceId, from.toEpochMilliseconds(), until.toEpochMilliseconds())
            .executeAsList()
            .map { it.toModel() }

    // connection status

    fun recordAttempt(
        platform: SocialPlatform,
        at: Instant,
        error: String?,
    ) = db.transaction {
        q.ensureConnection(platform.name)
        q.setConnectionAttempt(at.toEpochMilliseconds(), error, platform.name)
        if (error == null) q.markConnectionSuccess(at.toEpochMilliseconds(), platform.name)
    }

    fun setTokenExpiry(
        platform: SocialPlatform,
        expiresAt: Instant?,
    ) = db.transaction {
        q.ensureConnection(platform.name)
        q.setTokenExpiry(expiresAt?.toEpochMilliseconds(), platform.name)
    }

    fun connectionStatus(): List<ConnectionStatus> =
        q.allConnectionStatus().executeAsList().map {
            ConnectionStatus(
                platform = SocialPlatform.valueOf(it.platform),
                lastSuccessAt = it.last_success_at?.let(Instant::fromEpochMilliseconds),
                lastAttemptAt = it.last_attempt_at?.let(Instant::fromEpochMilliseconds),
                lastError = it.last_error,
                tokenExpiresAt = it.token_expires_at?.let(Instant::fromEpochMilliseconds),
            )
        }

    // sync bookkeeping

    fun syncedVersion(path: String): String? = q.syncedVersion(path).executeAsOneOrNull()

    fun markSynced(
        path: String,
        version: String,
    ) = q.markSynced(path, version)

    fun <T> transaction(body: () -> T): T = db.transactionWithResult { body() }

    private fun PostRow.toModel() =
        PlatformPost(
            platform = SocialPlatform.valueOf(platform),
            postId = post_id,
            title = title,
            publishedAt = Instant.fromEpochMilliseconds(published_at),
            url = url,
            durationSeconds = duration_seconds,
            contentType = ContentType.valueOf(content_type),
            thumbnailUrl = thumbnail_url,
        )

    private fun SnapshotRow.toModel() =
        Snapshot(
            deviceId = device_id,
            platform = SocialPlatform.valueOf(platform),
            itemKind = ItemKind.valueOf(item_kind),
            itemId = item_id,
            capturedAt = Instant.fromEpochMilliseconds(captured_at),
            captureDay = LocalDate.parse(capture_day),
            definitionsVersion = definitions_version.toInt(),
            raw = json.decodeFromString(JsonObject.serializer(), raw_json),
            values = q.valuesForSnapshot(id).executeAsList().associate { it.metric to it.value_ },
        )

    private fun EventRow.toModel() =
        StoredEvent(
            eventId = event_id,
            deviceId = device_id,
            createdAt = Instant.fromEpochMilliseconds(created_at),
            type = type,
            payload = json.decodeFromString(JsonObject.serializer(), payload_json),
        )

    companion object {
        const val DEVICE_ID_KEY = "device_id"
        private val json = Json { encodeDefaults = true }
    }
}
