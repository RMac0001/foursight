package io.github.rmac0001.foursight.desktop

import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.ContentType
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.model.PlatformPost
import io.github.rmac0001.foursight.core.model.Snapshot
import io.github.rmac0001.foursight.core.secrets.InMemorySecretStore
import io.github.rmac0001.foursight.core.secrets.SecretName
import io.github.rmac0001.foursight.core.store.DesktopDatabase
import io.github.rmac0001.foursight.core.store.Repository
import io.github.rmac0001.foursight.core.sync.MatchEvents
import io.github.rmac0001.foursight.desktop.cli.Cli
import io.github.rmac0001.foursight.desktop.mcp.FourSightMcp
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// TEST DATA: made-up posts and numbers, not real analytics.
class CliAndMcpTest {
    private val published = Instant.parse("2026-11-01T15:00:00Z")
    private var now = published + 10.days
    private val repo = Repository(DesktopDatabase.inMemory())
    private val secrets =
        InMemorySecretStore().apply {
            // A fake value: tests check that nothing secret ever appears in output.
            write(SecretName.TIKTOK_REFRESH_TOKEN, "FAKE-REFRESH-TOKEN-FOR-TESTS")
        }
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    private fun services() = DesktopServices(repo, secrets)

    private fun cli() = Cli(PrintStream(out, true), PrintStream(err, true), ::services) { now }

    private fun run(vararg args: String): Int {
        out.reset()
        err.reset()
        return cli().run(args.toList())
    }

    private val stdout get() = out.toString(Charsets.UTF_8)
    private val stderr get() = err.toString(Charsets.UTF_8)

    private fun post(
        platform: SocialPlatform,
        id: String,
        at: Instant,
        title: String,
    ) = PlatformPost(platform, id, title, at, null, 45, ContentType.SHORT_FORM, null)

    private fun snap(
        platform: SocialPlatform,
        id: String,
        at: Instant,
        values: Map<String, Double?>,
        kind: ItemKind = ItemKind.POST,
    ) = Snapshot(
        repo.deviceId(),
        platform,
        kind,
        id,
        at,
        at.toLocalDateTime(TimeZone.UTC).date,
        Metrics.DEFINITIONS_VERSION,
        buildJsonObject { put("fixture", "TEST DATA - not real analytics") },
        values,
    )

    private fun seed() {
        repo.upsertPosts(
            listOf(
                post(SocialPlatform.TIKTOK, "111", published, "Fixture weigh-in week 29"),
                post(SocialPlatform.FACEBOOK, "222", published + 4.minutes, "Fixture weigh-in week 29"),
            ),
            published,
        )
        repo.insertSnapshots(
            listOf(
                snap(SocialPlatform.TIKTOK, "me", published - 1.hours, mapOf("followers" to 100.0), ItemKind.ACCOUNT),
                snap(
                    SocialPlatform.TIKTOK,
                    "111",
                    published + 7.days + 1.hours,
                    mapOf(
                        "views" to 1000.0,
                        "likes" to 10.0,
                        "comments" to 1.0,
                        "shares" to 2.0,
                    ),
                ),
                snap(
                    SocialPlatform.FACEBOOK,
                    "222",
                    published + 7.days + 1.hours,
                    mapOf(
                        "views" to 500.0,
                        "shares" to 1.0,
                        "followers_gained" to 2.0,
                    ),
                ),
            ),
        )
    }

    @Test
    fun helpListsTheCommands() {
        assertEquals(0, run("help"))
        listOf(
            "videos",
            "stats",
            "report",
            "summary",
            "trends",
            "compare",
            "audience",
            "status",
            "definitions",
            "export",
            "match",
            "mcp",
        ).forEach { assertTrue(stdout.contains("  $it"), "help mentions $it") }
    }

    @Test
    fun badOptionsAreUsageErrors() {
        assertEquals(2, run("summary", "--period", "soon"))
        assertTrue(stderr.contains("--period"))
        assertEquals(2, run("videos", "--platform", "myspace"))
        assertEquals(2, run("stats", "111", "--day", "3"))
        assertEquals(2, run("nonsense"))
    }

    @Test
    fun videosJsonGroupsNothingUntilConfirmed() {
        seed()
        assertEquals(0, run("videos", "--json"))
        val json = Json.parseToJsonElement(stdout).jsonObject
        assertEquals(2, json["count"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun statsForAnUnknownVideoFails() {
        seed()
        assertEquals(1, run("stats", "tiktok:999"))
        assertTrue(stderr.contains("No video matches"))
    }

    @Test
    fun reportPrintsThePlaybookBlock() {
        seed()
        assertEquals(0, run("report", "tiktok:111"))
        assertTrue(stdout.contains("## 7-day numbers ("))
        assertTrue(stdout.contains("- Views (TikTok): 1,000"))
        assertEquals(0, run("report"))
        assertTrue(stdout.contains("## 7-day numbers"), "recent reports include it")
    }

    @Test
    fun matchConfirmAndUnlinkWriteEventsOnly() {
        seed()
        assertEquals(0, run("match"))
        assertTrue(stdout.contains("foursight match confirm tiktok:111 facebook:222"))
        assertTrue(repo.events().isEmpty(), "suggestions write nothing")

        assertEquals(0, run("match", "confirm", "tiktok:111", "facebook:222"))
        assertTrue(stderr.contains("Drive isn't connected"))
        val links = MatchEvents.currentLinks(repo.events())
        assertEquals(1, links.values.toSet().size)
        assertEquals(0, run("stats", "tiktok:111", "--day", "7", "--json"))
        val platforms = Json.parseToJsonElement(stdout).jsonObject["platforms"]!!.jsonArray
        assertEquals(2, platforms.size)

        assertEquals(2, run("match", "confirm", "tiktok:111", "tiktok:333"), "unknown post")
        now += 1.minutes // events are ordered by time; the unlink comes later
        assertEquals(0, run("match", "unlink", "facebook:222"))
        assertEquals(1, MatchEvents.currentLinks(repo.events()).size)
        assertEquals(1, run("match", "unlink", "facebook:222"), "already unlinked")
    }

    @Test
    fun exportCsvHasNoSecrets() {
        seed()
        assertEquals(0, run("export", "--format", "csv"))
        assertTrue(stdout.startsWith("platform,level,item_id"))
        assertFalse(stdout.contains("FAKE-REFRESH-TOKEN"))
        assertEquals(2, run("export"))
    }

    private fun text(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult) =
        (result.content.single() as TextContent).text

    @Test
    fun mcpOffersTheSpecToolsAndNothingThatEdits() {
        val mcp = FourSightMcp(services()) { now }
        assertEquals(
            listOf(
                "list_videos",
                "get_video_stats",
                "get_seven_day_report",
                "get_account_summary",
                "get_trends",
                "compare_videos",
                "get_audience",
                "get_freshness",
                "get_metric_definitions",
                "refresh",
                "write_obsidian_stats",
                "export",
            ),
            mcp.tools.map { it.name },
        )
        assertEquals(listOf("refresh", "write_obsidian_stats"), mcp.tools.filter { !it.readOnly }.map { it.name })
    }

    @Test
    fun mcpToolsReturnTheSameJsonAsTheCli() {
        seed()
        val mcp = FourSightMcp(services()) { now }
        val stats =
            runBlocking {
                mcp.call("get_video_stats", buildJsonObject { put("video", "tiktok:111") })
            }
        assertEquals(null, stats.isError)
        assertEquals(0, run("stats", "tiktok:111", "--json"))
        assertEquals(stdout.trim(), text(stats).trim())
    }

    @Test
    fun mcpErrorsAreToolErrorsWithPlainMessages() {
        seed()
        val mcp = FourSightMcp(services()) { now }
        runBlocking {
            val missing = mcp.call("get_video_stats", JsonObject(emptyMap()))
            assertEquals(true, missing.isError)
            assertTrue(text(missing).contains("Name a video"))
            val badPlatform =
                mcp.call(
                    "list_videos",
                    buildJsonObject {
                        putJsonArray("platforms") { add(kotlinx.serialization.json.JsonPrimitive("myspace")) }
                    },
                )
            assertEquals(true, badPlatform.isError)
            val unknown = mcp.call("delete_post", null)
            assertEquals(true, unknown.isError)
        }
    }

    @Test
    fun mcpOutputNeverContainsSecrets() {
        seed()
        val mcp = FourSightMcp(services()) { now }
        runBlocking {
            mcp.tools.filter { it.readOnly }.forEach { t ->
                val args =
                    when (t.name) {
                        "get_video_stats" -> {
                            buildJsonObject { put("video", "tiktok:111") }
                        }

                        "compare_videos" -> {
                            buildJsonObject {
                                put("a", "tiktok:111")
                                put("b", "facebook:222")
                            }
                        }

                        else -> {
                            null
                        }
                    }
                val r = mcp.call(t.name, args)
                assertEquals(null, r.isError, "${t.name}: ${text(r)}")
                assertFalse(text(r).contains("FAKE-REFRESH-TOKEN"), t.name)
            }
        }
    }
}
