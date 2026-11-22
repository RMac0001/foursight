package io.github.rmac0001.foursight.core.auth

import io.github.rmac0001.foursight.core.SocialPlatform
import kotlin.time.Instant

/**
 * A sign-in in progress. [verifier] and [state] stay in memory for the length of the sign-in and are
 * never stored or printed; [authorizeUrl] is what the browser opens.
 */
class SignInRequest(
    val platform: SocialPlatform,
    val authorizeUrl: String,
    val redirectUri: String,
    internal val state: String,
    internal val verifier: String,
) {
    override fun toString() = "SignInRequest(platform=$platform, redirectUri=$redirectUri)"
}

/** The outcome of a finished sign-in. Tokens are already in the [SecretStore]; none are here. */
data class SignInResult(
    val platform: SocialPlatform,
    val grantedScopes: Set<String>,
    val missingScopes: Set<String>,
    /** When the stored refresh token expires, so a new sign-in is needed. */
    val signInExpiresAt: Instant?,
)

/**
 * The OAuth authorization-code flow for one platform. The device shell (desktop loopback server,
 * Android App Link) receives the callback and passes its query parameters to [complete].
 */
interface SignInFlow {
    val platform: SocialPlatform

    /**
     * The path the desktop loopback listener serves: TikTok's registered redirect ends in
     * `/callback/`; Google's loopback redirect is the bare `http://127.0.0.1:<port>` (path `/`).
     */
    val loopbackPath: String

    /** [redirectUri] is the exact callback URL the shell is listening on. */
    fun begin(redirectUri: String): SignInRequest

    /** Checks `state`, exchanges the `code` and stores the refresh token. */
    suspend fun complete(
        request: SignInRequest,
        callback: Map<String, String>,
        now: Instant,
    ): SignInResult
}

/** The callback was refused, cancelled, or didn't match the request. */
class SignInException(
    message: String,
) : Exception(message)
