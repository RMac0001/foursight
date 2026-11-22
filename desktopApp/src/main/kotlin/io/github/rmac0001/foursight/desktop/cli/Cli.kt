package io.github.rmac0001.foursight.desktop.cli

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.auth.SignInException
import io.github.rmac0001.foursight.core.collect.CollectionException
import io.github.rmac0001.foursight.core.collect.PlatformRunResult
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.model.PostRef
import io.github.rmac0001.foursight.core.query.ExportFormat
import io.github.rmac0001.foursight.core.query.Exporter
import io.github.rmac0001.foursight.core.query.LibraryEntry
import io.github.rmac0001.foursight.core.query.Matching
import io.github.rmac0001.foursight.core.query.Period
import io.github.rmac0001.foursight.core.query.QueryJson
import io.github.rmac0001.foursight.core.query.SevenDayBlock
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
import io.github.rmac0001.foursight.core.sync.MatchEvents
import io.github.rmac0001.foursight.desktop.DesktopServices
import io.github.rmac0001.foursight.desktop.auth.signIn
import io.github.rmac0001.foursight.desktop.mcp.FourSightMcp
import io.github.rmac0001.foursight.desktop.obsidian.NoteOutcome
import io.github.rmac0001.foursight.desktop.obsidian.ObsidianSettings
import io.github.rmac0001.foursight.desktop.obsidian.WriteReport
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import java.awt.Desktop
import java.io.File
import java.io.PrintStream
import java.net.URI
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The command line (spec: MCP server and CLI). Readable output by default, JSON with `--json` (the
 * same JSON the MCP server returns). Output never contains tokens, secrets or authorization codes.
 */
class Cli(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    private val services: () -> DesktopServices = { DesktopServices() },
    private val clock: () -> Instant = { Clock.System.now() },
) {
    fun run(args: List<String>): Int {
        val command = args.firstOrNull()
        val rest = args.drop(1)
        return try {
            when (command) {
                "connect" -> {
                    withServices { connect(it, rest) }
                }

                "collect", "refresh" -> {
                    withServices { collect(it, rest) }
                }

                "videos" -> {
                    withServices { videos(it, rest) }
                }

                "stats" -> {
                    withServices { stats(it, rest) }
                }

                "report" -> {
                    withServices { report(it, rest) }
                }

                "summary" -> {
                    withServices { summary(it, rest) }
                }

                "trends" -> {
                    withServices { trends(it, rest) }
                }

                "compare" -> {
                    withServices { compare(it, rest) }
                }

                "status" -> {
                    withServices { status(it, rest) }
                }

                "audience" -> {
                    withServices { audience(it, rest) }
                }

                "obsidian" -> {
                    withServices { obsidian(it, rest) }
                }

                "definitions" -> {
                    definitions(rest)
                }

                "export" -> {
                    withServices { export(it, rest) }
                }

                "match" -> {
                    withServices { match(it, rest) }
                }

                "mcp" -> {
                    withServices { FourSightMcp(it, clock).runStdio() }
                }

                null, "help", "--help", "-h" -> {
                    usage(0)
                }

                else -> {
                    err.println("Unknown command: $command")
                    usage(2)
                }
            }
        } catch (e: Args.UsageError) {
            err.println(e.message)
            err.println("Run: foursight help")
            2
        }
    }

    private fun withServices(body: (DesktopServices) -> Int): Int = services().use(body)

    private fun json(text: String): Int {
        out.println(text)
        return 0
    }

    private fun entry(
        s: DesktopServices,
        key: String,
    ): LibraryEntry? =
        s.queries.findEntry(key) ?: run {
            err.println("No video matches \"$key\". Use a video id or a post like tiktok:123 (see: foursight videos).")
            null
        }

    private fun text(s: DesktopServices) = Text(s.timeZone)

    private fun latestViews(s: DesktopServices): (PlatformPost) -> Pair<Double?, Instant?> =
        { p ->
            val snap = s.queries.latestSnapshot(p)
            snap?.value(Metrics.VIEWS.id) to snap?.capturedAt
        }

    // query commands

    private fun videos(
        s: DesktopServices,
        args: List<String>,
    ): Int {
        val a = Args.parse(args, valued = setOf("platform", "since"), switches = setOf("json"))
        val since =
            a.option("since")?.let { raw ->
                Period.parse(raw)?.start(clock())
                    ?: runCatching { LocalDate.parse(raw).atStartOfDayIn(s.timeZone) }.getOrNull()
                    ?: throw Args.UsageError("--since takes a date (2026-11-01) or a number of days (28d)")
            }
        val entries =
            s.queries.videos(a.singlePlatform(), since).filter { e ->
                e.video.posts.any {
                    it.platform in
                        a.platforms()
                }
            }
        return if (a.flag("json")) {
            json(QueryJson.encode(QueryJson.videos(entries, latestViews(s))))
        } else {
            out.print(text(s).videos(entries, latestViews(s)))
            0
        }
    }

    private fun stats(
        s: DesktopServices,
        args: List<String>,
    ): Int {
        val a = Args.parse(args, valued = setOf("day"), switches = setOf("json"))
        val key = a.positional.singleOrNull() ?: throw Args.UsageError("Usage: foursight stats <video> [--day 7]")
        val basis =
            when (a.option("day")) {
                null -> StatsBasis.LATEST
                "7" -> StatsBasis.DAY_7
                else -> throw Args.UsageError("--day supports 7 only (the day-7 lock)")
            }
        val e = entry(s, key) ?: return 1
        val v = s.queries.videoStats(e, basis, clock())
        return if (a.flag("json")) {
            json(QueryJson.encode(QueryJson.videoStats(v)))
        } else {
            out.print(text(s).stats(v))
            0
        }
    }

    private fun report(
        s: DesktopServices,
        args: List<String>,
    ): Int {
        val a = Args.parse(args, valued = setOf("recent"), switches = setOf("json"))
        val now = clock()
        val reports =
            when (val key = a.positional.singleOrNull()) {
                null -> {
                    if (a.positional.size > 1) throw Args.UsageError("Usage: foursight report [<video>] [--recent 7d]")
                    s.queries.recentSevenDayReports(a.int("recent", 7, 1..90), now)
                }

                else -> {
                    listOf(s.queries.sevenDayReport(entry(s, key) ?: return 1, now))
                }
            }
        if (a.flag("json")) {
            return json(QueryJson.encode(kotlinx.serialization.json.JsonArray(reports.map { QueryJson.sevenDay(it) })))
        }
        if (reports.isEmpty()) {
            out.println("No video locked its day-7 numbers in that time.")
            return 0
        }
        reports.forEach { r ->
            val first =
                r.entry.video.posts
                    .minBy { it.publishedAt }
            out.println("<!-- ${first.title?.take(80)} (${r.entry.video.posts.joinToString { it.ref.toString() }}) -->")
            out.println(SevenDayBlock.render(r))
            out.println()
        }
        return 0
    }

    private fun summary(
        s: DesktopServices,
        args: List<String>,
    ): Int {
        val a = Args.parse(args, valued = setOf("period", "platform"), switches = setOf("json"))
        val period =
            a.option("period")?.let { Period.parse(it) ?: throw Args.UsageError("--period takes 7d, 28d, 90d or all") }
                ?: Period(28)
        val summary = s.queries.accountSummary(period, clock(), a.platforms())
        return if (a.flag("json")) {
            json(QueryJson.encode(QueryJson.summary(summary)))
        } else {
            out.print(text(s).summary(summary))
            0
        }
    }

    private fun trends(
        s: DesktopServices,
        args: List<String>,
    ): Int {
        val a = Args.parse(args, valued = setOf("days", "metric", "platform"), switches = setOf("json"))
        val metrics =
            (a.option("metric") ?: "followers,views").split(',').map { id ->
                trendMetrics.firstOrNull { it.id == id.trim() }
                    ?: throw Args.UsageError("--metric takes ${trendMetrics.joinToString { it.id }}")
            }
        val t = s.queries.trends(a.int("days", 28, 1..3650), metrics, clock(), s.timeZone, a.platforms())
        return if (a.flag("json")) {
            json(QueryJson.encode(QueryJson.trends(t)))
        } else {
            out.print(text(s).trends(t))
            0
        }
    }

    private fun compare(
        s: DesktopServices,
        args: List<String>,
    ): Int {
        val a = Args.parse(args, valued = setOf("metric"), switches = setOf("json"))
        if (a.positional.size != 2) throw Args.UsageError("Usage: foursight compare <a> <b> [--metric views]")
        val first = entry(s, a.positional[0]) ?: return 1
        val second = entry(s, a.positional[1]) ?: return 1
        val metrics =
            a
                .option("metric")
                ?.split(',')
                ?.map { it.trim() }
                .orEmpty()
        val c = s.queries.compare(first, second, clock(), metrics)
        return if (a.flag("json")) {
            json(QueryJson.encode(QueryJson.comparison(c)))
        } else {
            out.print(text(s).compare(c))
            0
        }
    }

    private fun status(
        s: DesktopServices,
        args: List<String>,
    ): Int {
        val a = Args.parse(args, switches = setOf("json"))
        val f = s.queries.freshness()
        return if (a.flag("json")) {
            json(QueryJson.encode(QueryJson.freshness(f)))
        } else {
            out.print(text(s).status(f))
            0
        }
    }

    private fun audience(
        s: DesktopServices,
        args: List<String>,
    ): Int {
        val a = Args.parse(args, valued = setOf("platform", "top"), switches = setOf("json"))
        val r = s.queries.audience(clock(), a.platforms())
        return if (a.flag("json")) {
            json(QueryJson.encode(QueryJson.audience(r)))
        } else {
            out.print(text(s).audience(r, a.int("top", 10, 1..100)))
            0
        }
    }

    private fun definitions(args: List<String>): Int {
        val a = Args.parse(args, switches = setOf("json"))
        return if (a.flag("json")) {
            json(QueryJson.encode(QueryJson.definitions(Metrics.all)))
        } else {
            out.print(Text(kotlinx.datetime.TimeZone.currentSystemDefault()).definitions(Metrics.all))
            0
        }
    }

    private fun export(
        s: DesktopServices,
        args: List<String>,
    ): Int {
        val a = Args.parse(args, valued = setOf("format", "platform", "out"))
        val format =
            when (a.option("format")?.lowercase()) {
                "json" -> ExportFormat.JSON
                "csv" -> ExportFormat.CSV
                else -> throw Args.UsageError("Usage: foursight export --format json|csv [--out <file>]")
            }
        val body =
            when (format) {
                ExportFormat.JSON -> Exporter.json(s.queries, a.platforms(), clock())
                ExportFormat.CSV -> Exporter.csv(s.queries, a.platforms())
            }
        val target = a.option("out")
        if (target == null) {
            out.print(body)
        } else {
            File(target).writeText(body, Charsets.UTF_8)
            err.println("Wrote ${File(target).absolutePath}")
        }
        return 0
    }

    // matching (writes events; not available through the MCP server)

    @OptIn(ExperimentalUuidApi::class)
    private fun match(
        s: DesktopServices,
        args: List<String>,
    ): Int {
        val a = Args.parse(args, valued = setOf("video"), switches = setOf("json"))
        val now = clock()
        val newId = { Uuid.random().toString() }
        return when (a.positional.firstOrNull()) {
            null -> {
                val library = s.queries.library()
                val unmatched = library.filter { !it.confirmed }.flatMap { it.video.posts }
                val suggestions = Matching.suggest(unmatched)
                out.print(text(s).suggestions(suggestions, library.filter { it.confirmed }))
                0
            }

            "confirm" -> {
                val refs = a.positional.drop(1).map(::parseRef)
                if (refs.size < 2 && a.option("video") == null) {
                    throw Args.UsageError("Usage: foursight match confirm <post> <post> [...] [--video <id>]")
                }
                refs.forEach { if (s.queries.findPost(it) == null) throw Args.UsageError("No stored post $it") }
                val videoId = a.option("video") ?: "v-${newId()}"
                val existing = s.queries.library().firstOrNull { it.video.videoId == videoId }
                if (a.option("video") != null && existing == null) throw Args.UsageError("No matched video $videoId")
                val platforms =
                    existing
                        ?.video
                        ?.posts
                        .orEmpty()
                        .filter { it.ref !in refs }
                        .map { it.platform } +
                        refs.map { it.platform }
                if (platforms.size != platforms.toSet().size) {
                    throw Args.UsageError("A video can have only one post per platform.")
                }
                val events = Matching.confirm(refs, videoId, s.repo.deviceId(), now, newId)
                s.repo.insertEvents(events)
                publishEvents(s, now)
                out.println("Matched ${refs.joinToString()} as video $videoId")
                0
            }

            "unlink" -> {
                val ref =
                    a.positional.getOrNull(1)?.let(::parseRef)
                        ?: throw Args.UsageError("Usage: foursight match unlink <post>")
                if (ref !in MatchEvents.currentLinks(s.repo.events())) {
                    err.println("$ref isn't matched to a video.")
                    return 1
                }
                s.repo.insertEvents(listOf(Matching.unlink(ref, s.repo.deviceId(), now, newId)))
                publishEvents(s, now)
                out.println("Unlinked $ref")
                0
            }

            else -> {
                throw Args.UsageError("Usage: foursight match [confirm <post> <post>... | unlink <post>]")
            }
        }
    }

    private fun publishEvents(
        s: DesktopServices,
        now: Instant,
    ) {
        runBlocking { s.publishEvents(now) }?.let { err.println(it) }
    }

    private fun parseRef(text: String): PostRef {
        val (name, id) =
            text.split(':', limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() }
                ?: throw Args.UsageError("A post looks like platform:id, for example tiktok:7301234567")
        val platform =
            SocialPlatform.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw Args.UsageError("Unknown platform: $name")
        return PostRef(platform, id)
    }

    // sign-in and collection

    private fun connect(
        services: DesktopServices,
        args: List<String>,
    ): Int {
        val name = args.singleOrNull()?.lowercase() ?: return usage(2)
        val ok = runBlocking { signIn(services, name, { out.println(it) }, { err.println(it) }) }
        return if (ok) 0 else 1
    }

    private fun collect(
        services: DesktopServices,
        args: List<String>,
    ): Int {
        val a = Args.parse(args, valued = setOf("platform"), switches = setOf("day7-due"))
        val only = if (a.option("platform") == null) null else a.platforms()
        val day7Due = a.flag("day7-due")
        val results =
            runBlocking {
                if (day7Due) services.runner.runDay7Due() else services.runner.run(only)
            }
        if (day7Due && results.isEmpty()) {
            out.println("No post is waiting for its day-7 capture.")
            return 0
        }
        results.forEach(::report)
        if (results.any { it.ok }) writeVault(services)
        return if (results.all { it.ok }) 0 else 1
    }

    /** The automatic vault write after a collection. Problems are reported, never fatal. */
    private fun writeVault(s: DesktopServices) {
        val report =
            try {
                s.afterCollection(clock()) ?: return
            } catch (e: Exception) {
                err.println("Obsidian: not written (${e::class.simpleName})")
                return
            }
        printVault(report)
    }

    private fun printVault(r: WriteReport) {
        val written = r.notes.filter { it.outcome == NoteOutcome.WRITTEN }
        out.println("Obsidian: ${written.size} notes written, Stats Overview ${r.overview}")
        written.forEach { out.println("  wrote ${it.note.name}" + (it.detail?.let { d -> " ($d)" } ?: "")) }
        r.errors.forEach { err.println("  $it") }
    }

    private fun obsidian(
        s: DesktopServices,
        args: List<String>,
    ): Int {
        val a = Args.parse(args, valued = setOf("vault", "notes", "overview"), switches = setOf("dry-run"))
        if (a.positional.firstOrNull() == "setup") {
            val vault = a.option("vault") ?: throw Args.UsageError("Usage: foursight obsidian setup --vault <folder>")
            val dir = File(vault)
            if (!dir.isDirectory || !File(dir, ".obsidian").isDirectory) {
                err.println("$vault isn't an Obsidian vault (no .obsidian folder inside).")
                return 1
            }
            s.repo.putSetting(ObsidianSettings.VAULT, dir.absolutePath)
            a.option("notes")?.let { s.repo.putSetting(ObsidianSettings.NOTES, it) }
            a.option("overview")?.let { s.repo.putSetting(ObsidianSettings.OVERVIEW, it) }
            val settings = ObsidianSettings.load(s.repo)!!
            out.println("Vault set. Video notes: ${settings.notesFolder}. Stats Overview: ${settings.overviewNote}.")
            out.println(
                "FourSight now writes them after each collection; run: foursight obsidian --dry-run to preview.",
            )
            return 0
        }
        val writer =
            s.obsidian() ?: run {
                err.println("Set up the vault first: foursight obsidian setup --vault <folder>")
                return 1
            }
        val dry = a.flag("dry-run")
        val now = clock()
        val key = a.positional.singleOrNull()
        if (key == null) {
            val r = writer.writeAll(now, dry)
            if (dry) out.println("Dry run: nothing written.")
            r.notes.forEach {
                out.println(
                    "${it.note.name}: ${it.outcome.label}" +
                        (it.detail?.let { d -> " ($d)" } ?: ""),
                )
            }
            out.println("Stats Overview: ${r.overview}")
            r.errors.forEach { err.println(it) }
            if (r.notes.isEmpty()) {
                out.println(
                    "No video note has a tiktok_id, youtube_id, facebook_id or instagram_id yet.",
                )
            }
            return if (r.errors.isEmpty()) 0 else 1
        }
        val note =
            writer.notes().firstOrNull {
                it.name.equals(key, ignoreCase = true) ||
                    it.refs.any { r -> r.toString() == key }
            }
                ?: run {
                    err.println("No video note named \"$key\" with post ids (see: foursight obsidian --dry-run).")
                    return 1
                }
        val r = writer.write(note, now, dry)
        out.println(
            "${note.name}: ${r.outcome.label}" + (r.detail?.let { " ($it)" } ?: ""),
        )
        if (!dry) out.println("Stats Overview: ${writer.writeOverview(writer.notes(), now)}")
        return 0
    }

    private fun report(r: PlatformRunResult) {
        if (r.ok) {
            out.println("${r.platform.displayName}: ${r.posts} posts, ${r.newSnapshots} new snapshots")
        } else {
            err.println("${r.error}\n  ${r.failure?.advice?.replace("<platform>", r.platform.name.lowercase())}")
        }
        r.warnings.forEach { err.println("  warning: $it") }
    }

    private fun usage(code: Int): Int {
        (if (code == 0) out else err).println(
            """
            Usage: foursight <command> [options]     (add --json for JSON output)

            Numbers
              videos [--platform <p>] [--since 28d|<date>]   Every video, grouped across platforms
              stats <video> [--day 7]                        One video's numbers, latest or at day 7
              report [<video>] [--recent 7d]                 The Playbook's 7-day block, ready to paste
              summary [--period 28d|all] [--platform <p>]    Followers, growth, best and weakest videos
              trends [--days 28] [--metric followers,views]  Day-by-day totals and changes
              compare <a> <b> [--metric views]               Two videos side by side
              audience [--platform <p>] [--top 10]           Who watches or follows, and where views came from
              status                                         Last refresh, errors and limits per platform
              definitions                                    How every number is calculated
              export --format json|csv [--out <file>]        Everything stored (no secrets)

            Changes
              collect [--platform <p>] [--day7-due]          Collect now (refresh does the same)
              match                                          Suggested cross-platform matches
              match confirm <post> <post>... [--video <id>]  Link posts as one video
              match unlink <post>                            Undo a link
              obsidian [<note>] [--dry-run]                  Write the 7-day block and stats fields to the vault
              obsidian setup --vault <folder>                Tell FourSight where the vault is
              connect <name>                                 Sign in: tiktok, youtube, drive, meta
              mcp                                            Run the MCP server for Claude (stdio)

            A <video> is a video id, or a post like tiktok:7301234567. Platforms: tiktok, youtube,
            facebook, instagram (comma-separated for several).
            """.trimIndent(),
        )
        return code
    }
}
