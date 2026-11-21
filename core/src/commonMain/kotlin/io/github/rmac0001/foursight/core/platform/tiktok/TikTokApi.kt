package io.github.rmac0001.foursight.core.platform.tiktok

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.collect.CollectionException
import io.github.rmac0001.foursight.core.collect.FailureKind
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A token response from `/v2/oauth/token/`. Never logged, stored in the database or synced. */
class TikTokTokens(
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String,
    val refreshExpiresInSeconds: Long,
    val openId: String?,
    val scopes: Set<String>,
) {
    override fun toString() = "TikTokTokens(openId=$openId, scopes=$scopes)"
}

/** One page of `/v2/video/list/`. */
data class TikTokVideoPage(
    val videos: List<JsonObject>,
    val cursor: Long?,
    val hasMore: Boolean,
)

/**
 * TikTok's Login Kit and Display API, checked against the live reference on November 21, 2026:
 * - Authorize: `https://www.tiktok.com/v2/auth/authorize/` (Login Kit for Desktop, PKCE S256 with a
 *   hex-encoded challenge).
 * - Token: `POST https://open.tiktokapis.com/v2/oauth/token/`, form-encoded; grants
 *   `authorization_code` (with `code_verifier` on desktop) and `refresh_token`. The refresh token
 *   rotates: each refresh returns a new one and invalidates the old one. Errors are
 *   `{error, error_description, log_id}`.
 * - `GET /v2/user/info/?fields=...` and `POST /v2/video/list/?fields=...` (body `cursor`,
 *   `max_count` up to 20). Errors are `{error: {code, message, log_id}}` with `code` "ok" on success.
 *
 * No method includes a token, secret or authorization code in an exception message.
 */
class TikTokApi(
    private val http: HttpClient,
) {
    fun authorizeUrl(
        clientKey: String,
        scopes: List<String>,
        redirectUri: String,
        state: String,
        codeChallenge: String,
    ): String =
        URLBuilder(AUTHORIZE_URL)
            .apply {
                parameters.append("client_key", clientKey)
                parameters.append("response_type", "code")
                parameters.append("scope", scopes.joinToString(","))
                parameters.append("redirect_uri", redirectUri)
                parameters.append("state", state)
                parameters.append("code_challenge", codeChallenge)
                parameters.append("code_challenge_method", "S256")
            }.buildString()

    suspend fun exchangeCode(
        clientKey: String,
        clientSecret: String,
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): TikTokTokens =
        tokenRequest("sign-in") {
            append("client_key", clientKey)
            append("client_secret", clientSecret)
            append("code", code)
            append("grant_type", "authorization_code")
            append("redirect_uri", redirectUri)
            append("code_verifier", codeVerifier)
        }

    suspend fun refresh(
        clientKey: String,
        clientSecret: String,
        refreshToken: String,
    ): TikTokTokens =
        tokenRequest("token refresh") {
            append("client_key", clientKey)
            append("client_secret", clientSecret)
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
        }

    /** The `data.user` object of `/v2/user/info/`. */
    suspend fun userInfo(accessToken: String): JsonObject {
        val body =
            call("user info") {
                http.get("$API/user/info/") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    parameter("fields", USER_FIELDS.joinToString(","))
                }
            }
        return body.objectAt("data")?.objectAt("user") ?: badResponse("user info has no data.user")
    }

    suspend fun listVideos(
        accessToken: String,
        cursor: Long?,
        maxCount: Int = MAX_PAGE_SIZE,
    ): TikTokVideoPage {
        val body =
            call("video list") {
                http.post("$API/video/list/") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    parameter("fields", VIDEO_FIELDS.joinToString(","))
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("max_count", maxCount)
                            if (cursor != null) put("cursor", cursor)
                        }.toString(),
                    )
                }
            }
        val data = body.objectAt("data") ?: badResponse("video list has no data")
        val videos =
            when (val v = data["videos"]) {
                null -> emptyList()
                is JsonArray -> v.map { it as? JsonObject ?: badResponse("video list entry is not an object") }
                else -> badResponse("video list data.videos is not a list")
            }
        return TikTokVideoPage(
            videos = videos,
            cursor = (data["cursor"] as? JsonPrimitive)?.longOrNull,
            hasMore = (data["has_more"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    private suspend fun tokenRequest(
        what: String,
        form: io.ktor.http.ParametersBuilder.() -> Unit,
    ): TikTokTokens {
        val response =
            network(what) {
                http.submitForm(url = TOKEN_URL, formParameters = parameters(form))
            }
        val body = parse(what, response)
        val error = body.string("error")
        val accessToken = body.string("access_token")
        if (!error.isNullOrEmpty() || accessToken.isNullOrEmpty()) {
            val code = error ?: "http_${response.status.value}"
            val kind =
                when {
                    code == "invalid_client" -> FailureKind.MISSING_CLIENT_KEYS
                    what == "token refresh" -> FailureKind.SIGN_IN_EXPIRED
                    else -> FailureKind.PLATFORM_ERROR
                }
            val description = body.string("error_description")?.take(200)
            throw CollectionException(
                SocialPlatform.TIKTOK,
                kind,
                "$what failed: $code" + (description?.let { " ($it)" } ?: ""),
                logId = body.string("log_id"),
            )
        }
        return TikTokTokens(
            accessToken = accessToken,
            expiresInSeconds = body.long("expires_in") ?: badResponse("token response has no expires_in"),
            refreshToken = body.string("refresh_token") ?: badResponse("token response has no refresh_token"),
            refreshExpiresInSeconds =
                body.long("refresh_expires_in") ?: badResponse("token response has no refresh_expires_in"),
            openId = body.string("open_id"),
            scopes =
                body
                    .string("scope")
                    .orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet(),
        )
    }

    /** Runs an API call and turns TikTok's `error` object into a [CollectionException]. */
    private suspend fun call(
        what: String,
        request: suspend () -> HttpResponse,
    ): JsonObject {
        val response = network(what, request)
        val body = parse(what, response)
        val error = body.objectAt("error")
        val code = error?.string("code")
        if (code != null && code != "ok") {
            val kind =
                when (code) {
                    "access_token_invalid" -> FailureKind.SIGN_IN_EXPIRED
                    "scope_not_authorized", "scope_permission_missed" -> FailureKind.SCOPE_MISSING
                    "rate_limit_exceeded" -> FailureKind.RATE_LIMITED
                    else -> FailureKind.PLATFORM_ERROR
                }
            val message = error.string("message")?.take(200)
            throw CollectionException(
                SocialPlatform.TIKTOK,
                kind,
                "$what failed: $code" + (message?.let { " ($it)" } ?: ""),
                logId = error.string("log_id"),
            )
        }
        if (response.status.value !in 200..299) {
            throw CollectionException(
                SocialPlatform.TIKTOK,
                if (response.status.value == 429) FailureKind.RATE_LIMITED else FailureKind.PLATFORM_ERROR,
                "$what failed: HTTP ${response.status.value}",
            )
        }
        return body
    }

    private suspend fun network(
        what: String,
        request: suspend () -> HttpResponse,
    ): HttpResponse =
        try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: CollectionException) {
            throw e
        } catch (e: Exception) {
            // The exception class only: Ktor messages can include the request URL.
            throw CollectionException(SocialPlatform.TIKTOK, FailureKind.NETWORK, "$what: ${e::class.simpleName}")
        }

    private suspend fun parse(
        what: String,
        response: HttpResponse,
    ): JsonObject {
        val text = response.bodyAsText()
        return try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            throw CollectionException(
                SocialPlatform.TIKTOK,
                if (response.status.value >= 500) FailureKind.PLATFORM_ERROR else FailureKind.BAD_RESPONSE,
                "$what: HTTP ${response.status.value} with a body that isn't JSON",
                cause = e,
            )
        }
    }

    private fun badResponse(detail: String): Nothing =
        throw CollectionException(SocialPlatform.TIKTOK, FailureKind.BAD_RESPONSE, detail)

    companion object {
        const val AUTHORIZE_URL = "https://www.tiktok.com/v2/auth/authorize/"
        const val TOKEN_URL = "https://open.tiktokapis.com/v2/oauth/token/"
        const val API = "https://open.tiktokapis.com/v2"
        const val MAX_PAGE_SIZE = 20

        /** Read-only scopes. Posting scopes are requested when posting is built (step 8). */
        val READ_SCOPES = listOf("user.info.basic", "user.info.stats", "video.list")

        /** `follower_count` and the other counts need `user.info.stats`. */
        val USER_FIELDS =
            listOf("open_id", "display_name", "follower_count", "following_count", "likes_count", "video_count")

        val VIDEO_FIELDS =
            listOf(
                "id",
                "create_time",
                "title",
                "video_description",
                "duration",
                "cover_image_url",
                "share_url",
                "embed_link",
                "view_count",
                "like_count",
                "comment_count",
                "share_count",
            )

        private val json = Json { ignoreUnknownKeys = true }
    }
}

internal fun JsonObject.objectAt(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.takeIf {
            it.isString
        }?.contentOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
