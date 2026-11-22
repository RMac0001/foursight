package io.github.rmac0001.foursight.core.query

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.AudienceBreakdown
import io.github.rmac0001.foursight.core.metrics.Availability
import io.github.rmac0001.foursight.core.metrics.Calculations
import io.github.rmac0001.foursight.core.metrics.Computed
import io.github.rmac0001.foursight.core.metrics.Day7Result
import io.github.rmac0001.foursight.core.metrics.MetricDefinition
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.PlatformPost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Instant

/**
 * The JSON shape of every query result, shared by `foursight ... --json` and the MCP server so both
 * return the same thing. Every number carries its definition or metric id and a capture time, and
 * missing values are null with a reason. Never contains tokens or secrets: nothing here reads them.
 */
object QueryJson {
    val pretty = Json { prettyPrint = true }

    fun encode(element: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), element)

    private fun JsonObjectBuilder.putInstant(
        key: String,
        at: Instant?,
    ) = put(key, at?.toString())

    private fun num(v: Double?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)

    fun post(p: PlatformPost): JsonObject =
        buildJsonObject {
            put("ref", p.ref.toString())
            put("platform", p.platform.name.lowercase())
            put("post_id", p.postId)
            put("title", p.title)
            putInstant("published_at", p.publishedAt)
            put("url", p.url)
            put("duration_seconds", p.durationSeconds)
            put("content_type", p.contentType.name.lowercase())
        }

    fun computed(c: Computed): JsonObject =
        buildJsonObject {
            put("id", c.id)
            put("value", num(c.value))
            put("definition", c.definition)
            putJsonObject("inputs") { c.inputs.forEach { (k, v) -> put(k, num(v)) } }
            if (c.missingReason != null) put("missing_reason", c.missingReason)
        }

    fun entry(e: LibraryEntry): JsonObject =
        buildJsonObject {
            put("video", e.video.videoId)
            put("confirmed_match", e.confirmed)
            putInstant("first_published_at", e.video.firstPublishedAt)
            putJsonArray("posts") {
                e.video.posts
                    .sortedBy { it.platform.ordinal }
                    .forEach { add(post(it)) }
            }
        }

    fun videos(
        entries: List<LibraryEntry>,
        latestViews: (PlatformPost) -> Pair<Double?, Instant?>,
    ): JsonObject =
        buildJsonObject {
            put("count", entries.size)
            putJsonArray("videos") {
                entries.forEach { e ->
                    add(
                        JsonObject(
                            entry(e) +
                                (
                                    "latest_views" to
                                        buildJsonArray {
                                            e.video.posts.sortedBy { it.platform.ordinal }.forEach { p ->
                                                val (views, at) = latestViews(p)
                                                add(
                                                    buildJsonObject {
                                                        put("ref", p.ref.toString())
                                                        put("views", num(views))
                                                        putInstant("captured_at", at)
                                                    },
                                                )
                                            }
                                        }
                                ),
                        ),
                    )
                }
            }
            put("metric", Metrics.VIEWS.id)
        }

    fun day7(r: Day7Result?): JsonElement =
        when (r) {
            null -> {
                JsonNull
            }

            is Day7Result.Locked -> {
                buildJsonObject {
                    put("state", "locked")
                    putInstant("captured_at", r.snapshot.capturedAt)
                    put("age_days", r.ageDays)
                }
            }

            else -> {
                buildJsonObject {
                    put(
                        "state",
                        when (r) {
                            Day7Result.NotYetDue -> "not_yet_due"
                            Day7Result.Pending -> "pending"
                            else -> "missed"
                        },
                    )
                    put("reason", day7Reason(r))
                }
            }
        }

    fun postStats(s: PostStats): JsonObject =
        buildJsonObject {
            put("post", post(s.post))
            put("basis", s.basis.name.lowercase())
            putInstant("captured_at", s.capturedAt)
            put("age_days", num(s.ageDays))
            if (s.noCaptureReason != null) put("no_capture_reason", s.noCaptureReason)
            if (s.day7 != null) put("day7", day7(s.day7))
            putJsonObject("values") { s.values.forEach { (k, v) -> put(k, num(v)) } }
            putJsonObject("unavailable") { s.unavailable.forEach { (k, v) -> put(k, v) } }
            putJsonArray("computed") { s.computed.forEach { add(computed(it)) } }
            s.accountFollowerChange?.let { put("account_follower_change", computed(it)) }
        }

    fun videoStats(v: VideoStats): JsonObject =
        buildJsonObject {
            put("video", entry(v.entry))
            put("basis", v.basis.name.lowercase())
            putJsonArray("platforms") { v.posts.forEach { add(postStats(it)) } }
            putJsonArray("totals") { v.totals.forEach { add(computed(it)) } }
            putInstant("generated_at", v.generatedAt)
            put("definitions_version", v.definitionsVersion)
            put("note", "Values are metric ids; see get_metric_definitions. Null means missing, never zero.")
        }

    fun sevenDay(r: SevenDayReport): JsonObject =
        buildJsonObject {
            put("video", entry(r.entry))
            putJsonArray("platforms") {
                r.posts.forEach { d ->
                    add(
                        buildJsonObject {
                            put("post", post(d.post))
                            put("day7", day7(d.result))
                            putJsonObject("values") { d.values.forEach { (k, v) -> put(k, num(v)) } }
                            d.savesPer1k?.let { put("saves_per_1k", computed(it)) }
                            d.sharesPer1k?.let { put("shares_per_1k", computed(it)) }
                            d.followersPer1k?.let { put("followers_per_1k", computed(it)) }
                            d.followersPer1kUnavailable?.let { put("followers_per_1k_unavailable", it) }
                            d.accountFollowerChange?.let { put("account_follower_change", computed(it)) }
                        },
                    )
                }
            }
            put("block", SevenDayBlock.render(r))
            putInstant("generated_at", r.generatedAt)
            put("definitions_version", r.definitionsVersion)
        }

    fun summary(s: AccountSummary): JsonObject =
        buildJsonObject {
            put("period", s.label)
            putInstant("start", s.start)
            putInstant("end", s.end)
            putInstant("data_starts_at", s.dataStartsAt)
            if (s.dataStartsAt != null && s.start != null && s.dataStartsAt > s.start) {
                put("partial_period", "Collection started ${s.dataStartsAt}, after the period start.")
            }
            put("followers_total", computed(s.followersTotal))
            putJsonArray("platforms") {
                s.platforms.forEach { p ->
                    add(
                        buildJsonObject {
                            put("platform", p.platform.name.lowercase())
                            put("followers", num(p.followers))
                            putInstant("followers_captured_at", p.followersCapturedAt)
                            put("follower_change", computed(p.followerChange))
                            putJsonArray("gained") { p.gained.forEach { add(computed(it)) } }
                            put("posts_without_baseline", p.postsWithoutBaseline)
                            put("posts_published", p.postsPublished)
                            put("ranking", ranking(p.ranking))
                            putInstant("latest_capture", p.latestCapture)
                        },
                    )
                }
            }
            putInstant("generated_at", s.generatedAt)
            put("definitions_version", s.definitionsVersion)
        }

    private fun ranking(r: Ranking): JsonObject =
        buildJsonObject {
            put("measure", r.measure.metricId)
            put("label", r.measure.label)
            r.measure.note?.let { put("note", it) }
            put("sample_size", r.ranked)

            fun list(items: List<RankedPost>) =
                JsonArray(
                    items.map {
                        buildJsonObject {
                            put("post", post(it.post))
                            put("value", computed(it.value))
                            putInstant("day7_captured_at", it.day7CapturedAt)
                            put("age_days", it.ageDays)
                        }
                    },
                )
            put("best", list(r.best))
            put("weakest", list(r.weakest))
            putJsonObject("not_ranked") { r.notRanked.forEach { (k, v) -> put(k, v) } }
        }

    fun trends(t: Trends): JsonObject =
        buildJsonObject {
            put("days", t.days)
            put("from", t.from.toString())
            put("to", t.to.toString())
            put("definition", t.definition)
            putJsonArray("series") {
                t.series.forEach { s ->
                    add(
                        buildJsonObject {
                            put("platform", s.platform.name.lowercase())
                            put("metric", s.metric.id)
                            s.unavailableReason?.let { put("unavailable", it) }
                            putJsonArray("points") {
                                s.points.forEach { p ->
                                    add(
                                        buildJsonObject {
                                            put("day", p.day.toString())
                                            put("value", num(p.value))
                                            put("change", num(p.change))
                                            putInstant("captured_at", p.capturedAt)
                                            p.missingReason?.let { put("missing_reason", it) }
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
            putInstant("generated_at", t.generatedAt)
            put("definitions_version", t.definitionsVersion)
        }

    fun comparison(c: VideoComparison): JsonObject =
        buildJsonObject {
            put("a_day7", videoStats(c.a))
            put("b_day7", videoStats(c.b))
            put("a_latest", videoStats(c.aLatest))
            put("b_latest", videoStats(c.bLatest))
            if (c.metrics.isNotEmpty()) putJsonArray("metrics") { c.metrics.forEach { add(JsonPrimitive(it)) } }
            put("note", "Day-7 numbers compare both videos at the same age. Describes differences; never causes.")
            putInstant("generated_at", c.generatedAt)
        }

    fun freshness(list: List<PlatformFreshness>): JsonObject =
        buildJsonObject {
            putJsonArray("platforms") {
                list.forEach { f ->
                    add(
                        buildJsonObject {
                            put("platform", f.platform.name.lowercase())
                            putInstant("latest_capture", f.latestCapture)
                            putInstant("last_success", f.status?.lastSuccessAt)
                            putInstant("last_attempt", f.status?.lastAttemptAt)
                            put("last_error", f.status?.lastError)
                            putInstant("sign_in_expires", f.status?.tokenExpiresAt)
                            putJsonArray("limits") { PlatformLimits.of(f.platform).forEach { add(JsonPrimitive(it)) } }
                        },
                    )
                }
            }
        }

    fun definitions(list: List<MetricDefinition>): JsonObject =
        buildJsonObject {
            put("definitions_version", Metrics.DEFINITIONS_VERSION)
            putJsonArray("metrics") {
                list.forEach { m ->
                    add(
                        buildJsonObject {
                            put("id", m.id)
                            put("label", m.label)
                            put("kind", m.kind.name.lowercase())
                            put("summable_across_platforms", m.kind.summable)
                            put("unit", m.unit)
                            put("level", m.itemKind.name.lowercase())
                            put("definition", m.definition)
                            putJsonObject("sources") {
                                SocialPlatform.entries.forEach { p ->
                                    when (val a = m.availability(p)) {
                                        is Availability.Available -> {
                                            put(p.name.lowercase(), buildJsonObject { put("source", a.source) })
                                        }

                                        is Availability.Unavailable -> {
                                            put(p.name.lowercase(), buildJsonObject { put("unavailable", a.reason) })
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            }
            putJsonArray("audience") {
                AudienceBreakdown.entries.forEach { b ->
                    add(
                        buildJsonObject {
                            put("platform", b.platform.name.lowercase())
                            put("id", b.id)
                            put("label", b.label)
                            put("unit", b.unit)
                            put("window", b.window)
                            put("source", b.source)
                        },
                    )
                }
            }
            putJsonArray("computed") {
                computedDefinitions.forEach { (id, text) ->
                    add(
                        buildJsonObject {
                            put("id", id)
                            put("definition", text)
                        },
                    )
                }
            }
        }

    fun audience(r: AudienceReport): JsonObject =
        buildJsonObject {
            putJsonArray("platforms") {
                r.platforms.forEach { p ->
                    add(
                        buildJsonObject {
                            put("platform", p.platform.name.lowercase())
                            putJsonArray("breakdowns") {
                                p.breakdowns.forEach { v ->
                                    add(
                                        buildJsonObject {
                                            put("id", v.breakdown.id)
                                            put("label", v.breakdown.label)
                                            put("unit", v.breakdown.unit)
                                            put("window", v.breakdown.window)
                                            put("source", v.breakdown.source)
                                            putInstant("captured_at", v.capturedAt)
                                            v.missingReason?.let { put("missing_reason", it) }
                                            putJsonArray("groups") {
                                                v.groups.forEach { g ->
                                                    add(
                                                        buildJsonObject {
                                                            put("group", g.group)
                                                            put("value", num(g.value))
                                                            if (g.shareOfListed != null) {
                                                                put("share_of_listed_percent", g.shareOfListed)
                                                            }
                                                        },
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                            putJsonArray("not_available") { p.notAvailable.forEach { add(JsonPrimitive(it)) } }
                        },
                    )
                }
            }
            put("share_of_listed_definition", SHARE_OF_LISTED_DEFINITION)
            putInstant("generated_at", r.generatedAt)
            put("definitions_version", Metrics.DEFINITIONS_VERSION)
        }

    /** Definitions of the numbers computed from the stored metrics. */
    val computedDefinitions: List<Pair<String, String>> =
        listOf(
            "saves_per_1k / shares_per_1k / followers_per_1k" to
                Calculations.PER_1K_DEFINITION,
            "views_per_day" to Calculations.VIEWS_PER_DAY_DEFINITION,
            "engagement_rate" to Calculations.ENGAGEMENT_RATE_DEFINITION,
            "follower_change / account_follower_change" to
                Calculations.CHANGE_DEFINITION,
            "*_gained (summary)" to GAINED_DEFINITION,
            "trends" to TREND_DEFINITION,
            "match score" to Matching.DEFINITION,
            "audience share_of_listed" to SHARE_OF_LISTED_DEFINITION,
            "day-7 lock" to
                "The first capture at least 7 × 24 hours after publishing, no later than day 8.0; its exact age " +
                "is kept.",
        )
}
