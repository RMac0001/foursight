package io.github.rmac0001.foursight.core.platform.google

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
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.formUrlEncode
import io.ktor.http.parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Google sign-in for the Desktop OAuth client, one [GoogleGrant] at a time. Google refuses YouTube
 * and Drive scopes in one consent (`invalid_request`: "scopes that cannot be requested together",
 * found live November 22, 2026), so YouTube and Drive sync are two sign-ins with the same client,
 * each with its own refresh token. Checked against "OAuth 2.0 for iOS & Desktop Apps" on
 * November 22, 2026:
 * - Authorize `https://accounts.google.com/o/oauth2/v2/auth` with PKCE S256 (base64url), `state`,
 *   space-separated scopes, `access_type=offline` and `prompt=consent` so a refresh token is
 *   always issued. `include_granted_scopes` is left off, so the other grant's scopes aren't added.
 * - Token `POST https://oauth2.googleapis.com/token`; the loopback redirect is
 *   `http://127.0.0.1:<port>`. A refresh returns no new refresh token, so the stored one is kept.
 * - Refresh tokens of a production-status app don't expire on a timer, but stop working after six
 *   months unused, on revocation, or past 100 live tokens per account and client.
 *
 * The access token is cached in memory for the process and never stored.
 */
/**
 * Where a Google access token comes from: the Windows sign-in ([GoogleAuth], with a stored refresh
 * token) or Google's Android AuthorizationClient (no stored refresh token). Collectors only need this.
 */
interface GoogleTokenSource {
    /** A current access token; throws a CollectionException (NOT_CONNECTED) when there's no sign-in. */
    suspend fun accessToken(now: Instant): String
}

class GoogleAuth(
    private val http: HttpClient,
    private val secrets: SecretStore,
    private val grant: GoogleGrant,
) : SignInFlow,
    GoogleTokenSource {
    override val connection = grant.connection
    override val displayName = grant.displayName
    override val platform = grant.platform
    override val loopbackPath = "/"
    override val loopbackRedirectSuffix = ""

    /** Errors are reported under YouTube, the platform Google sign-in belongs to. */
    private val reportAs = SocialPlatform.YOUTUBE

    private val lock = Mutex()
    private var cached: Pair<String, Instant>? = null

    val isConnected: Boolean get() = !secrets.read(grant.refreshSecret).isNullOrEmpty()

    override fun begin(redirectUri: String): SignInRequest {
        val (clientId, _) = clientKeys()
        val verifier = Pkce.newVerifier()
        val state = Pkce.newState()
        val url =
            URLBuilder(AUTHORIZE_URL)
                .apply {
                    parameters.append("client_id", clientId)
                    parameters.append("redirect_uri", redirectUri)
                    parameters.append("response_type", "code")
                    parameters.append("scope", grant.scopes.joinToString(" "))
                    parameters.append("code_challenge", Pkce.s256Challenge(verifier))
                    parameters.append("code_challenge_method", "S256")
                    parameters.append("state", state)
                    parameters.append("access_type", "offline")
                    parameters.append("prompt", "consent")
                }.buildString()
        return SignInRequest(connection, url, redirectUri, state, verifier)
    }

    override suspend fun complete(
        request: SignInRequest,
        callback: Map<String, String>,
        now: Instant,
    ): SignInResult {
        callback["error"]?.let { throw SignInException("Google sign-in was not completed: $it") }
        if (callback["state"] != request.state) {
            throw SignInException("The sign-in response didn't match this sign-in (state check failed). Try again.")
        }
        val code = callback["code"]?.takeIf { it.isNotEmpty() } ?: throw SignInException("Google returned no code.")
        val (clientId, clientSecret) = clientKeys()
        val body =
            tokenRequest("sign-in") {
                append("client_id", clientId)
                append("client_secret", clientSecret)
                append("code", code)
                append("code_verifier", request.verifier)
                append("grant_type", "authorization_code")
                append("redirect_uri", request.redirectUri)
            }
        val refresh = body.refreshToken ?: throw SignInException("Google returned no refresh token. Try again.")
        secrets.write(grant.refreshSecret, refresh)
        lock.withLock { cached = body.accessToken to now + body.expiresIn }
        return SignInResult(connection, body.scopes, grant.scopes.toSet() - body.scopes, signInExpiresAt = null)
    }

    /** A current access token, refreshed when it has under five minutes left. */
    override suspend fun accessToken(now: Instant): String =
        lock.withLock {
            cached?.let { (token, expires) -> if (expires - now > 5.minutes) return@withLock token }
            val refresh =
                secrets.read(grant.refreshSecret)?.takeIf { it.isNotEmpty() }
                    ?: throw CollectionException(
                        reportAs,
                        FailureKind.NOT_CONNECTED,
                        "not signed in to ${grant.displayName}",
                    )
            val (clientId, clientSecret) = clientKeys()
            val body =
                tokenRequest("token refresh") {
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                    append("grant_type", "refresh_token")
                    append("refresh_token", refresh)
                }
            // Google normally sends no new refresh token; store one if it ever does.
            body.refreshToken?.let { secrets.write(grant.refreshSecret, it) }
            val missing = grant.scopes.toSet() - body.scopes
            if (missing.isNotEmpty() && body.scopes.isNotEmpty()) {
                throw CollectionException(reportAs, FailureKind.SCOPE_MISSING, "not granted: ${missing.joinToString()}")
            }
            cached = body.accessToken to now + body.expiresIn
            body.accessToken
        }

    private class TokenBody(
        val accessToken: String,
        val expiresIn: kotlin.time.Duration,
        val refreshToken: String?,
        val scopes: Set<String>,
    )

    private suspend fun tokenRequest(
        what: String,
        form: ParametersBuilder.() -> Unit,
    ): TokenBody {
        val (status, body) =
            GoogleCalls.send(reportAs, what) {
                http.post(TOKEN_URL) {
                    setBody(
                        ByteArrayContent(
                            parameters(form).formUrlEncode().encodeToByteArray(),
                            ContentType.Application.FormUrlEncoded,
                        ),
                    )
                }
            }
        val error = body.str("error")
        val access = body.str("access_token")
        if (error != null || access == null || status !in 200..299) {
            val code = error ?: "http_$status"
            val kind =
                when {
                    code == "invalid_client" || code == "unauthorized_client" -> FailureKind.MISSING_CLIENT_KEYS
                    code == "invalid_grant" -> FailureKind.SIGN_IN_EXPIRED
                    status >= 500 -> FailureKind.PLATFORM_ERROR
                    else -> FailureKind.PLATFORM_ERROR
                }
            val description = body.str("error_description")?.take(200)
            throw CollectionException(
                reportAs,
                kind,
                "${grant.displayName} $what failed: $code" + (description?.let { " ($it)" } ?: ""),
            )
        }
        return TokenBody(
            accessToken = access,
            expiresIn = (body.num("expires_in") ?: 3600L).seconds,
            refreshToken = body.str("refresh_token"),
            scopes =
                body
                    .str("scope")
                    .orEmpty()
                    .split(' ')
                    .filter { it.isNotEmpty() }
                    .toSet(),
        )
    }

    private fun clientKeys(): Pair<String, String> {
        val id = secrets.read(SecretName.GOOGLE_CLIENT_ID)?.trim()
        val secret = secrets.read(SecretName.GOOGLE_CLIENT_SECRET)?.trim()
        if (id.isNullOrEmpty() || secret.isNullOrEmpty()) {
            val missing =
                listOfNotNull(
                    SecretName.GOOGLE_CLIENT_ID.target.takeIf { id.isNullOrEmpty() },
                    SecretName.GOOGLE_CLIENT_SECRET.target.takeIf { secret.isNullOrEmpty() },
                )
            throw CollectionException(
                reportAs,
                FailureKind.MISSING_CLIENT_KEYS,
                "missing Credential Manager entries: ${missing.joinToString()}",
            )
        }
        return id to secret
    }

    companion object {
        const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val YOUTUBE_READONLY = "https://www.googleapis.com/auth/youtube.readonly"
        const val ANALYTICS_READONLY = "https://www.googleapis.com/auth/yt-analytics.readonly"
        const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    }
}

/** One Google consent: its scopes and where its refresh token is kept. */
enum class GoogleGrant(
    val connection: String,
    val displayName: String,
    val platform: SocialPlatform?,
    val scopes: List<String>,
    val refreshSecret: SecretName,
) {
    /** Analytics now also needs youtube.readonly. `youtube.upload` comes with posting (step 8). */
    YOUTUBE(
        "youtube",
        "YouTube",
        SocialPlatform.YOUTUBE,
        listOf(GoogleAuth.YOUTUBE_READONLY, GoogleAuth.ANALYTICS_READONLY),
        SecretName.GOOGLE_YOUTUBE_REFRESH_TOKEN,
    ),
    DRIVE("drive", "Google Drive", null, listOf(GoogleAuth.DRIVE_FILE), SecretName.GOOGLE_DRIVE_REFRESH_TOKEN),
}
