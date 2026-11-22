package io.github.rmac0001.foursight.core.platform.meta

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
import io.ktor.http.URLBuilder
import kotlin.time.Instant

/**
 * Meta sign-in: one Facebook Login for Business sign-in covers the Facebook Page and its linked
 * Instagram account. Checked against Meta's manual login flow, Login for Business and long-lived
 * token pages on November 22, 2026:
 * - Dialog `https://www.facebook.com/v27.0/dialog/oauth` with `client_id`, `redirect_uri`, `state`,
 *   `response_type=code` and `config_id` (the configuration chooses the permissions; Meta advises
 *   against also sending `scope`). The flow has no PKCE, so the `state` check matters.
 * - Code exchange `GET /oauth/access_token` (client id and secret, redirect URI, code) gives a
 *   short-lived user token; `grant_type=fb_exchange_token` turns it into a long-lived one (about 60
 *   days); `/me/accounts` with that gives the Page's token, which doesn't expire.
 *
 * Only the Page token is stored. The user tokens live in memory during sign-in.
 */
class MetaAuth(
    private val graph: MetaGraph,
    private val secrets: SecretStore,
) : SignInFlow {
    override val connection = "meta"
    override val displayName = "Facebook and Instagram"
    override val platform = SocialPlatform.FACEBOOK
    override val loopbackPath = "/"
    override val loopbackHost = "localhost"

    override fun begin(redirectUri: String): SignInRequest {
        val (appId, _) = appKeys()
        val configId =
            secrets.read(SecretName.META_LOGIN_CONFIG_ID)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw missing(listOf(SecretName.META_LOGIN_CONFIG_ID.target))
        val state = Pkce.newState()
        val url =
            URLBuilder("https://www.facebook.com/${MetaGraph.VERSION}/dialog/oauth")
                .apply {
                    parameters.append("client_id", appId)
                    parameters.append("redirect_uri", redirectUri)
                    parameters.append("state", state)
                    parameters.append("response_type", "code")
                    parameters.append("config_id", configId)
                }.buildString()
        // No PKCE in Meta's flow; the verifier slot is unused.
        return SignInRequest(connection, url, redirectUri, state, verifier = "")
    }

    override suspend fun complete(
        request: SignInRequest,
        callback: Map<String, String>,
        now: Instant,
    ): SignInResult {
        callback["error"]?.let { error ->
            val description = (callback["error_description"] ?: callback["error_reason"])?.take(200)
            throw SignInException("Facebook sign-in was not completed: $error" + (description?.let { " ($it)" } ?: ""))
        }
        if (callback["state"] != request.state) {
            throw SignInException("The sign-in response didn't match this sign-in (state check failed). Try again.")
        }
        val code = callback["code"]?.takeIf { it.isNotEmpty() } ?: throw SignInException("Facebook returned no code.")
        val (appId, appSecret) = appKeys()
        val p = SocialPlatform.FACEBOOK

        val short =
            graph.get(
                p,
                "sign-in",
                "oauth/access_token",
                token = null,
                params =
                    mapOf(
                        "client_id" to appId,
                        "client_secret" to appSecret,
                        "redirect_uri" to request.redirectUri,
                        "code" to code,
                    ),
            )
        val shortToken = short.str("access_token") ?: throw SignInException("Facebook returned no access token.")
        val long =
            graph.get(
                p,
                "long-lived token",
                "oauth/access_token",
                token = null,
                params =
                    mapOf(
                        "grant_type" to "fb_exchange_token",
                        "client_id" to appId,
                        "client_secret" to appSecret,
                        "fb_exchange_token" to shortToken,
                    ),
            )
        val userToken = long.str("access_token") ?: throw SignInException("Facebook returned no long-lived token.")

        val pages =
            graph
                .get(
                    p,
                    "Page list",
                    "me/accounts",
                    userToken,
                    mapOf("fields" to "id,name,access_token,instagram_business_account"),
                ).list("data")
        val page =
            when {
                pages.isEmpty() -> {
                    throw SignInException("This Facebook account manages no Pages that FourSight was given.")
                }

                pages.size == 1 -> {
                    pages.single()
                }

                else -> {
                    pages.singleOrNull { it.obj("instagram_business_account") != null }
                        ?: throw SignInException(
                            "More than one Page was shared (${pages.mapNotNull { it.str("name") }.joinToString()}). " +
                                "Sign in again and choose only the One Fat Man's Journey Page.",
                        )
                }
            }
        val pageToken = page.str("access_token") ?: throw SignInException("Facebook returned no Page token.")
        secrets.write(SecretName.META_PAGE_TOKEN, pageToken)

        val granted =
            graph
                .get(p, "permissions", "me/permissions", userToken)
                .list("data")
                .filter { it.str("status") == "granted" }
                .mapNotNull { it.str("permission") }
                .toSet()
        val hasInstagram = page.obj("instagram_business_account") != null
        val wanted = READ_PERMISSIONS.toSet()
        return SignInResult(
            connection = connection,
            grantedScopes = granted + if (hasInstagram) setOf("(Instagram account linked)") else emptySet(),
            missingScopes =
                (wanted - granted) +
                    if (hasInstagram) emptySet() else setOf("(no Instagram account linked to the Page)"),
            signInExpiresAt = null,
        )
    }

    /** The stored Page token; it doesn't expire, but can be revoked (then collection reports it). */
    fun pageToken(platform: SocialPlatform): String =
        secrets.read(SecretName.META_PAGE_TOKEN)?.takeIf { it.isNotEmpty() }
            ?: throw CollectionException(
                platform,
                FailureKind.NOT_CONNECTED,
                "not signed in (run `foursight connect meta`)",
            )

    private fun appKeys(): Pair<String, String> {
        val id = secrets.read(SecretName.META_APP_ID)?.trim()
        val secret = secrets.read(SecretName.META_APP_SECRET)?.trim()
        if (id.isNullOrEmpty() || secret.isNullOrEmpty()) {
            throw missing(
                listOfNotNull(
                    SecretName.META_APP_ID.target.takeIf { id.isNullOrEmpty() },
                    SecretName.META_APP_SECRET.target.takeIf { secret.isNullOrEmpty() },
                ),
            )
        }
        return id to secret
    }

    private fun missing(targets: List<String>) =
        CollectionException(
            SocialPlatform.FACEBOOK,
            FailureKind.MISSING_CLIENT_KEYS,
            "missing Credential Manager entries: ${targets.joinToString()}",
        )

    companion object {
        /** What reading needs, per the reference pages; posting permissions come with step 8. */
        val READ_PERMISSIONS =
            listOf(
                "pages_show_list",
                "pages_read_engagement",
                "read_insights",
                "instagram_basic",
                "instagram_manage_insights",
            )
    }
}
