package io.github.rmac0001.foursight.core.query

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.Snapshot
import io.github.rmac0001.foursight.core.sync.MatchEvents
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Instant

enum class ExportFormat { JSON, CSV }

/**
 * Exports of everything stored (spec: Export). Normalized values only: the raw API responses stay out,
 * because some carry signed media URLs. Nothing here touches tokens or secrets.
 */
object Exporter {
    const val NOTE =
        "FourSight export. Values are as captured; null or an empty cell means missing, never zero. Raw API " +
            "responses are left out. See definitions for how each metric is sourced."

    fun snapshots(
        queries: QueryService,
        platforms: Set<SocialPlatform>,
    ): List<Snapshot> =
        SocialPlatform.entries.filter { it in platforms }.flatMap { p ->
            queries.repo.posts(p).flatMap { queries.repo.snapshots(p, ItemKind.POST, it.postId) } +
                queries.repo.accountSnapshots(p) +
                queries.repo.snapshotsOfKind(p, ItemKind.AUDIENCE)
        }

    fun json(
        queries: QueryService,
        platforms: Set<SocialPlatform>,
        now: Instant,
    ): String {
        val posts = SocialPlatform.entries.filter { it in platforms }.flatMap { queries.repo.posts(it) }
        val links = MatchEvents.currentLinks(queries.repo.events()).filterKeys { it.platform in platforms }
        val body =
            buildJsonObject {
                put("note", NOTE)
                put("exported_at", now.toString())
                put("definitions_version", Metrics.DEFINITIONS_VERSION)
                putJsonArray(
                    "platforms",
                ) { platforms.sortedBy { it.ordinal }.forEach { add(JsonPrimitive(it.name.lowercase())) } }
                putJsonArray("posts") { posts.forEach { add(QueryJson.post(it)) } }
                putJsonObject("confirmed_matches") { links.forEach { (ref, video) -> put(ref.toString(), video) } }
                putJsonArray("snapshots") {
                    snapshots(queries, platforms).forEach { s ->
                        add(
                            buildJsonObject {
                                put("device_id", s.deviceId)
                                put("platform", s.platform.name.lowercase())
                                put("level", s.itemKind.name.lowercase())
                                put("item_id", s.itemId)
                                put("captured_at", s.capturedAt.toString())
                                put("capture_day", s.captureDay.toString())
                                put("definitions_version", s.definitionsVersion)
                                put(
                                    "values",
                                    JsonObject(
                                        s.values.mapValues { (_, v) ->
                                            if (v ==
                                                null
                                            ) {
                                                JsonNull
                                            } else {
                                                JsonPrimitive(v)
                                            }
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
                put("definitions", QueryJson.definitions(Metrics.all))
            }
        return QueryJson.encode(body)
    }

    val CSV_HEADER =
        listOf(
            "platform",
            "level",
            "item_id",
            "title",
            "captured_at",
            "capture_day",
            "device_id",
            "definitions_version",
            "metric",
            "value",
        )

    /** One row per captured value (long format), so new metrics never change the columns. */
    fun csv(
        queries: QueryService,
        platforms: Set<SocialPlatform>,
    ): String {
        val titles =
            SocialPlatform.entries
                .filter { it in platforms }
                .flatMap { queries.repo.posts(it) }
                .associate { it.ref.toString() to it.title }
        return buildString {
            appendLine(CSV_HEADER.joinToString(","))
            snapshots(queries, platforms).forEach { s ->
                val title =
                    if (s.itemKind ==
                        ItemKind.POST
                    ) {
                        titles["${s.platform.name.lowercase()}:${s.itemId}"]
                    } else {
                        null
                    }
                s.values.toSortedMap().forEach { (metric, value) ->
                    val row =
                        listOf(
                            s.platform.name.lowercase(),
                            s.itemKind.name.lowercase(),
                            s.itemId,
                            title.orEmpty(),
                            s.capturedAt.toString(),
                            s.captureDay.toString(),
                            s.deviceId,
                            s.definitionsVersion.toString(),
                            metric,
                            value
                                ?.let {
                                    if (it ==
                                        kotlin.math.floor(it)
                                    ) {
                                        it.toLong().toString()
                                    } else {
                                        it.toString()
                                    }
                                }.orEmpty(),
                        )
                    appendLine(row.joinToString(",") { cell(it) })
                }
            }
        }
    }

    /** Quotes a CSV cell when needed, and defuses cells a spreadsheet would run as a formula. */
    internal fun cell(text: String): String {
        val safe = if (text.isNotEmpty() && text[0] in "=+-@" && text.toDoubleOrNull() == null) "'$text" else text
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + safe.replace("\"", "\"\"") + "\""
        } else {
            safe
        }
    }
}
