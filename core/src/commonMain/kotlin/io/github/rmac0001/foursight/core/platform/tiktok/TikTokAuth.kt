package io.github.rmac0001.foursight.core.platform.tiktok

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.auth.Pkce
import io.github.rmac0001.foursight.core.auth.SignInException
import io.github.rmac0001.foursight.core.auth.SignInFlow
import io.github.rmac0001.foursight.core.auth.SignInRequest
import io.github.rmac0001.foursight.core.auth.SignInResult
import io.github.rmac0001.foursight.core.collect.CollectionException
import io.github.rmac0001.foursight.core.collect.FailureKind
import io.github.rmac0001.foursight.core.secrets.SecretName
import io.github.rmac0001.foursight.core.secrets.SecretStore
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A short-lived access token, held in memory for one collection run and never stored. */
class AccessToken(
    val value: String,
    val expiresAt: Instant,
    val scopes: Set<String>,
    /** When the (rotated) refresh token expires. */
    val signInExpiresAt: Instant,
) {
    override fun toString() = "AccessToken(expiresAt=$expiresAt, scopes=$scopes)"
}

/**
 * TikTok sign-in and token renewal. The client key and secret are entered by Roger in the
 * [SecretStore]; the refresh token is written there after sign-in and **rewritten after every
 * refresh**, because TikTok invalidates the old one as soon as it issues a new one.
 */
class TikTokAuth(
    private val api: TikTokApi,
    private val secrets: SecretStore,
) : SignInFlow {
    override val platform = SocialPlatform.TIKTOK

    override fun begin(redirectUri: String): SignInRequest {
        val (clientKey, _) = clientKeys()
        val verifier = Pkce.newVerifier()
        val state = Pkce.newState()
        val url =
            api.authorizeUrl(
                clientKey = clientKey,
                scopes = TikTokApi.READ_SCOPES,
                redirectUri = redirectUri,
                state = state,
                codeChallenge = Pkce.tiktokChallenge(verifier),
            )
        return SignInRequest(platform, url, redirectUri, state, verifier)
    }

    override suspend fun complete(
        request: SignInRequest,
        callback: Map<String, String>,
        now: Instant,
    ): SignInResult {
        callback["error"]?.let { error ->
            val description = callback["error_description"]?.take(200)
            throw SignInException("TikTok sign-in was not completed: $error" + (description?.let { " ($it)" } ?: ""))
        }
        if (callback["state"] != request.state) {
            throw SignInException("The sign-in response didn't match this sign-in (state check failed). Try again.")
        }
        val code = callback["code"]?.takeIf { it.isNotEmpty() } ?: throw SignInException("TikTok returned no code.")
        val (clientKey, clientSecret) = clientKeys()
        val tokens = api.exchangeCode(clientKey, clientSecret, code, request.redirectUri, request.verifier)
        secrets.write(SecretName.TIKTOK_REFRESH_TOKEN, tokens.refreshToken)
        return SignInResult(
            platform = platform,
            grantedScopes = tokens.scopes,
            missingScopes = TikTokApi.READ_SCOPES.toSet() - tokens.scopes,
            signInExpiresAt = now + tokens.refreshExpiresInSeconds.seconds,
        )
    }

    /** Exchanges the stored refresh token for an access token, storing the rotated refresh token. */
    suspend fun accessToken(now: Instant): AccessToken {
        val refreshToken =
            secrets.read(SecretName.TIKTOK_REFRESH_TOKEN)?.takeIf { it.isNotEmpty() }
                ?: throw CollectionException(platform, FailureKind.NOT_CONNECTED, "not signed in")
        val (clientKey, clientSecret) = clientKeys()
        val tokens = api.refresh(clientKey, clientSecret, refreshToken)
        // Write first: the old refresh token is already invalid.
        secrets.write(SecretName.TIKTOK_REFRESH_TOKEN, tokens.refreshToken)
        return AccessToken(
            value = tokens.accessToken,
            expiresAt = now + tokens.expiresInSeconds.seconds,
            scopes = tokens.scopes,
            signInExpiresAt = now + tokens.refreshExpiresInSeconds.seconds,
        )
    }

    private fun clientKeys(): Pair<String, String> {
        val key = secrets.read(SecretName.TIKTOK_CLIENT_KEY)?.trim()
        val secret = secrets.read(SecretName.TIKTOK_CLIENT_SECRET)?.trim()
        if (key.isNullOrEmpty() || secret.isNullOrEmpty()) {
            val missing =
                listOfNotNull(
                    SecretName.TIKTOK_CLIENT_KEY.target.takeIf { key.isNullOrEmpty() },
                    SecretName.TIKTOK_CLIENT_SECRET.target.takeIf { secret.isNullOrEmpty() },
                )
            throw CollectionException(
                platform,
                FailureKind.MISSING_CLIENT_KEYS,
                "missing Credential Manager entries: ${missing.joinToString()}",
            )
        }
        return key to secret
    }
}
