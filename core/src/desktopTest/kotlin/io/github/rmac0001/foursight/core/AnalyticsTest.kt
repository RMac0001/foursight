package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.Fixtures.published
import io.github.rmac0001.foursight.core.SocialPlatform.FACEBOOK
import io.github.rmac0001.foursight.core.SocialPlatform.INSTAGRAM
import io.github.rmac0001.foursight.core.SocialPlatform.TIKTOK
import io.github.rmac0001.foursight.core.SocialPlatform.YOUTUBE
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.query.Exporter
import io.github.rmac0001.foursight.core.query.Format
import io.github.rmac0001.foursight.core.query.MatchConfidence
import io.github.rmac0001.foursight.core.query.Matching
import io.github.rmac0001.foursight.core.query.Period
import io.github.rmac0001.foursight.core.query.QueryJson
import io.github.rmac0001.foursight.core.query.QueryService
import io.github.rmac0001.foursight.core.query.SevenDayBlock
import io.github.rmac0001.foursight.core.query.StatsBasis
import io.github.rmac0001.foursight.core.query.accountSummary
import io.github.rmac0001.foursight.core.query.compare
import io.github.rmac0001.foursight.core.query.recentSevenDayReports
import io.github.rmac0001.foursight.core.query.trends
import io.github.rmac0001.foursight.core.query.videoStats
import io.github.rmac0001.foursight.core.query.videos
import io.github.rmac0001.foursight.core.sync.MatchEvents
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// TEST DATA: made-up posts and numbers, not real analytics.
class AnalyticsTest {
    private val repo = Fixtures.repo()
    private val queries = QueryService(repo)
    private val device by lazy { repo.deviceId() }

    private val tiktok = Fixtures.post(TIKTOK, "tt-1")
    private val short = Fixtures.post(YOUTUBE, "yt-1", published + 1.hours)
    private val now = published + 10.days

    private fun snap(
        platform: SocialPlatform,
        id: String,
        at: kotlin.time.Instant,
        values: Map<String, Double?>,
        kind: ItemKind = ItemKind.POST,
    ) = Fixtures.snapshot(device, platform, id, at, values, kind)

    private fun seed() {
        repo.upsertPosts(listOf(tiktok, short), published)
        repo.insertSnapshots(
            listOf(
                snap(TIKTOK, "me", published - 2.hours, mapOf("followers" to 1000.0), ItemKind.ACCOUNT),
                snap(
                    TIKTOK,
                    "tt-1",
                    published + 1.days,
                    mapOf(
                        "views" to 1000.0,
                        "likes" to 50.0,
                        "comments" to 5.0,
                        "shares" to 5.0,
                    ),
                ),
                snap(
                    TIKTOK,
                    "tt-1",
                    published + 7.days + 2.hours,
                    mapOf(
                        "views" to 4000.0,
                        "likes" to 200.0,
                        "comments" to 20.0,
                        "shares" to 20.0,
                    ),
                ),
                snap(TIKTOK, "me", published + 7.days + 2.hours, mapOf("followers" to 1060.0), ItemKind.ACCOUNT),
                snap(
                    TIKTOK,
                    "tt-1",
                    published + 9.days,
                    mapOf(
                        "views" to 5000.0,
                        "likes" to 250.0,
                        "comments" to 25.0,
                        "shares" to 30.0,
                    ),
                ),
                snap(TIKTOK, "me", published + 9.days, mapOf("followers" to 1080.0), ItemKind.ACCOUNT),
                snap(
                    YOUTUBE,
                    "yt-1",
                    short.publishedAt + 7.days + 3.hours,
                    mapOf(
                        "views" to 2000.0,
                        "likes" to 40.0,
                        "comments" to 4.0,
                        "shares" to 4.0,
                        "followers_gained" to 10.0,
                    ),
                ),
                snap(
                    YOUTUBE,
                    "yt-1",
                    published + 9.days,
                    mapOf(
                        "views" to 2500.0,
                        "likes" to 45.0,
                        "comments" to null,
                        "shares" to 6.0,
                        "followers_gained" to 12.0,
                    ),
                ),
            ),
        )
    }

    private fun link() =
        repo.insertEvents(
            listOf(
                MatchEvents.link("e1", device, published, "video-1", tiktok.ref),
                MatchEvents.link("e2", device, published, "video-1", short.ref),
            ),
        )

    @Test
    fun latestStatsKeepMissingAsNullAndListUnavailableMetrics() {
        seed()
        link()
        val v = queries.videoStats(queries.findEntry("video-1")!!, StatsBasis.LATEST, now)
        val yt = v.posts.single { it.post.platform == YOUTUBE }
        assertEquals(2500.0, yt.values["views"])
        assertTrue("comments" in yt.values)
        assertNull(yt.values["comments"], "missing stays null, never 0")
        val tt = v.posts.single { it.post.platform == TIKTOK }
        assertTrue("saves" in tt.unavailable)
        assertTrue("followers_gained" in tt.unavailable)
        assertTrue(tt.computed.none { it.id == "followers_per_1k" })
        assertEquals(30.0 / 5000.0 * 1000.0, tt.computed.single { it.id == "shares_per_1k" }.value)
        // Totals: views sum; comments missing on YouTube makes the comment total missing, not partial.
        assertEquals(7500.0, v.totals.single { it.id == "views_total" }.value)
        val comments = v.totals.single { it.id == "comments_total" }
        assertNull(comments.value)
        assertNotNull(comments.missingReason)
        // Saves aren't reported by either platform, so there's no saves total at all.
        assertTrue(v.totals.none { it.id == "saves_total" })
    }

    @Test
    fun day7StatsUseTheLockedCaptureWithItsExactAge() {
        seed()
        link()
        val v = queries.videoStats(queries.findEntry("tiktok:tt-1")!!, StatsBasis.DAY_7, now)
        val tt = v.posts.single { it.post.platform == TIKTOK }
        assertEquals(4000.0, tt.values["views"])
        assertEquals(7.0 + 2.0 / 24.0, tt.ageDays!!, 1e-9)
        assertEquals(60.0, tt.accountFollowerChange?.value)
        val yt = v.posts.single { it.post.platform == YOUTUBE }
        assertEquals(5.0, yt.computed.single { it.id == "followers_per_1k" }.value)
    }

    @Test
    fun day7StatsExplainWhyThereAreNoNumbersYet() {
        repo.upsertPosts(listOf(tiktok), published)
        val v = queries.videoStats(queries.findEntry("tt-1")!!, StatsBasis.DAY_7, published + 3.days)
        val tt = v.posts.single()
        assertNull(tt.capturedAt)
        assertEquals("Not 7 days old yet.", tt.noCaptureReason)
        assertTrue(tt.values.values.all { it == null })
    }

    @Test
    fun videosFilterBySinceAndPlatform() {
        seed()
        assertEquals(2, queries.videos().size)
        assertEquals(1, queries.videos(YOUTUBE).size)
        assertEquals(1, queries.videos(since = published + 30.minutes).size)
    }

    @Test
    fun summaryCountsGrowthOnlyFromRealBaselines() {
        seed()
        // Period starts at day 8: TikTok's day-7 capture is the baseline, YouTube's too.
        val s = queries.accountSummary(Period(2), now)
        val t = s.platforms.single { it.platform == TIKTOK }
        assertEquals(1080.0, t.followers)
        assertEquals(20.0, t.followerChange.value)
        assertEquals(1000.0, t.gained.single { it.id == "views_gained" }.value)
        assertEquals(0, t.postsWithoutBaseline)
        val y = s.platforms.single { it.platform == YOUTUBE }
        assertEquals(500.0, y.gained.single { it.id == "views_gained" }.value)
        assertNull(y.gained.single { it.id == "comments_gained" }.value, "a missing count makes the gain missing")
        assertNull(y.followerChange.value)
        assertNotNull(y.followerChange.missingReason)
        // Instagram has no posts: nothing to count, shown as missing rather than 0.
        val ig = s.platforms.single { it.platform == INSTAGRAM }
        assertTrue(ig.gained.all { it.value == null })
    }

    @Test
    fun summaryLeavesOutPostsWithNoCaptureBeforeThePeriod() {
        seed()
        // A period starting 12 hours after publishing: TikTok's first capture is a day in, so no baseline.
        val s = queries.accountSummary(Period(10), published + 10.days + 12.hours)
        val t = s.platforms.single { it.platform == TIKTOK }
        assertEquals(1, t.postsWithoutBaseline)
        assertNull(t.gained.single { it.id == "views_gained" }.value)
    }

    @Test
    fun summaryRanksYouTubeByFollowersAndTikTokBySharesPer1k() {
        seed()
        val s = queries.accountSummary(Period.ALL, now)
        val y = s.platforms.single { it.platform == YOUTUBE }.ranking
        assertEquals("followers_per_1k", y.measure.metricId)
        assertNull(y.measure.note)
        assertEquals(1, y.ranked)
        assertEquals(
            5.0,
            y.best
                .single()
                .value.value,
        )
        assertTrue(y.weakest.isEmpty(), "a post is never both best and weakest")
        val t = s.platforms.single { it.platform == TIKTOK }.ranking
        assertEquals("shares_per_1k", t.measure.metricId)
        assertNotNull(t.measure.note)
        assertEquals(
            5.0,
            t.best
                .single()
                .value.value,
        )
        assertEquals(published - 2.hours, s.dataStartsAt)
        assertNull(s.followersTotal.value, "the other platforms have no account capture, so no total")
        assertNotNull(s.followersTotal.missingReason)
    }

    @Test
    fun periodParsing() {
        assertEquals(Period(28), Period.parse("28d"))
        assertEquals(Period(7), Period.parse("7"))
        assertEquals(Period.ALL, Period.parse("all"))
        assertNull(Period.parse("soon"))
        assertNull(Period.parse("0d"))
    }

    @Test
    fun trendsShowGapsAndDailyChanges() {
        seed()
        val t = queries.trends(10, listOf(Metrics.FOLLOWERS, Metrics.VIEWS), now, Fixtures.zone, setOf(TIKTOK))
        val followers = t.series.single { it.metric == Metrics.FOLLOWERS }.points
        assertEquals(10, followers.size)
        val byDay = followers.associateBy { it.day }
        assertEquals(1060.0, byDay.getValue(LocalDate.parse("2026-11-08")).value)
        assertNull(byDay.getValue(LocalDate.parse("2026-11-09")).value, "no capture that day is a gap")
        val day9 = byDay.getValue(LocalDate.parse("2026-11-10"))
        assertEquals(1080.0, day9.value)
        assertNull(day9.change, "change needs the day before, which is a gap")
        val views =
            t.series
                .single { it.metric == Metrics.VIEWS }
                .points
                .associateBy { it.day }
        assertEquals(5000.0, views.getValue(LocalDate.parse("2026-11-10")).value)
    }

    @Test
    fun trendsMarkADayWhereAPostWasNotCaptured() {
        seed()
        val other = Fixtures.post(TIKTOK, "tt-2", published + 2.hours)
        repo.upsertPosts(listOf(other), published)
        val t = queries.trends(10, listOf(Metrics.VIEWS), now, Fixtures.zone, setOf(TIKTOK))
        val day =
            t.series
                .single()
                .points
                .single { it.day == LocalDate.parse("2026-11-10") }
        assertNull(day.value)
        assertEquals("1 post(s) weren't captured that day.", day.missingReason)
    }

    @Test
    fun trendsRejectRatesAndReportUnavailableMetrics() {
        seed()
        assertFailsWith<IllegalArgumentException> {
            queries.trends(7, listOf(Metrics.AVERAGE_VIEW_PERCENTAGE), now, Fixtures.zone)
        }
        val t = queries.trends(7, listOf(Metrics.SAVES), now, Fixtures.zone, setOf(TIKTOK, INSTAGRAM))
        assertNotNull(t.series.single { it.platform == TIKTOK }.unavailableReason)
        assertNull(t.series.single { it.platform == INSTAGRAM }.unavailableReason)
    }

    @Test
    fun matchingSuggestsCloseCaptionedPostsAndNeverLinksOnItsOwn() {
        val a = Fixtures.post(TIKTOK, "a").copy(title = "Missed the 200s by 0.2 lbs. Week 29 #weightloss")
        val b = Fixtures.post(FACEBOOK, "b", published + 3.minutes).copy(title = "Missed the 200s by 0.2 lbs. Week 29")
        val far = Fixtures.post(YOUTUBE, "c", published + 3.days).copy(title = "Missed the 200s by 0.2 lbs. Week 29")
        val unrelated = Fixtures.post(INSTAGRAM, "d", published + 30.hours).copy(title = "Breakfast ideas")
        val suggestions = Matching.suggest(listOf(a, b, far, unrelated))
        val s = suggestions.single()
        assertEquals(setOf("a", "b"), s.posts.map { it.postId }.toSet())
        assertEquals(MatchConfidence.HIGH, s.confidence)
        assertTrue(repo.events().isEmpty(), "suggesting writes nothing")
        assertEquals(0.0, Matching.overlap("", "anything"))
    }

    @Test
    fun matchingSkipsPostsAlreadyLinked() {
        val a = Fixtures.post(TIKTOK, "a").copy(title = "Same caption here")
        val b = Fixtures.post(FACEBOOK, "b", published + 1.minutes).copy(title = "Same caption here")
        assertTrue(Matching.suggest(listOf(a, b), mapOf(a.ref to "video-1")).isEmpty())
    }

    @Test
    fun sevenDayBlockFollowsThePlaybookAndLeavesRogersLinesBlank() {
        seed()
        link()
        val block = SevenDayBlock.render(queries.sevenDayReport(queries.findEntry("video-1")!!, now))
        val lines = block.lines()
        assertEquals("## 7-day numbers (2026-11-08)", lines[0])
        assertEquals("- Views (TikTok / Shorts): 4,000 / 2,000", lines[1])
        assertEquals("- Most viewers stopped at: ", lines[2])
        assertTrue(lines[3].startsWith("- Average watch time / % watched: TikTok n/a / n/a; Shorts -"))
        assertEquals("- Saves per 1,000 / shares per 1,000: TikTok n/a / 5.0; Shorts n/a / 2.0", lines[4])
        assertTrue(lines[5].contains("Shorts 5.0"))
        assertTrue(lines[5].contains("TikTok not reported per video; account +60"))
        assertEquals("- Comments with substance (count + best example): ", lines[6])
        assertEquals("- One lesson: ", lines[7])
    }

    @Test
    fun recentReportsAreTheVideosThatLockedDay7InTheWindow() {
        seed()
        link()
        assertEquals(1, queries.recentSevenDayReports(7, now).size)
        assertTrue(queries.recentSevenDayReports(1, now).isEmpty())
    }

    @Test
    fun compareUsesDay7AndLatest() {
        seed()
        val c = queries.compare(queries.findEntry("tt-1")!!, queries.findEntry("yt-1")!!, now)
        assertEquals(
            4000.0,
            c.a.posts
                .single()
                .values["views"],
        )
        assertEquals(
            5000.0,
            c.aLatest.posts
                .single()
                .values["views"],
        )
        assertEquals(StatsBasis.DAY_7, c.b.basis)
    }

    @Test
    fun exportLeavesOutRawResponsesAndDefusesSpreadsheetFormulas() {
        seed()
        val json = Exporter.json(queries, SocialPlatform.entries.toSet(), now)
        assertFalse(json.contains("TEST DATA - not real analytics"), "raw API JSON stays out of exports")
        assertTrue(json.contains("\"definitions_version\""))
        val csv = Exporter.csv(queries, setOf(YOUTUBE))
        val lines = csv.trim().lines()
        assertEquals(Exporter.CSV_HEADER.joinToString(","), lines[0])
        assertTrue(lines.any { it.endsWith(",comments,") }, "missing exports as an empty cell")
        assertTrue(lines.drop(1).all { it.startsWith("youtube,") })
        assertEquals("'=HYPERLINK()", Exporter.cell("=HYPERLINK()"))
        assertEquals("\"a, \"\"b\"\"\"", Exporter.cell("a, \"b\""))
        assertEquals("-3", Exporter.cell("-3"))
    }

    @Test
    fun jsonCarriesDefinitionsAndCaptureTimes() {
        seed()
        val text =
            QueryJson.encode(
                QueryJson.videoStats(queries.videoStats(queries.findEntry("tt-1")!!, StatsBasis.LATEST, now)),
            )
        assertTrue(text.contains("\"captured_at\""))
        assertTrue(text.contains("\"definition\""))
        assertTrue(text.contains("\"definitions_version\": ${Metrics.DEFINITIONS_VERSION}"))
    }

    @Test
    fun formatting() {
        assertEquals("1,234,567", Format.number(1234567.0))
        assertEquals("-1,000.5", Format.number(-1000.5, 1))
        assertEquals("0.0", Format.number(-0.01, 1))
        assertEquals("-", Format.number(null))
        assertEquals("+12", Format.signed(12.0))
        assertEquals("0", Format.signed(0.2))
        assertEquals("1:05", Format.duration(65.0))
    }
}
