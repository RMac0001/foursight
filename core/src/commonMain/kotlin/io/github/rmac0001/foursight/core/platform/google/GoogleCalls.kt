package io.github.rmac0001.foursight.core.platform.google

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.collect.CollectionException
import io.github.rmac0001.foursight.core.collect.FailureKind
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Shared request handling for Google APIs. Exception messages never contain tokens or URLs. */
internal object GoogleCalls {
    private val json = Json { ignoreUnknownKeys = true }

    /** Sends a request and returns its status and JSON body; maps network failures. */
    suspend fun send(
        platform: SocialPlatform,
        what: String,
        request: suspend () -> HttpResponse,
    ): Pair<Int, JsonObject> {
        val response =
            try {
                request()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CollectionException(platform, FailureKind.NETWORK, "$what: ${e::class.simpleName}")
            }
        val status = response.status.value
        val text = response.bodyAsText()
        val body =
            try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: IllegalArgumentException) {
                throw CollectionException(
                    platform,
                    if (status >= 500) FailureKind.PLATFORM_ERROR else FailureKind.BAD_RESPONSE,
                    "$what: HTTP $status with a body that isn't JSON",
                    cause = e,
                )
            }
        return status to body
    }

    /**
     * A Google API call (YouTube Data, YouTube Analytics, Drive). Errors come back as
     * `{"error": {"code", "message", "status", "errors": [{"reason"}]}}`.
     */
    suspend fun api(
        platform: SocialPlatform,
        what: String,
        request: suspend () -> HttpResponse,
    ): JsonObject {
        val (status, body) = send(platform, what, request)
        if (status in 200..299) return body
        val error = body["error"] as? JsonObject
        val reason =
            ((error?.get("errors") as? JsonArray)?.firstOrNull() as? JsonObject)?.str("reason")
                ?: error?.str("status")
        val kind =
            when {
                status == 401 -> {
                    FailureKind.SIGN_IN_EXPIRED
                }

                reason in setOf("quotaExceeded", "rateLimitExceeded", "userRateLimitExceeded", "RESOURCE_EXHAUSTED") ||
                    status == 429 -> {
                    FailureKind.RATE_LIMITED
                }

                reason in setOf("insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT", "forbidden") -> {
                    FailureKind.SCOPE_MISSING
                }

                else -> {
                    FailureKind.PLATFORM_ERROR
                }
            }
        val message = error?.str("message")?.take(200)
        throw CollectionException(
            platform,
            kind,
            "$what failed: HTTP $status" + (reason?.let { " $it" } ?: "") + (message?.let { " ($it)" } ?: ""),
        )
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** A number sent either as a JSON number or, as YouTube does for statistics, a string. */
internal fun JsonObject.num(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { p -> if (p.isString) p.content.toLongOrNull() else p.longOrNull }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
