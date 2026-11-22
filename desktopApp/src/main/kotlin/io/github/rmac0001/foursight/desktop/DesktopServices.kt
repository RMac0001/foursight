package io.github.rmac0001.foursight.desktop

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.auth.SignInFlow
import io.github.rmac0001.foursight.core.collect.CollectionRunner
import io.github.rmac0001.foursight.core.collect.PlatformCollector
import io.github.rmac0001.foursight.core.platform.tiktok.TikTokApi
import io.github.rmac0001.foursight.core.platform.tiktok.TikTokAuth
import io.github.rmac0001.foursight.core.platform.tiktok.TikTokCollector
import io.github.rmac0001.foursight.core.secrets.SecretStore
import io.github.rmac0001.foursight.core.secrets.WindowsCredentialStore
import io.github.rmac0001.foursight.core.store.DesktopDatabase
import io.github.rmac0001.foursight.core.store.Repository
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
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

    /** Platforms with a collector so far (step 3 builds them TikTok first). */
    val collectors: List<PlatformCollector> = listOf(TikTokCollector(tiktokApi, tiktokAuth))

    val signInFlows: Map<SocialPlatform, SignInFlow> = mapOf(SocialPlatform.TIKTOK to tiktokAuth)

    // Drive publishing joins once the Google sign-in exists (YouTube collector).
    val runner = CollectionRunner(repo, collectors, timeZone, { Clock.System.now() })

    override fun close() = http.close()
}
