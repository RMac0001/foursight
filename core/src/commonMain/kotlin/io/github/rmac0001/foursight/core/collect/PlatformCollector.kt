package io.github.rmac0001.foursight.core.collect

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.model.Snapshot
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/** What one platform's collection returned: its posts and one snapshot per post plus the account. */
data class Collection(
    val posts: List<PlatformPost>,
    val snapshots: List<Snapshot>,
    /** When the access token used for this run expires, if the platform says. */
    val tokenExpiresAt: Instant? = null,
    /** Non-fatal notes for the run report, for example a scope the user didn't grant. */
    val warnings: List<String> = emptyList(),
)

/** One platform's collector (spec: Build order step 3). Implementations never log or return tokens. */
interface PlatformCollector {
    val platform: SocialPlatform

    /**
     * Reads every post and the account totals as of [now]. [deviceId] and [captureDay] are stamped on
     * each snapshot. Throws [CollectionException] on any failure; nothing is stored by the collector.
     */
    suspend fun collect(
        now: Instant,
        deviceId: String,
        captureDay: LocalDate,
    ): Collection
}

/** Why a collection failed, in terms the status screen and CLI can act on. */
enum class FailureKind(
    val advice: String,
) {
    NOT_CONNECTED("Run `foursight connect <platform>` to sign in."),
    MISSING_CLIENT_KEYS("Enter the platform's client keys in Windows Credential Manager (see STATUS.md)."),
    SIGN_IN_EXPIRED("The sign-in expired or was revoked. Run `foursight connect <platform>` again."),
    SCOPE_MISSING("A permission wasn't granted. Run `foursight connect <platform>` and allow every permission."),
    RATE_LIMITED("The platform's rate limit was hit. Try again in a few minutes."),
    PLATFORM_ERROR("The platform returned an error. Try again later."),
    NETWORK("Couldn't reach the platform. Check the internet connection."),
    BAD_RESPONSE("The platform's response didn't match its documented format."),
}

/**
 * A collection failure. [message] is safe to store and show: it never contains tokens, client
 * secrets or authorization codes. [logId] is the platform's request id, useful for its support.
 */
class CollectionException(
    val platform: SocialPlatform,
    val kind: FailureKind,
    detail: String,
    val logId: String? = null,
    cause: Throwable? = null,
) : Exception(
        buildString {
            append(platform.displayName).append(": ").append(detail)
            if (logId != null) append(" (log id ").append(logId).append(')')
        },
        cause,
    )
