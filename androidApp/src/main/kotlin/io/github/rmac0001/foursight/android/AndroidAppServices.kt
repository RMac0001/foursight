package io.github.rmac0001.foursight.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.collect.PlatformRunResult
import io.github.rmac0001.foursight.core.model.PostRef
import io.github.rmac0001.foursight.core.query.ExportFormat
import io.github.rmac0001.foursight.core.query.Exporter
import io.github.rmac0001.foursight.core.query.Matching
import io.github.rmac0001.foursight.core.query.QueryService
import io.github.rmac0001.foursight.core.store.AndroidDatabase
import io.github.rmac0001.foursight.core.store.Repository
import io.github.rmac0001.foursight.core.sync.DriveSyncStore
import io.github.rmac0001.foursight.core.sync.SyncEngine
import io.github.rmac0001.foursight.ui.AppServices
import io.github.rmac0001.foursight.ui.Connection
import io.github.rmac0001.foursight.ui.VaultStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The Android app's [AppServices] (step 7, part 1: sync). The phone has its own database and reads
 * the Windows PC's snapshot and event files from Google Drive (spec: Sync), so it shows the same
 * numbers. Its own sign-ins to the platforms and daily collection come in part 2.
 */
class AndroidAppServices(
    private val context: Context,
) : AppServices {
    private val http =
        HttpClient(Android) {
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 20_000
            }
            // No logging plugin: request logs would contain tokens.
        }
    val repo = Repository(AndroidDatabase.open(context))
    val drive = DriveAuthorizer(context)

    /** Set by the visible Activity, so sign-in and the save dialog can be shown. */
    @Volatile
    var activity: Activity? = null

    @Volatile
    var exportLauncher: ((String) -> Unit)? = null
    private var exportTarget: CompletableDeferred<Uri?>? = null

    override val queries = QueryService(repo)
    override val timeZone: TimeZone get() = TimeZone.currentSystemDefault()

    private val sync =
        SyncEngine(
            DriveSyncStore(http, { activity?.let { drive.interactiveToken(it) } ?: drive.silentToken() }),
            repo,
            timeZone,
        )

    override fun now(): Instant = Clock.System.now()

    override val canCollect: Boolean get() = false
    override val hasObsidian: Boolean get() = false

    override val driveConnected: Boolean get() = repo.setting(DRIVE_ALLOWED) == "true"

    override fun connections(): List<Connection> =
        listOf(Connection("drive", "Google Drive (sync)", emptySet(), driveConnected))

    override suspend fun refresh(platforms: Set<SocialPlatform>?): List<PlatformRunResult> = emptyList()

    override suspend fun connect(connectionId: String): String {
        if (connectionId != "drive") return "Signing in to the platforms on the phone comes in the next part of step 7."
        val a = activity ?: return "Open the app to sign in."
        return try {
            drive.interactiveToken(a)
            repo.putSetting(DRIVE_ALLOWED, "true")
            "Google Drive is connected. Syncing..." + "\n" + syncNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveAuthorizer.NeedsConsent) {
            "Google Drive wasn't allowed. Try again and choose Allow."
        } catch (e: Exception) {
            "Google Drive sign-in failed (${e::class.simpleName})."
        }
    }

    /** The background sync (WorkManager): no screens; skips quietly until Drive is allowed. */
    suspend fun backgroundSync(): Boolean {
        if (!driveConnected) return true
        return try {
            drive.silentToken()
            val r = withContext(Dispatchers.IO) { sync.merge() }
            r.errors.isEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun syncNow(): String =
        withContext(Dispatchers.IO) {
            if (!driveConnected) return@withContext "Connect Google Drive first."
            try {
                sync.registerDevice("Android: ${Build.MANUFACTURER} ${Build.MODEL}", "android", now())
                val r = sync.merge()
                "Read ${r.filesRead} new files from Drive: ${r.snapshotsAdded} snapshots and ${r.eventsAdded} " +
                    "edits added." +
                    if (r.errors.isEmpty()) "" else " Problems: ${r.errors.joinToString("; ")}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Sync failed (${e::class.simpleName}). Local data is unchanged."
            }
        }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun confirmMatch(refs: List<PostRef>): String =
        withContext(Dispatchers.IO) {
            val now = now()
            repo.insertEvents(
                Matching.confirm(refs, "v-${Uuid.random()}", repo.deviceId(), now) { Uuid.random().toString() },
            )
            val published =
                driveConnected &&
                    runCatching { sync.publishEvents(now.toLocalDateTime(timeZone).date) }.isSuccess
            "Matched ${refs.joinToString()}." +
                if (published) {
                    ""
                } else {
                    " Saved on this phone; it syncs once " +
                        "Drive is reachable."
                }
        }

    override suspend fun export(format: ExportFormat): String? {
        val launch = exportLauncher ?: return "Open the app to export."
        val ext = if (format == ExportFormat.JSON) "json" else "csv"
        val target = CompletableDeferred<Uri?>()
        exportTarget = target
        withContext(Dispatchers.Main) { launch("foursight-export.$ext") }
        val uri = target.await() ?: return null
        return withContext(Dispatchers.IO) {
            val body =
                when (format) {
                    ExportFormat.JSON -> Exporter.json(queries, SocialPlatform.entries.toSet(), now())
                    ExportFormat.CSV -> Exporter.csv(queries, SocialPlatform.entries.toSet())
                }
            context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            "Exported."
        }
    }

    /** Called by the Activity with the file the save dialog picked (null when cancelled). */
    fun onExportTarget(uri: Uri?) {
        exportTarget?.complete(uri)
        exportTarget = null
    }

    override fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun setting(key: String): String? = repo.setting(key)

    override fun putSetting(
        key: String,
        value: String,
    ) {
        repo.putSetting(key, value)
    }

    override fun vaultStatus(): VaultStatus? = null

    override fun setVault(path: String): String = "The Obsidian writer runs on Windows only."

    companion object {
        const val DRIVE_ALLOWED = "android.drive_allowed"
    }
}
