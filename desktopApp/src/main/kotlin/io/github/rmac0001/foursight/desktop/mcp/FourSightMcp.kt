package io.github.rmac0001.foursight.desktop.mcp

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.query.Exporter
import io.github.rmac0001.foursight.core.query.LibraryEntry
import io.github.rmac0001.foursight.core.query.Period
import io.github.rmac0001.foursight.core.query.QueryJson
import io.github.rmac0001.foursight.core.query.StatsBasis
import io.github.rmac0001.foursight.core.query.accountSummary
import io.github.rmac0001.foursight.core.query.audience
import io.github.rmac0001.foursight.core.query.compare
import io.github.rmac0001.foursight.core.query.latestSnapshot
import io.github.rmac0001.foursight.core.query.recentSevenDayReports
import io.github.rmac0001.foursight.core.query.trendMetrics
import io.github.rmac0001.foursight.core.query.trends
import io.github.rmac0001.foursight.core.query.videoStats
import io.github.rmac0001.foursight.core.query.videos
import io.github.rmac0001.foursight.desktop.DesktopServices
import io.github.rmac0001.foursight.desktop.obsidian.WriteReport
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Instant

/**
 * The local MCP server (spec: MCP server and CLI), over stdio, built on the official Kotlin MCP SDK.
 * Every tool returns the same JSON as the matching `foursight ... --json` command.
 *
 * What it can't do, by design (spec: "The MCP cannot"): publish, delete or edit posts, change
 * account or app settings, modify stored metrics, change matches, or read tokens. The only tool that
 * changes local data is `refresh`, which runs the normal collector. Nothing it returns contains
 * tokens or secrets: the query services never read them.
 */
class FourSightMcp(
    private val services: DesktopServices,
    private val clock: () -> Instant,
) {
    /** A tool's definition and its handler; [readOnly] is advertised to the client. */
    class ToolSpec(
        val name: String,
        val title: String,
        val description: String,
        val properties: JsonObject,
        val required: List<String>,
        val readOnly: Boolean,
        val run: suspend (JsonObject) -> JsonElement,
    )

    /** A bad argument or unknown video: returned to the client as a tool error with this message. */
    class ToolError(
        message: String,
    ) : Exception(message)

    private val queries get() = services.queries

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.removeSuffix("d")?.toIntOrNull() }

    private fun JsonObject.platforms(): Set<SocialPlatform> {
        val raw =
            when (val v = this["platforms"] ?: this["platform"]) {
                null -> return SocialPlatform.entries.toSet()
                is JsonArray -> v.map { it.jsonPrimitive.content }
                else -> v.jsonPrimitive.content.split(',')
            }
        return raw
            .filter { it.isNotBlank() }
            .map { n ->
                SocialPlatform.entries.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) }
                    ?: throw ToolError("Unknown platform \"$n\". Use tiktok, youtube, facebook or instagram.")
            }.toSet()
    }

    private fun entry(key: String?): LibraryEntry {
        if (key.isNullOrBlank()) {
            throw ToolError("Name a video: a video id or a post like tiktok:7301234567 (see list_videos).")
        }
        return queries.findEntry(key) ?: throw ToolError("No video matches \"$key\". Call list_videos for the ids.")
    }

    private fun prop(
        type: String,
        description: String,
        enum: List<String>? = null,
    ): JsonObject =
        buildJsonObject {
            put("type", type)
            put("description", description)
            enum?.let { putJsonArray("enum") { it.forEach { v -> add(JsonPrimitive(v)) } } }
        }

    private val platformsProp: JsonObject =
        buildJsonObject {
            put("type", "array")
            put("description", "Limit to these platforms. Leave out for all four.")
            putJsonObject("items") {
                put("type", "string")
                putJsonArray("enum") { SocialPlatform.entries.forEach { add(JsonPrimitive(it.name.lowercase())) } }
            }
        }

    private val videoProp = prop("string", "A video id, or a post such as tiktok:7301234567 (from list_videos).")

    val tools: List<ToolSpec> =
        listOf(
            ToolSpec(
                "list_videos",
                "List videos",
                "Every video, grouped across platforms where matched, newest first, with each post's latest views " +
                    "and capture time.",
                buildJsonObject {
                    put("platforms", platformsProp)
                    put(
                        "since",
                        prop(
                            "string",
                            "Only videos first published since a date (2026-11-01) or in the last N days (28d).",
                        ),
                    )
                },
                emptyList(),
                readOnly = true,
            ) { args ->
                val platforms = args.platforms()
                val since =
                    args.string("since")?.let { raw ->
                        Period.parse(raw)?.start(clock())
                            ?: runCatching { LocalDate.parse(raw).atStartOfDayIn(services.timeZone) }.getOrNull()
                            ?: throw ToolError("since takes a date (2026-11-01) or a number of days (28d).")
                    }
                val entries = queries.videos(null, since).filter { e -> e.video.posts.any { it.platform in platforms } }
                QueryJson.videos(entries) { p ->
                    val s = queries.latestSnapshot(p)
                    s?.value(Metrics.VIEWS.id) to s?.capturedAt
                }
            },
            ToolSpec(
                "get_video_stats",
                "Video stats",
                "One video's numbers on each platform: every metric the platform reports (null = missing, never " +
                    "zero), what it doesn't report and why, views per day, engagement rate and per-1,000 rates, " +
                    "from the latest capture or the day-7 lock.",
                buildJsonObject {
                    put("video", videoProp)
                    put("day", prop("integer", "7 for the day-7 numbers. Leave out for the latest capture.", null))
                },
                listOf("video"),
                readOnly = true,
            ) { args ->
                val basis =
                    when (args.int("day")) {
                        null -> StatsBasis.LATEST
                        7 -> StatsBasis.DAY_7
                        else -> throw ToolError("day supports 7 only (the day-7 lock).")
                    }
                QueryJson.videoStats(queries.videoStats(entry(args.string("video")), basis, clock()))
            },
            ToolSpec(
                "get_seven_day_report",
                "7-day report",
                "The Playbook's 7-day block (R7.2), filled in and ready to paste into the video's note, plus the " +
                    "numbers behind it. With no video, returns every video that locked its day-7 numbers in the last " +
                    "recent_days days (the Sunday review). Where viewers stopped, comments and the lesson stay blank.",
                buildJsonObject {
                    put("video", videoProp)
                    put(
                        "recent_days",
                        prop("integer", "Without a video: how far back to look for day-7 locks. Default 7."),
                    )
                },
                emptyList(),
                readOnly = true,
            ) { args ->
                val now = clock()
                val reports =
                    when (val key = args.string("video")) {
                        null -> queries.recentSevenDayReports((args.int("recent_days") ?: 7).coerceIn(1, 90), now)
                        else -> listOf(queries.sevenDayReport(entry(key), now))
                    }
                buildJsonObject {
                    put("count", reports.size)
                    put("reports", JsonArray(reports.map { QueryJson.sevenDay(it) }))
                }
            },
            ToolSpec(
                "get_account_summary",
                "Account summary",
                "Followers per platform and their change, counts gained during the period, videos published, and " +
                    "the best and weakest two videos by day-7 followers per 1,000 (YouTube, Facebook) or shares per " +
                    "1,000 (TikTok, Instagram, which don't report followers per video). Shows sample sizes and when " +
                    "the period is only partly covered.",
                buildJsonObject {
                    put("period", prop("string", "7d, 28d, 90d, any Nd, or all. Default 28d."))
                    put("platforms", platformsProp)
                },
                emptyList(),
                readOnly = true,
            ) { args ->
                val period =
                    args.string("period")?.let {
                        Period.parse(it) ?: throw ToolError("period takes 7d, 28d, 90d or all.")
                    }
                        ?: Period(28)
                QueryJson.summary(queries.accountSummary(period, clock(), args.platforms()))
            },
            ToolSpec(
                "get_trends",
                "Trends",
                "Day-by-day totals per platform and the change from the day before. Days with no capture, or with a " +
                    "post not captured, are gaps (null with a reason), never filled in. Counts only.",
                buildJsonObject {
                    put("days", prop("integer", "How many days back, ending today. Default 28."))
                    put(
                        "metrics",
                        buildJsonObject {
                            put("type", "array")
                            put("description", "Default followers and views.")
                            putJsonObject("items") {
                                put("type", "string")
                                putJsonArray("enum") { trendMetrics.forEach { add(JsonPrimitive(it.id)) } }
                            }
                        },
                    )
                    put("platforms", platformsProp)
                },
                emptyList(),
                readOnly = true,
            ) { args ->
                val days = args.int("days") ?: 28
                if (days !in 1..3650) throw ToolError("days must be from 1 to 3650.")
                val ids =
                    (args["metrics"] as? JsonArray)?.map { it.jsonPrimitive.content }
                        ?: args.string("metric")?.split(',')
                        ?: listOf("followers", "views")
                val metrics =
                    ids.map { id ->
                        trendMetrics.firstOrNull { it.id == id.trim() }
                            ?: throw ToolError(
                                "Unknown trend metric \"$id\". Use ${trendMetrics.joinToString { it.id }}.",
                            )
                    }
                QueryJson.trends(queries.trends(days, metrics, clock(), services.timeZone, args.platforms()))
            },
            ToolSpec(
                "compare_videos",
                "Compare videos",
                "Two videos side by side: day-7 numbers (both at the same age) and latest numbers with their ages. " +
                    "Describes differences; it can't say why they differ.",
                buildJsonObject {
                    put("a", videoProp)
                    put("b", videoProp)
                    put(
                        "metrics",
                        buildJsonObject {
                            put("type", "array")
                            put("description", "Metric or computed ids to focus on (see get_metric_definitions).")
                            putJsonObject("items") { put("type", "string") }
                        },
                    )
                },
                listOf("a", "b"),
                readOnly = true,
            ) { args ->
                val metrics = (args["metrics"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                val c = queries.compare(entry(args.string("a")), entry(args.string("b")), clock(), metrics)
                QueryJson.comparison(c)
            },
            ToolSpec(
                "get_audience",
                "Audience",
                "Who watches or follows, where available: YouTube viewers by age and gender, views by traffic " +
                    "source and by country (last 28 Analytics days); Facebook Page followers by country and city; " +
                    "Instagram followers by age, gender and country. Each breakdown says its unit, time window and " +
                    "capture time, and each platform lists what it doesn't provide (TikTok provides none).",
                buildJsonObject { put("platforms", platformsProp) },
                emptyList(),
                readOnly = true,
            ) { args -> QueryJson.audience(queries.audience(clock(), args.platforms())) },
            ToolSpec(
                "get_freshness",
                "Data freshness",
                "Per platform: the latest capture, last successful and attempted refresh, the last error, when the " +
                    "sign-in expires, and the API limits that apply. Check this before trusting today's numbers.",
                JsonObject(emptyMap()),
                emptyList(),
                readOnly = true,
            ) { _ -> QueryJson.freshness(queries.freshness()) },
            ToolSpec(
                "get_metric_definitions",
                "Metric definitions",
                "How every number is sourced and calculated, per platform, including which metrics a platform " +
                    "doesn't report and why, and which can be summed across platforms.",
                JsonObject(emptyMap()),
                emptyList(),
                readOnly = true,
            ) { _ -> QueryJson.definitions(queries.definitions()) },
            ToolSpec(
                "refresh",
                "Refresh",
                "Collect fresh numbers now with the normal collector (the same as the daily run). Each platform runs " +
                    "on its own; one failing doesn't stop the others. Takes up to a minute or two.",
                buildJsonObject { put("platforms", platformsProp) },
                emptyList(),
                readOnly = false,
            ) { args ->
                val only = if (args["platforms"] == null && args["platform"] == null) null else args.platforms()
                val results = services.runner.run(only)
                val vault =
                    if (results.any { it.ok }) {
                        runCatching {
                            services.afterCollection(
                                clock(),
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                buildJsonObject {
                    putJsonArray("platforms") {
                        results.forEach { r ->
                            add(
                                buildJsonObject {
                                    put("platform", r.platform.name.lowercase())
                                    put("ok", r.ok)
                                    put("posts", r.posts)
                                    put("new_snapshots", r.newSnapshots)
                                    r.error?.let { put("error", it) }
                                    r.failure?.let {
                                        put("advice", it.advice.replace("<platform>", r.platform.name.lowercase()))
                                    }
                                    if (r.warnings.isNotEmpty()) {
                                        putJsonArray("warnings") { r.warnings.forEach { add(JsonPrimitive(it)) } }
                                    }
                                },
                            )
                        }
                    }
                    vault?.let { put("obsidian", vaultJson(it)) }
                    put("finished_at", clock().toString())
                }
            },
            ToolSpec(
                "write_obsidian_stats",
                "Write Obsidian stats",
                "Writes the 7-day block and the stats fields into a video's note in Roger's vault, and regenerates " +
                    "the Stats Overview note. Only notes with a post id in their frontmatter (tiktok_id, youtube_id, " +
                    "facebook_id, instagram_id) are touched, only FourSight's own fields and block, and Roger's " +
                    "lines in the block are kept. With no video, writes every note that has day-7 numbers. It also " +
                    "runs on its own after each collection.",
                buildJsonObject {
                    put("video", prop("string", "A note name, or a post such as tiktok:7301234567. Leave out for all."))
                    put("dry_run", prop("boolean", "Report what would change without writing."))
                },
                emptyList(),
                readOnly = false,
            ) { args ->
                val writer =
                    services.obsidian()
                        ?: throw ToolError(
                            "The vault isn't set up. Roger runs: foursight obsidian setup --vault <folder>",
                        )
                val dry = (args["dry_run"] as? JsonPrimitive)?.content == "true"
                val key = args.string("video")
                if (key == null) {
                    vaultJson(writer.writeAll(clock(), dry))
                } else {
                    val note =
                        writer.notes().firstOrNull {
                            it.name.equals(key, ignoreCase = true) ||
                                it.refs.any { r -> r.toString() == key }
                        }
                            ?: throw ToolError("No video note named \"$key\" has post ids.")
                    val r = writer.write(note, clock(), dry)
                    val overview = if (dry) "not written (dry run)" else writer.writeOverview(writer.notes(), clock())
                    vaultJson(WriteReport(listOf(r), overview, emptyList()))
                }
            },
            ToolSpec(
                "export",
                "Export",
                "Every stored post and capture (normalized values, no raw API responses, no secrets) as JSON, or as " +
                    "CSV with one row per captured value.",
                buildJsonObject {
                    put("format", prop("string", "json or csv. Default json.", listOf("json", "csv")))
                    put("platforms", platformsProp)
                },
                emptyList(),
                readOnly = true,
            ) { args ->
                when (args.string("format")?.lowercase() ?: "json") {
                    "json" -> QueryJson.pretty.parseToJsonElement(Exporter.json(queries, args.platforms(), clock()))
                    "csv" -> JsonPrimitive(Exporter.csv(queries, args.platforms()))
                    else -> throw ToolError("format is json or csv.")
                }
            },
        )

    private fun vaultJson(r: WriteReport): JsonObject =
        buildJsonObject {
            putJsonArray("notes") {
                r.notes.forEach { n ->
                    add(
                        buildJsonObject {
                            put("note", n.note.name)
                            put("outcome", n.outcome.name.lowercase())
                            n.detail?.let { put("detail", it) }
                        },
                    )
                }
            }
            put("stats_overview", r.overview)
            if (r.errors.isNotEmpty()) putJsonArray("errors") { r.errors.forEach { add(JsonPrimitive(it)) } }
        }

    /** Runs one tool by name: the path both the server and the tests use. */
    suspend fun call(
        name: String,
        arguments: JsonObject?,
    ): CallToolResult {
        val tool = tools.firstOrNull { it.name == name } ?: return error("Unknown tool: $name")
        return try {
            val result = tool.run(arguments ?: JsonObject(emptyMap()))
            val text = if (result is JsonPrimitive && result.isString) result.content else QueryJson.encode(result)
            CallToolResult(content = listOf(TextContent(text)))
        } catch (e: ToolError) {
            error(e.message ?: "Bad request.")
        } catch (e: IllegalArgumentException) {
            error(e.message ?: "Bad request.")
        } catch (e: Exception) {
            // Only the class name: an unexpected exception's message could carry anything.
            error("FourSight hit an unexpected ${e::class.simpleName}. Try again, or run the CLI to see more.")
        }
    }

    private fun error(message: String) = CallToolResult(content = listOf(TextContent(message)), isError = true)

    fun server(): Server {
        val server =
            Server(
                Implementation(name = "foursight", version = VERSION, title = "FourSight"),
                ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
                INSTRUCTIONS,
            )
        tools.forEach { t ->
            server.addTool(
                name = t.name,
                title = t.title,
                description = t.description,
                inputSchema = ToolSchema(properties = t.properties, required = t.required.ifEmpty { null }),
                toolAnnotations =
                    ToolAnnotations(
                        title = t.title,
                        readOnlyHint = t.readOnly,
                        destructiveHint = false,
                        idempotentHint = t.readOnly,
                        openWorldHint = !t.readOnly,
                    ),
            ) { request -> call(request.name, request.arguments) }
        }
        return server
    }

    /**
     * Serves MCP over stdin and stdout until the client disconnects. stdout carries only the protocol:
     * the real stdout goes to the transport and System.out is pointed at stderr, so a stray print from
     * a library (kotlin-logging prints a startup line) can't corrupt it.
     */
    fun runStdio(): Int {
        System.setProperty("kotlin-logging.logStartupMessage", "false")
        val protocolOut = System.out
        System.setOut(System.err)
        return runBlocking {
            val transport = StdioServerTransport(System.`in`.asSource().buffered(), protocolOut.asSink().buffered())
            val closed = CompletableDeferred<Unit>()
            val session = server().createSession(transport)
            session.onClose { closed.complete(Unit) }
            closed.await()
            0
        }
    }

    companion object {
        const val VERSION = "0.1.0"

        val INSTRUCTIONS =
            """
            FourSight holds Roger's creator analytics for One Fat Man's Journey on TikTok, YouTube Shorts, Facebook
            Reels and Instagram Reels, collected daily on his PC. For the Sunday review, call get_seven_day_report
            with no video, then get_account_summary. Check get_freshness first if the numbers might be stale.
            Null values are missing, never zero; "unavailable" means the platform doesn't report that metric.
            Rates and durations are never combined across platforms. Describe patterns; don't claim causes.
            TikTok's "most viewers stopped at" is only in TikTok Studio. This server can't post, edit, delete or
            change settings; refresh is the only tool that changes data.
            """.trimIndent()
    }
}
