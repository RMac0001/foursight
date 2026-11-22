package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.Fixtures.published
import io.github.rmac0001.foursight.core.SocialPlatform.TIKTOK
import io.github.rmac0001.foursight.core.SocialPlatform.YOUTUBE
import io.github.rmac0001.foursight.core.metrics.Day7Result
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.query.QueryService
import io.github.rmac0001.foursight.core.sync.MatchEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

// TEST DATA: made-up posts and numbers.
class QueryServiceTest {
    private val repo = Fixtures.repo()
    private val queries = QueryService(repo)
    private val tiktok = Fixtures.post(TIKTOK, "tt-1")
    private val short = Fixtures.post(YOUTUBE, "yt-1", published + 1.hours)

    private fun seed() {
        repo.upsertPosts(listOf(tiktok, short), published)
        val d = repo.deviceId()
        repo.insertSnapshots(
            listOf(
                Fixtures.snapshot(
                    d,
                    TIKTOK,
                    "tt-1",
                    published + 7.days + 2.hours,
                    mapOf(
                        "views" to 4000.0,
                        "shares" to 20.0,
                    ),
                ),
                Fixtures.snapshot(
                    d,
                    YOUTUBE,
                    "yt-1",
                    published + 1.hours + 7.days + 3.hours,
                    mapOf("views" to 2000.0, "shares" to 4.0, "followers_gained" to 10.0),
                ),
                Fixtures.snapshot(d, TIKTOK, "me", published - 2.hours, mapOf("followers" to 1000.0), ItemKind.ACCOUNT),
                Fixtures.snapshot(
                    d,
                    TIKTOK,
                    "me",
                    published + 7.days + 2.hours,
                    mapOf("followers" to 1060.0),
                    ItemKind.ACCOUNT,
                ),
            ),
        )
    }

    @Test
    fun unmatchedPostsAreSeparateEntriesUntilLinked() {
        seed()
        assertEquals(2, queries.library().size)
        repo.insertEvents(
            listOf(
                MatchEvents.link("e1", repo.deviceId(), published, "video-1", tiktok.ref),
                MatchEvents.link("e2", repo.deviceId(), published, "video-1", short.ref),
            ),
        )
        val library = queries.library()
        assertEquals(1, library.size)
        assertTrue(library.single().confirmed)
        assertEquals(
            setOf(TIKTOK, YOUTUBE),
            library
                .single()
                .video.posts
                .map { it.platform }
                .toSet(),
        )
        assertNotNull(queries.findEntry("tiktok:tt-1"))
    }

    @Test
    fun sevenDayReportUsesPerPostFollowsOnlyWhereThePlatformReportsThem() {
        seed()
        repo.insertEvents(
            listOf(
                MatchEvents.link("e1", repo.deviceId(), published, "video-1", tiktok.ref),
                MatchEvents.link("e2", repo.deviceId(), published, "video-1", short.ref),
            ),
        )
        val report = queries.sevenDayReport(queries.findEntry("video-1")!!, published + 10.days)
        val (tt, yt) = report.posts

        // YouTube: 10 subscribers gained on 2,000 views = 5 per 1,000.
        assertEquals(5.0, yt.followersPer1k!!.value)
        assertEquals(2.0, yt.sharesPer1k!!.value)

        // TikTok has no per-post follows: no per-1,000 number, a reason, and a labeled account change.
        assertNull(tt.followersPer1k)
        assertNotNull(tt.followersPer1kUnavailable)
        assertEquals(60.0, tt.accountFollowerChange!!.value)
        assertTrue(tt.accountFollowerChange.definition.contains("not attributed"))
        assertEquals(5.0, tt.sharesPer1k!!.value)
        assertNull(tt.savesPer1k, "TikTok doesn't report saves")
    }

    @Test
    fun aPostWithNoCaptureInTheWindowIsMissed() {
        repo.upsertPosts(listOf(tiktok), published)
        repo.insertSnapshots(
            listOf(Fixtures.snapshot(repo.deviceId(), TIKTOK, "tt-1", published + 9.days, mapOf("views" to 1.0))),
        )
        val report = queries.sevenDayReport(queries.findEntry("tt-1")!!, published + 10.days)
        assertIs<Day7Result.Missed>(report.posts.single().result)
        assertTrue(
            report.posts
                .single()
                .values
                .isEmpty(),
        )
    }

    @Test
    fun freshnessCoversEveryPlatform() {
        seed()
        val freshness = queries.freshness().associateBy { it.platform }
        assertEquals(SocialPlatform.entries.toSet(), freshness.keys)
        assertNotNull(freshness[TIKTOK]!!.latestCapture)
        assertNull(freshness[SocialPlatform.FACEBOOK]!!.latestCapture)
    }
}
