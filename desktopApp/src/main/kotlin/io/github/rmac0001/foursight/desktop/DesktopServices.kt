package io.github.rmac0001.foursight.desktop

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.auth.SignInFlow
import io.github.rmac0001.foursight.core.collect.CollectionRunner
import io.github.rmac0001.foursight.core.collect.PlatformCollector
import io.github.rmac0001.foursight.core.platform.google.GoogleAuth
import io.github.rmac0001.foursight.core.platform.tiktok.TikTokApi
import io.github.rmac0001.foursight.core.platform.tiktok.TikTokAuth
import io.github.rmac0001.foursight.core.platform.tiktok.TikTokCollector
import io.github.rmac0001.foursight.core.platform.youtube.YouTubeApi
import io.github.rmac0001.foursight.core.platform.youtube.YouTubeCollector
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

/** Wires the core services for the Windows app and CLI. One instance per process. */
class DesktopServices : AutoCloseable {
    val http =
        HttpClient(Java) {
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 20_000
            }
            // No logging plugin: request logs would contain tokens.
        }
    val secrets: SecretStore = WindowsCredentialStore()
    val repo = Repository(DesktopDatabase.open(DesktopDatabase.defaultPath()))
    val timeZone = TimeZone.currentSystemDefault()

    private val tiktokApi = TikTokApi(http)
    private val tiktokAuth = TikTokAuth(tiktokApi, secrets)
    private val googleAuth = GoogleAuth(http, secrets)

    val collectors: List<PlatformCollector> =
        listOf(
            TikTokCollector(tiktokApi, tiktokAuth),
            YouTubeCollector(YouTubeApi(http), googleAuth),
        )

    val signInFlows: Map<SocialPlatform, SignInFlow> =
        mapOf(SocialPlatform.TIKTOK to tiktokAuth, SocialPlatform.YOUTUBE to googleAuth)

    private val sync = SyncEngine(DriveSyncStore(http, { googleAuth.accessToken(Clock.System.now()) }), repo, timeZone)
    private var deviceRegistered = false

    /**
     * After a platform is stored, publish this device's snapshot file for the day to Drive (spec:
     * Sync). Needs the Google sign-in; without it, data stays local until Google is connected.
     */
    private suspend fun publish(
        platform: SocialPlatform,
        day: LocalDate,
    ) {
        if (!googleAuth.isConnected) return
        if (!deviceRegistered) {
            sync.registerDevice(System.getenv("COMPUTERNAME") ?: "Windows PC", "windows", Clock.System.now())
            deviceRegistered = true
        }
        sync.publishSnapshots(platform, day)
    }

    val runner = CollectionRunner(repo, collectors, timeZone, { Clock.System.now() }, ::publish)

    override fun close() = http.close()
}
