package io.github.rmac0001.foursight.desktop.obsidian

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Day7Result
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.PostRef
import io.github.rmac0001.foursight.core.model.Video
import io.github.rmac0001.foursight.core.query.Format
import io.github.rmac0001.foursight.core.query.LibraryEntry
import io.github.rmac0001.foursight.core.query.PostSevenDay
import io.github.rmac0001.foursight.core.query.QueryService
import io.github.rmac0001.foursight.core.query.SevenDayBlock
import io.github.rmac0001.foursight.core.query.SevenDayReport
import io.github.rmac0001.foursight.core.store.Repository
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.time.Instant

/** Where the vault is and which notes FourSight may touch. Stored as local settings, never in the repo. */
data class ObsidianSettings(
    val vault: Path,
    /** Folder inside the vault that holds the video notes. */
    val notesFolder: String,
    /** The Stats Overview note, inside the vault. FourSight owns this one note completely. */
    val overviewNote: String,
) {
    val notesDir: Path get() = vault.resolve(notesFolder)
    val overviewPath: Path get() = vault.resolve(overviewNote)

    companion object {
        const val VAULT = "obsidian.vault"
        const val NOTES = "obsidian.notes_folder"
        const val OVERVIEW = "obsidian.overview_note"
        const val DEFAULT_NOTES = "Social Media Content"
        const val DEFAULT_OVERVIEW = "Social Media Content/Stats Overview.md"

        fun load(repo: Repository): ObsidianSettings? {
            val vault = repo.setting(VAULT) ?: return null
            return ObsidianSettings(
                Path.of(vault),
                repo.setting(NOTES) ?: DEFAULT_NOTES,
                repo.setting(OVERVIEW) ?: DEFAULT_OVERVIEW,
            )
        }
    }
}

/** A video note: one with at least one post id in its frontmatter. */
data class VideoNote(
    val path: Path,
    val name: String,
    val refs: List<PostRef>,
)

enum class NoteOutcome { WRITTEN, UNCHANGED, NOT_DUE, NO_POSTS_COLLECTED }

data class NoteResult(
    val note: VideoNote,
    val outcome: NoteOutcome,
    val detail: String? = null,
)

data class WriteReport(
    val notes: List<NoteResult>,
    val overview: String,
    val errors: List<String>,
)

/**
 * The Obsidian writer (spec: Obsidian writer; build order step 5). After the day-7 lock it writes
 * the Playbook's 7-day block and the note's stats fields, and regenerates the Stats Overview note.
 * It only touches notes that carry a post id (`tiktok_id`, `youtube_id`, `facebook_id`,
 * `instagram_id`; Roger or Claude adds them when a video is posted), only its own fields and block
 * in them, and the one overview note it owns. Re-running it rewrites the same values.
 */
class ObsidianWriter(
    private val queries: QueryService,
    private val settings: ObsidianSettings,
    private val zone: TimeZone,
) {
    fun notes(): List<VideoNote> {
        val dir = settings.notesDir
        if (!Files.isDirectory(dir)) return emptyList()
        return Files
            .walk(dir)
            .use { paths ->
                paths
                    .filter { it.isRegularFile() && it.extension == "md" && it != settings.overviewPath }
                    .toList()
            }.mapNotNull { path ->
                val fields = NoteEditor.frontmatter(path.readText())
                val refs =
                    ID_FIELDS.mapNotNull { (key, platform) ->
                        fields[key]?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.let { PostRef(platform, it) }
                    }
                if (refs.isEmpty()) null else VideoNote(path, path.nameWithoutExtension, refs)
            }.sortedBy { it.path }
    }

    /** The note's 7-day report, built from the posts its ids name (the note defines the video). */
    fun report(
        note: VideoNote,
        now: Instant,
    ): SevenDayReport? {
        val posts = note.refs.mapNotNull { queries.findPost(it) }
        if (posts.isEmpty()) return null
        return queries.sevenDayReport(LibraryEntry(Video("note:${note.name}", posts), confirmed = true), now)
    }

    fun write(
        note: VideoNote,
        now: Instant,
        dryRun: Boolean = false,
    ): NoteResult {
        val report =
            report(note, now)
                ?: return NoteResult(note, NoteOutcome.NO_POSTS_COLLECTED, "None of its ids are collected yet.")
        val missing = note.refs.filter { queries.findPost(it) == null }
        if (report.posts.none { it.result is Day7Result.Locked }) {
            return NoteResult(note, NoteOutcome.NOT_DUE, "No platform has its day-7 numbers yet.")
        }
        val before = note.path.readText()
        val after =
            NoteEditor.upsertBlock(
                NoteEditor.setFields(before, SevenDayBlock.fields(report)),
                SevenDayBlock.render(report),
            )
        val detail = if (missing.isEmpty()) null else "Not collected yet: ${missing.joinToString()}"
        if (after == before) return NoteResult(note, NoteOutcome.UNCHANGED, detail)
        if (!dryRun) atomicWrite(note.path, after)
        return NoteResult(note, NoteOutcome.WRITTEN, detail)
    }

    /** Writes every video note that has day-7 numbers, then the Stats Overview note. */
    fun writeAll(
        now: Instant,
        dryRun: Boolean = false,
    ): WriteReport {
        val errors = mutableListOf<String>()
        val notes = notes()
        val results =
            notes.mapNotNull { note ->
                try {
                    write(note, now, dryRun)
                } catch (e: java.io.IOException) {
                    errors += "${note.name}: couldn't write (${e::class.simpleName})"
                    null
                }
            }
        val overview =
            try {
                writeOverview(notes, now, dryRun)
            } catch (e: java.io.IOException) {
                errors += "Stats Overview: couldn't write (${e::class.simpleName})"
                "not written"
            }
        return WriteReport(results, overview, errors)
    }

    /**
     * Regenerates the Stats Overview note. It refuses to replace a note FourSight didn't write (one
     * without its marker line), so an existing hand-made note is never lost.
     */
    fun writeOverview(
        notes: List<VideoNote>,
        now: Instant,
        dryRun: Boolean = false,
    ): String {
        val path = settings.overviewPath
        if (Files.exists(path) && MARKER !in path.readText()) {
            return "skipped: ${settings.overviewNote} exists and wasn't written by FourSight"
        }
        val text = overview(notes, now)
        val current = if (Files.exists(path)) path.readText() else null
        // The generated time changes every run; compare without it so an unchanged table isn't rewritten.
        if (current != null && current.withoutGenerated() == text.withoutGenerated()) return "unchanged"
        if (!dryRun) {
            path.parent?.let { Files.createDirectories(it) }
            atomicWrite(path, text)
        }
        return if (current == null) "created" else "updated"
    }

    private fun String.withoutGenerated() = lines().filterNot { it.startsWith(GENERATED_PREFIX) }.joinToString("\n")

    fun overview(
        notes: List<VideoNote>,
        now: Instant,
    ): String {
        val rows =
            notes
                .mapNotNull { n -> report(n, now)?.let { n to it } }
                .filter { (_, r) -> r.posts.any { it.result is Day7Result.Locked } }
                .sortedByDescending { (_, r) -> r.entry.video.firstPublishedAt }
        val platforms = SocialPlatform.entries
        return buildString {
            appendLine("---")
            appendLine("type: stats-overview")
            appendLine("---")
            appendLine("# Stats Overview")
            appendLine()
            appendLine(MARKER)
            appendLine("$GENERATED_PREFIX${local(now)}, definitions v${Metrics.DEFINITIONS_VERSION}._")
            appendLine()
            appendLine("## Followers")
            appendLine()
            appendLine("| Platform | Followers | Captured |")
            appendLine("| --- | --- | --- |")
            queries.freshness().forEach { f ->
                val account = queries.latestAccount(f.platform)
                val followers = account?.value(Metrics.FOLLOWERS.id)
                appendLine(
                    "| ${f.platform.displayName} | ${Format.number(
                        followers,
                    )} | ${account?.let { local(it.capturedAt) } ?: "-"} |",
                )
            }
            appendLine()
            appendLine("## 7-day scoreboard")
            appendLine()
            if (rows.isEmpty()) {
                appendLine("No video note with post ids has day-7 numbers yet.")
            } else {
                val cols = platforms.map { SevenDayBlock.label(it) }
                appendLine(
                    "| Published | Video | Views (${cols.joinToString(
                        " / ",
                    )}) | Followers per 1,000 (Shorts / Reels) | " +
                        "Shares per 1,000 (${cols.joinToString(" / ")}) |",
                )
                appendLine("| --- | --- | --- | --- | --- |")
                rows.forEach { (note, r) ->
                    val byPlatform = r.posts.associateBy { it.post.platform }

                    fun cell(
                        p: SocialPlatform,
                        show: (PostSevenDay) -> String,
                    ): String {
                        val d = byPlatform[p] ?: return " "
                        return if (d.result is Day7Result.Locked) show(d) else "pending"
                    }
                    val views =
                        platforms.joinToString(
                            " / ",
                        ) { p -> cell(p) { Format.number(it.values[Metrics.VIEWS.id]) } }
                    val follows =
                        listOf(SocialPlatform.YOUTUBE, SocialPlatform.FACEBOOK).joinToString(" / ") { p ->
                            cell(p) { Format.number(it.followersPer1k?.value, 1) }
                        }
                    val shares =
                        platforms.joinToString(
                            " / ",
                        ) { p -> cell(p) { Format.number(it.sharesPer1k?.value, 1) } }
                    val published =
                        r.entry.video.firstPublishedAt
                            ?.let { it.toLocalDateTime(zone).date.toString() } ?: "-"
                    appendLine("| $published | [[${note.name}]] | $views | $follows | $shares |")
                }
            }
            appendLine()
            appendLine(
                "_'-' is missing; a blank cell means the video wasn't posted there; 'pending' means no day-7 " +
                    "numbers yet. TikTok and Instagram don't report followers per video. Rates are never combined " +
                    "across platforms._",
            )
        }
    }

    private fun local(at: Instant): String {
        val t = at.toLocalDateTime(zone)
        return "${t.date} ${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
    }

    private fun atomicWrite(
        path: Path,
        text: String,
    ) {
        val tmp = path.resolveSibling(".${path.fileName}.foursight.tmp")
        Files.writeString(tmp, text)
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** A note's path inside the vault, for messages. */
    fun relative(note: VideoNote): String = note.path.relativeTo(settings.vault).toString()

    companion object {
        const val MARKER = "<!-- FourSight writes this note; edits here are replaced. -->"
        const val GENERATED_PREFIX = "_Generated "

        val ID_FIELDS =
            listOf(
                "tiktok_id" to SocialPlatform.TIKTOK,
                "youtube_id" to SocialPlatform.YOUTUBE,
                "facebook_id" to SocialPlatform.FACEBOOK,
                "instagram_id" to SocialPlatform.INSTAGRAM,
            )
    }
}
