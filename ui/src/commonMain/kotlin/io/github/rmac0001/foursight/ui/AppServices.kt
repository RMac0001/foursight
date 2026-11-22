package io.github.rmac0001.foursight.ui

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.collect.PlatformRunResult
import io.github.rmac0001.foursight.core.model.PostRef
import io.github.rmac0001.foursight.core.query.ExportFormat
import io.github.rmac0001.foursight.core.query.QueryService
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** A sign-in the Accounts section can start. */
data class Connection(
    val id: String,
    val label: String,
    /** The platforms this sign-in serves (Meta covers Facebook and Instagram; Drive serves none). */
    val platforms: Set<SocialPlatform>,
    val connected: Boolean,
)

/** Where the Obsidian writer stands, for the Settings section. */
data class VaultStatus(
    val vault: String?,
    val notesFolder: String,
    val overviewNote: String,
)

/**
 * What the shared screens need from the app around them. Each platform (Windows now, Android in
 * step 7) implements it with its own sign-in, file and browser handling; every number still comes
 * from [queries] in core. Nothing here exposes tokens or secrets.
 */
interface AppServices {
    val queries: QueryService
    val timeZone: TimeZone

    fun now(): Instant

    fun connections(): List<Connection>

    /** Runs the normal collector (all platforms when [platforms] is null), then the vault writer. */
    suspend fun refresh(platforms: Set<SocialPlatform>?): List<PlatformRunResult>

    /** Starts a sign-in in the browser and waits for it; returns a message to show. */
    suspend fun connect(connectionId: String): String

    /** Links posts as one video (Roger confirmed it); returns a message to show. */
    suspend fun confirmMatch(refs: List<PostRef>): String

    /** Asks where to save, then writes the export; returns a message, or null if cancelled. */
    suspend fun export(format: ExportFormat): String?

    fun openUrl(url: String)

    fun setting(key: String): String?

    fun putSetting(
        key: String,
        value: String,
    )

    fun vaultStatus(): VaultStatus?

    /** Sets the vault folder; returns a message (an error message if it isn't a vault). */
    fun setVault(path: String): String

    val driveConnected: Boolean

    /** The platform's fonts for the Microsoft 365 look and the brand wordmark; defaults when absent. */
    val fonts: BrandFonts get() = BrandFonts()
}
