package io.github.rmac0001.foursight.core.sync

/**
 * Plain-file storage shared by the devices (spec: Sync through Google Drive). Paths are relative to
 * the FourSight folder and use "/" separators, for example `snapshots/<device>/TIKTOK/2026-11-21.json`.
 * Each file has exactly one writer (the device named in its path), so there are no write conflicts.
 *
 * Google Drive is the first adapter; another provider only needs a new implementation of this
 * interface.
 */
interface SyncStore {
    /** Every file under [prefix] (recursively), with a version that changes when its content changes. */
    suspend fun list(prefix: String): List<SyncFile>

    suspend fun read(path: String): ByteArray

    /** Creates the file (and any missing folders) or replaces its content. */
    suspend fun write(
        path: String,
        content: ByteArray,
    )
}

data class SyncFile(
    val path: String,
    val version: String,
)

/** Thrown for failures talking to the store; a sync failure never touches local data. */
class SyncException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
