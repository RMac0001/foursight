package io.github.rmac0001.foursight.core.platform.meta

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.collect.CollectionException
import io.github.rmac0001.foursight.core.collect.FailureKind
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Meta's Graph API (Facebook Pages and Instagram with Facebook Login), version [VERSION], the latest
 * on the reference pages checked November 22, 2026. Errors come back as `{"error": {"message",
 * "type", "code", "error_subcode", "fbtrace_id"}}`: 190 is an expired or revoked token, 10 and
 * 200-299 are permissions, 4, 17, 32, 613, 80001 and 80002 are rate limits.
 *
 * Tokens are sent in the Authorization header, never in URLs, and never appear in exceptions.
 */
class MetaGraph(
    private val http: HttpClient,
) {
    suspend fun get(
        platform: SocialPlatform,
        what: String,
        path: String,
        token: String?,
        params: Map<String, String> = emptyMap(),
    ): JsonObject = getUrl(platform, what, "$BASE/$path", token, params)

    /** Follows a `paging.next` URL. It may carry the token as a query parameter; it's never logged. */
    suspend fun getUrl(
        platform: SocialPlatform,
        what: String,
        url: String,
        token: String?,
        params: Map<String, String> = emptyMap(),
    ): JsonObject {
        val response =
            try {
                http.get(url) {
                    if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                    params.forEach { (k, v) -> parameter(k, v) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CollectionException(platform, FailureKind.NETWORK, "$what: ${e::class.simpleName}")
            }
        val status = response.status.value
        val body =
            try {
                json.parseToJsonElement(response.bodyAsText()).jsonObject
            } catch (e: IllegalArgumentException) {
                throw CollectionException(
                    platform,
                    if (status >= 500) FailureKind.PLATFORM_ERROR else FailureKind.BAD_RESPONSE,
                    "$what: HTTP $status with a body that isn't JSON",
                    cause = e,
                )
            }
        val error = body["error"] as? JsonObject ?: return body
        val code = (error["code"] as? JsonPrimitive)?.intOrNull
        val kind =
            when (code) {
                190, 102 -> FailureKind.SIGN_IN_EXPIRED
                10 -> FailureKind.SCOPE_MISSING
                in 200..299 -> FailureKind.SCOPE_MISSING
                4, 17, 32, 613, 80001, 80002 -> FailureKind.RATE_LIMITED
                else -> if (status >= 500) FailureKind.PLATFORM_ERROR else FailureKind.PLATFORM_ERROR
            }
        val message = error.str("message")?.take(200)
        throw CollectionException(
            platform,
            kind,
            "$what failed: code ${code ?: "?"}" + (message?.let { " ($it)" } ?: ""),
            logId = error.str("fbtrace_id"),
        )
    }

    companion object {
        const val VERSION = "v27.0"
        const val BASE = "https://graph.facebook.com/$VERSION"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.list(key: String): List<JsonObject> =
    (this[key] as? JsonArray).orEmpty().mapNotNull {
        it as? JsonObject
    }

internal fun JsonElement.number(): Double? =
    (this as? JsonPrimitive)?.let { if (it.isString) it.content.toDoubleOrNull() else it.doubleOrNull }

/**
 * An insights response (`data[]` of `{name, values: [{value}]}`) as name -> value element. Meta
 * leaves unavailable metrics out of `data`, and FourSight keeps them missing.
 */
internal fun insightValues(body: JsonObject): Map<String, JsonElement> =
    body
        .list("data")
        .mapNotNull { m ->
            val name = m.str("name") ?: return@mapNotNull null
            val value =
                (m["values"] as? JsonArray)?.lastOrNull()?.let { (it as? JsonObject)?.get("value") }
                    ?: (m.obj("total_value"))?.get("value")
                    ?: return@mapNotNull null
            name to value
        }.toMap()
