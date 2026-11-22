package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.ContentType
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.model.Snapshot
import io.github.rmac0001.foursight.core.store.DesktopDatabase
import io.github.rmac0001.foursight.core.store.Repository
import io.github.rmac0001.foursight.core.sync.SyncFile
import io.github.rmac0001.foursight.core.sync.SyncStore
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * TEST FIXTURES ONLY. Every value here is made up for tests and labeled as such in its raw JSON;
 * none of it is real analytics data, and none of it ships in a release build.
 */
object Fixtures {
    val zone = TimeZone.of("America/Chicago")
    val published: Instant = Instant.parse("2026-11-01T15:00:00Z")

    fun repo(): Repository = Repository(DesktopDatabase.inMemory())

    fun post(
        platform: SocialPlatform = SocialPlatform.TIKTOK,
        id: String = "post-1",
        publishedAt: Instant = published,
    ) = PlatformPost(platform, id, "Fixture video $id", publishedAt, null, 45, ContentType.SHORT_FORM, null)

    fun snapshot(
        deviceId: String,
        platform: SocialPlatform,
        itemId: String,
        capturedAt: Instant,
        values: Map<String, Double?>,
        kind: ItemKind = ItemKind.POST,
    ) = Snapshot(
        deviceId = deviceId,
        platform = platform,
        itemKind = kind,
        itemId = itemId,
        capturedAt = capturedAt,
        captureDay = capturedAt.toLocalDateTime(zone).date,
        definitionsVersion = Metrics.DEFINITIONS_VERSION,
        raw =
            buildJsonObject {
                put("fixture", "TEST DATA - not real analytics")
            },
        values = values,
    )
}

/** In-memory [SyncStore] for tests: a version counter per path, like Drive's file version. */
class FakeSyncStore : SyncStore {
    val files = mutableMapOf<String, Pair<ByteArray, Int>>()

    override suspend fun list(prefix: String): List<SyncFile> =
        files
            .filterKeys { it.startsWith(prefix.trimEnd('/') + "/") }
            .map { (path, v) -> SyncFile(path, v.second.toString()) }

    override suspend fun read(path: String): ByteArray = files[path]?.first ?: error("missing $path")

    override suspend fun write(
        path: String,
        content: ByteArray,
    ) {
        files[path] = content to ((files[path]?.second ?: 0) + 1)
    }
}
