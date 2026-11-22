package io.github.rmac0001.foursight.desktop

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.auth.SignInFlow
import io.github.rmac0001.foursight.core.collect.CollectionRunner
import io.github.rmac0001.foursight.core.collect.PlatformCollector
import io.github.rmac0001.foursight.core.platform.google.GoogleAuth
import io.github.rmac0001.foursight.core.platform.google.GoogleGrant
import io.github.rmac0001.foursight.core.platform.meta.FacebookCollector
import io.github.rmac0001.foursight.core.platform.meta.InstagramCollector
import io.github.rmac0001.foursight.core.platform.meta.MetaAuth
import io.github.rmac0001.foursight.core.platform.meta.MetaGraph
import io.github.rmac0001.foursight.core.platform.tiktok.TikTokApi
import io.github.rmac0001.foursight.core.platform.tiktok.TikTokAuth
import io.github.rmac0001.foursight.core.platform.tiktok.TikTokCollector
import io.github.rmac0001.foursight.core.platform.youtube.YouTubeApi
import io.github.rmac0001.foursight.core.platform.youtube.YouTubeCollector
import io.github.rmac0001.foursight.core.query.QueryService
import io.github.rmac0001.foursight.core.secrets.SecretStore
import io.github.rmac0001.foursight.core.secrets.WindowsCredentialStore
import io.github.rmac0001.foursight.core.store.DesktopDatabase
import io.github.rmac0001.foursight.core.store.Repository
import io.github.rmac0001.foursight.core.sync.DriveSyncStore
import io.github.rmac0001.foursight.core.sync.SyncEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * Wires the core services for the Windows app, CLI and MCP server. One instance per process. Tests
 * pass an in-memory [repo] and secret store.
 */
class DesktopServices(
    val repo: Repository = Repository(DesktopDatabase.open(DesktopDatabase.defaultPath())),
    val secrets: SecretStore = WindowsCredentialStore(),
) : AutoCloseable {
    val http =
        HttpClient(Java) {
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 20_000
            }
            // No logging plugin: request logs would contain tokens.
        }
    val queries = QueryService(repo)
    val timeZone = TimeZone.currentSystemDefault()

    private val tiktokApi = TikTokApi(http)
    private val tiktokAuth = TikTokAuth(tiktokApi, secrets)
    private val youtubeAuth = GoogleAuth(http, secrets, GoogleGrant.YOUTUBE)
    private val driveAuth = GoogleAuth(http, secrets, GoogleGrant.DRIVE)
    private val metaGraph = MetaGraph(http)
    private val metaAuth = MetaAuth(metaGraph, secrets)

    val collectors: List<PlatformCollector> =
        listOf(
            TikTokCollector(tiktokApi, tiktokAuth),
            YouTubeCollector(YouTubeApi(http), youtubeAuth),
            FacebookCollector(metaGraph, metaAuth),
            InstagramCollector(metaGraph, metaAuth),
        )

    /** Sign-ins by command-line name. Drive sync is its own Google sign-in. */
    val signInFlows: Map<String, SignInFlow> =
        listOf(tiktokAuth, youtubeAuth, driveAuth, metaAuth).associateBy { it.connection }

    val sync = SyncEngine(DriveSyncStore(http, { driveAuth.accessToken(Clock.System.now()) }), repo, timeZone)
    private var deviceRegistered = false

    val driveConnected: Boolean get() = driveAuth.isConnected

    /**
     * After a platform is stored, publish this device's snapshot file for the day to Drive (spec:
     * Sync). Needs the Drive sign-in; without it, data stays local until Drive is connected.
     */
    private suspend fun publish(
        platform: SocialPlatform,
        day: LocalDate,
    ) {
        if (!driveAuth.isConnected) return
        if (!deviceRegistered) {
            sync.registerDevice(System.getenv("COMPUTERNAME") ?: "Windows PC", "windows", Clock.System.now())
            deviceRegistered = true
        }
        sync.publishSnapshots(platform, day)
    }

    val runner = CollectionRunner(repo, collectors, timeZone, { Clock.System.now() }, ::publish)

    override fun close() = http.close()
}
