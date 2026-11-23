package io.github.rmac0001.foursight.desktop

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.collect.PlatformRunResult
import io.github.rmac0001.foursight.core.model.PostRef
import io.github.rmac0001.foursight.core.query.ExportFormat
import io.github.rmac0001.foursight.core.query.Exporter
import io.github.rmac0001.foursight.core.query.Matching
import io.github.rmac0001.foursight.core.query.QueryService
import io.github.rmac0001.foursight.desktop.auth.signIn
import io.github.rmac0001.foursight.desktop.obsidian.ObsidianSettings
import io.github.rmac0001.foursight.ui.AppServices
import io.github.rmac0001.foursight.ui.BrandFonts
import io.github.rmac0001.foursight.ui.Connection
import io.github.rmac0001.foursight.ui.VaultStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The Windows app's [AppServices]: the same engine as the CLI and MCP server. */
class DesktopAppServices(
    private val services: DesktopServices,
) : AppServices {
    override val queries: QueryService get() = services.queries
    override val timeZone: TimeZone get() = services.timeZone

    override fun now(): Instant = Clock.System.now()

    override fun connections(): List<Connection> {
        val collected = services.queries.freshness().associateBy { it.platform }
        return listOf(
            Connection(
                "tiktok",
                "TikTok",
                setOf(SocialPlatform.TIKTOK),
                collected[SocialPlatform.TIKTOK]?.status?.lastSuccessAt != null,
            ),
            Connection(
                "youtube",
                "YouTube",
                setOf(SocialPlatform.YOUTUBE),
                collected[SocialPlatform.YOUTUBE]?.status?.lastSuccessAt != null,
            ),
            Connection(
                "meta",
                "Meta (Facebook and Instagram)",
                setOf(SocialPlatform.FACEBOOK, SocialPlatform.INSTAGRAM),
                collected[SocialPlatform.FACEBOOK]?.status?.lastSuccessAt != null,
            ),
            Connection("drive", "Google Drive (sync)", emptySet(), services.driveConnected),
        )
    }

    override suspend fun refresh(platforms: Set<SocialPlatform>?): List<PlatformRunResult> =
        withContext(Dispatchers.IO) {
            val results = services.runner.run(platforms)
            if (results.any { it.ok }) runCatching { services.afterCollection(Clock.System.now()) }
            results
        }

    override suspend fun connect(connectionId: String): String =
        withContext(Dispatchers.IO) {
            val lines = mutableListOf<String>()
            signIn(services, connectionId, { lines += it }, { lines += it })
            lines.filterNot { it.startsWith("Opening") || it.startsWith("Waiting") }.joinToString("\n")
        }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun confirmMatch(refs: List<PostRef>): String =
        withContext(Dispatchers.IO) {
            val now = Clock.System.now()
            val events =
                Matching.confirm(
                    refs,
                    "v-${Uuid.random()}",
                    services.repo.deviceId(),
                    now,
                ) { Uuid.random().toString() }
            services.repo.insertEvents(events)
            val note = services.publishEvents(now)
            "Matched ${refs.joinToString()}." + (note?.let { " $it" } ?: "")
        }

    override suspend fun export(format: ExportFormat): String? {
        val ext = if (format == ExportFormat.JSON) "json" else "csv"
        val file =
            withContext(Dispatchers.Main) {
                val dialog = FileDialog(null as Frame?, "Export FourSight data", FileDialog.SAVE)
                dialog.file = "foursight-export.$ext"
                dialog.isVisible = true
                val name = dialog.file ?: return@withContext null
                File(dialog.directory, if (name.endsWith(".$ext")) name else "$name.$ext")
            } ?: return null
        return withContext(Dispatchers.IO) {
            val body =
                when (format) {
                    ExportFormat.JSON -> {
                        Exporter.json(
                            services.queries,
                            SocialPlatform.entries.toSet(),
                            Clock.System.now(),
                        )
                    }

                    ExportFormat.CSV -> {
                        Exporter.csv(services.queries, SocialPlatform.entries.toSet())
                    }
                }
            file.writeText(body, Charsets.UTF_8)
            "Exported to ${file.absolutePath}"
        }
    }

    override fun openUrl(url: String) {
        runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url)) }
    }

    override fun setting(key: String): String? = services.repo.setting(key)

    override fun putSetting(
        key: String,
        value: String,
    ) {
        services.repo.putSetting(key, value)
    }

    override fun vaultStatus(): VaultStatus? =
        ObsidianSettings.load(services.repo)?.let { VaultStatus(it.vault.toString(), it.notesFolder, it.overviewNote) }

    override fun setVault(path: String): String {
        val dir = File(path)
        if (!dir.isDirectory ||
            !File(dir, ".obsidian").isDirectory
        ) {
            return "$path isn't an Obsidian vault (no .obsidian folder inside)."
        }
        services.repo.putSetting(ObsidianSettings.VAULT, dir.absolutePath)
        return "Vault saved. FourSight writes to it after each collection."
    }

    override val driveConnected: Boolean get() = services.driveConnected

    override val fonts: BrandFonts by lazy { windowsFonts() }

    override suspend fun syncNow(): String =
        withContext(Dispatchers.IO) {
            if (!services.driveConnected) return@withContext "Drive isn't connected. Sign in to Google Drive first."
            val r = services.sync.merge()
            "Read ${r.filesRead} new files from Drive: ${r.snapshotsAdded} snapshots and ${r.eventsAdded} " +
                "edits added." +
                if (r.errors.isEmpty()) "" else " Problems: ${r.errors.joinToString("; ")}"
        }
}

/**
 * Segoe UI, Segoe Fluent Icons and Cooper Black from the PC's own font folder (the Microsoft 365
 * look and Roger's Retro Sunset brand). Nothing is bundled; a missing font falls back to defaults.
 */
private fun windowsFonts(): BrandFonts {
    val dir = File(System.getenv("WINDIR") ?: "C:\\Windows", "Fonts")

    fun file(name: String) = File(dir, name).takeIf { it.isFile }
    val segoe = file("segoeui.ttf")
    val text =
        segoe?.let {
            FontFamily(
                listOfNotNull(
                    Font(it, FontWeight.Normal),
                    file("seguisb.ttf")?.let { f -> Font(f, FontWeight.SemiBold) },
                    file("segoeuib.ttf")?.let { f -> Font(f, FontWeight.Bold) },
                    file("segoeuil.ttf")?.let { f -> Font(f, FontWeight.Light) },
                ),
            )
        }
    val icons = (file("SegoeIcons.ttf") ?: file("segmdl2.ttf"))?.let { FontFamily(Font(it)) }
    val wordmark = file("COOPBL.TTF")?.let { FontFamily(Font(it)) }
    return BrandFonts(text, icons, wordmark)
}
