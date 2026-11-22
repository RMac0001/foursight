package io.github.rmac0001.foursight.desktop

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.ContentType
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.model.Snapshot
import io.github.rmac0001.foursight.core.query.QueryService
import io.github.rmac0001.foursight.core.store.DesktopDatabase
import io.github.rmac0001.foursight.core.store.Repository
import io.github.rmac0001.foursight.desktop.obsidian.NoteEditor
import io.github.rmac0001.foursight.desktop.obsidian.NoteOutcome
import io.github.rmac0001.foursight.desktop.obsidian.ObsidianSettings
import io.github.rmac0001.foursight.desktop.obsidian.ObsidianWriter
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

// TEST DATA: a made-up vault, notes, posts and numbers.
class ObsidianTest {
    private val published = Instant.parse("2026-11-01T15:00:00Z")
    private val now = published + 10.days
    private val vault: Path = Files.createTempDirectory("foursight-test-vault")
    private val repo = Repository(DesktopDatabase.inMemory())
    private val queries = QueryService(repo)
    private val settings = ObsidianSettings(vault, "Videos", "Videos/Stats Overview.md")
    private val writer = ObsidianWriter(queries, settings, TimeZone.UTC)

    @AfterTest
    fun cleanUp() {
        vault.toFile().deleteRecursively()
    }

    private fun seed() {
        repo.upsertPosts(
            listOf(
                PlatformPost(
                    SocialPlatform.TIKTOK,
                    "111",
                    "Fixture",
                    published,
                    null,
                    40,
                    ContentType.SHORT_FORM,
                    null,
                ),
                PlatformPost(
                    SocialPlatform.YOUTUBE,
                    "yt1",
                    "Fixture",
                    published + 1.hours,
                    null,
                    40,
                    ContentType.SHORT_FORM,
                    null,
                ),
            ),
            published,
        )

        fun snap(
            p: SocialPlatform,
            id: String,
            at: Instant,
            v: Map<String, Double?>,
        ) = Snapshot(
            repo.deviceId(),
            p,
            ItemKind.POST,
            id,
            at,
            at.toLocalDateTime(TimeZone.UTC).date,
            Metrics.DEFINITIONS_VERSION,
            buildJsonObject { put("fixture", "TEST DATA") },
            v,
        )
        repo.insertSnapshots(
            listOf(
                snap(
                    SocialPlatform.TIKTOK,
                    "111",
                    published + 7.days + 1.hours,
                    mapOf(
                        "views" to 2000.0,
                        "shares" to 10.0,
                    ),
                ),
                snap(
                    SocialPlatform.YOUTUBE,
                    "yt1",
                    published + 7.days + 2.hours,
                    mapOf("views" to 1000.0, "shares" to 1.0, "followers_gained" to 4.0),
                ),
            ),
        )
    }

    private fun note(
        name: String,
        text: String,
    ): Path {
        val dir = vault.resolve("Videos").createDirectories()
        return dir.resolve("$name.md").also { it.writeText(text) }
    }

    private val noteText =
        """
        |---
        |status: posted
        |publish_date: 2026-11-01
        |tiktok_id: "111"
        |youtube_id: yt1
        |posted:
        |  - tiktok
        |  - shorts
        |---
        |# Fixture video
        |
        |Body text stays.
        |
        |## 7-day numbers (old)
        |- Views (TikTok): 5
        |- Most viewers stopped at: 0:04, after the hook
        |- Comments with substance (count + best example):
        |- One lesson: Lead with the number.
        |- My own extra line
        |
        |## Notes
        |Keep me.
        |
        """.trimMargin()

    @Test
    fun writesFieldsAndBlockAndKeepsRogersLines() {
        seed()
        val path = note("Fixture video", noteText)
        val r = writer.writeAll(now)
        assertEquals(NoteOutcome.WRITTEN, r.notes.single().outcome)
        val text = path.readText()
        val fields = NoteEditor.frontmatter(text)
        assertEquals("2000", fields["views_tiktok"])
        assertEquals("1000", fields["views_shorts"])
        assertEquals("4.0", fields["followers_per_1k_shorts"])
        assertEquals("5.0", fields["shares_per_1k_tiktok"])
        assertFalse("followers_per_1k_tiktok" in fields, "TikTok doesn't report followers per video")
        assertEquals("2026-11-08", fields["stats_day7_date"])
        assertTrue(text.contains("posted:\n  - tiktok\n  - shorts\n"), "other frontmatter untouched")
        assertTrue(text.contains("Body text stays."))
        assertTrue(text.contains("## 7-day numbers (2026-11-08)"))
        assertTrue(text.contains("- Views (TikTok / Shorts): 2,000 / 1,000"))
        assertTrue(text.contains("- Most viewers stopped at: 0:04, after the hook"), "Roger's line kept")
        assertTrue(text.contains("- One lesson: Lead with the number."))
        assertTrue(text.contains("- Comments with substance (count + best example): \n"), "blank stays blank")
        assertTrue(text.contains("- My own extra line"))
        assertTrue(text.contains("\n## Notes\nKeep me.\n"), "the next section is untouched")
        assertFalse(text.contains("- Views (TikTok): 5"), "the old numbers are replaced")
        assertTrue(r.overview == "created")
    }

    @Test
    fun runningTwiceChangesNothing() {
        seed()
        val path = note("Fixture video", noteText)
        writer.writeAll(now)
        val first = path.readText()
        val overview = settings.overviewPath.readText()
        val again = writer.writeAll(now + 1.hours)
        assertEquals(NoteOutcome.UNCHANGED, again.notes.single().outcome)
        assertEquals("unchanged", again.overview)
        assertEquals(first, path.readText())
        assertEquals(overview, settings.overviewPath.readText())
    }

    @Test
    fun notesWithoutIdsOrDay7AreLeftAlone() {
        seed()
        val plain = note("No ids", "---\nstatus: idea\n---\n# Idea\n")
        val young = note("Young", "---\ntiktok_id: 999\n---\n# Young\n")
        repo.upsertPosts(
            listOf(
                PlatformPost(
                    SocialPlatform.TIKTOK,
                    "999",
                    "Young",
                    now - 1.days,
                    null,
                    40,
                    ContentType.SHORT_FORM,
                    null,
                ),
            ),
            now,
        )
        val r = writer.writeAll(now)
        assertEquals(listOf("Young"), r.notes.map { it.note.name })
        assertEquals(NoteOutcome.NO_DAY7, r.notes.single().outcome)
        assertEquals("TikTok not 7 days old", r.notes.single().detail)
        assertEquals("---\nstatus: idea\n---\n# Idea\n", plain.readText())
        assertEquals("---\ntiktok_id: 999\n---\n# Young\n", young.readText())
    }

    @Test
    fun aHandMadeStatsOverviewIsNeverReplaced() {
        seed()
        note("Fixture video", noteText)
        settings.overviewPath.writeText("# My own overview\n")
        val r = writer.writeAll(now)
        assertTrue(r.overview.startsWith("skipped"))
        assertEquals("# My own overview\n", settings.overviewPath.readText())
    }

    @Test
    fun overviewListsDay7VideosWithoutCombiningRates() {
        seed()
        note("Fixture video", noteText)
        writer.writeAll(now)
        val text = settings.overviewPath.readText()
        assertTrue(text.contains(ObsidianWriter.MARKER))
        assertTrue(
            text.contains("| 2026-11-01 | [[Fixture video]] | 2,000 / 1,000 /   /   | 4.0 /   | 5.0 / 1.0 /   /   |"),
        )
    }

    @Test
    fun editorAddsFrontmatterAndBlockWhenMissingAndKeepsCrlf() {
        val crlf = "# Title\r\nText\r\n"
        val withFields = NoteEditor.setFields(crlf, mapOf("views_tiktok" to "5", "stats_day7_date" to null))
        assertEquals("---\r\nviews_tiktok: 5\r\nstats_day7_date:\r\n---\r\n# Title\r\nText\r\n", withFields)
        val withBlock = NoteEditor.upsertBlock("# Title\nText\n", "## 7-day numbers (x)\n- Views (TikTok): 5")
        assertEquals("# Title\nText\n\n## 7-day numbers (x)\n- Views (TikTok): 5\n", withBlock)
        assertEquals(withBlock, NoteEditor.upsertBlock(withBlock, "## 7-day numbers (x)\n- Views (TikTok): 5"))
    }
}
