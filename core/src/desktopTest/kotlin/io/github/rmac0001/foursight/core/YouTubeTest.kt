package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.auth.Pkce
import io.github.rmac0001.foursight.core.collect.CollectionException
import io.github.rmac0001.foursight.core.collect.FailureKind
import io.github.rmac0001.foursight.core.model.ContentType
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.platform.google.GoogleAuth
import io.github.rmac0001.foursight.core.platform.google.GoogleGrant
import io.github.rmac0001.foursight.core.platform.youtube.YouTubeApi
import io.github.rmac0001.foursight.core.platform.youtube.YouTubeCollector
import io.github.rmac0001.foursight.core.secrets.InMemorySecretStore
import io.github.rmac0001.foursight.core.secrets.SecretName
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * TEST DATA ONLY. JSON follows the shapes in Google's reference pages (checked November 22, 2026);
 * every id, count and token is made up and labeled "fixture".
 */
class YouTubeTest {
    private val now = Instant.parse("2026-11-22T18:00:00Z")
    private val day = LocalDate.parse("2026-11-22")

    private fun secrets(refresh: String? = "fixture-google-refresh") =
        InMemorySecretStore().apply {
            write(SecretName.GOOGLE_CLIENT_ID, "fixture-client-id")
            write(SecretName.GOOGLE_CLIENT_SECRET, "fixture-client-secret")
            if (refresh != null) write(SecretName.GOOGLE_YOUTUBE_REFRESH_TOKEN, refresh)
        }

    private val tokenJson =
        """{"access_token":"fixture-access","expires_in":3599,"token_type":"Bearer",
           "scope":"https://www.googleapis.com/auth/youtube.readonly https://www.googleapis.com/auth/yt-analytics.readonly https://www.googleapis.com/auth/drive.file"}"""

    private val channelJson =
        """{"items":[{"id":"fixture-channel","statistics":{"subscriberCount":"412","hiddenSubscriberCount":false,
           "videoCount":"3"},"contentDetails":{"relatedPlaylists":{"uploads":"fixture-uploads"}}}]}"""

    private val playlistJson =
        """{"items":[{"contentDetails":{"videoId":"fx-a"}},{"contentDetails":{"videoId":"fx-b"}},
           {"contentDetails":{"videoId":"fx-private"}}]}"""

    private val videosJson =
        """{"items":[
           {"id":"fx-a","snippet":{"title":"Fixture short","publishedAt":"2026-11-15T13:00:00Z",
             "thumbnails":{"high":{"url":"https://example.invalid/a.jpg"}}},
             "contentDetails":{"duration":"PT58S"},"statistics":{"viewCount":"1500","likeCount":"60","commentCount":"4"},
             "status":{"privacyStatus":"public"}},
           {"id":"fx-b","snippet":{"title":"Fixture long","publishedAt":"2026-10-01T13:00:00Z"},
             "contentDetails":{"duration":"PT12M5S"},"statistics":{"viewCount":"300"},"status":{"privacyStatus":"public"}},
           {"id":"fx-private","snippet":{"title":"Fixture private","publishedAt":"2026-11-20T13:00:00Z"},
             "contentDetails":{"duration":"PT30S"},"statistics":{"viewCount":"0"},"status":{"privacyStatus":"private"}}]}"""

    private val dailyJson =
        """{"columnHeaders":[{"name":"day"},{"name":"views"}],"rows":[["2026-11-19",80],["2026-11-20",95]]}"""

    private val perVideoJson =
        """{"columnHeaders":[{"name":"video"},{"name":"views"},{"name":"engagedViews"},{"name":"shares"},
           {"name":"subscribersGained"},{"name":"subscribersLost"},{"name":"estimatedMinutesWatched"},
           {"name":"averageViewDuration"},{"name":"averageViewPercentage"}],
           "rows":[["fx-a",1400,1300,12,9,1,700,30,51.7]]}"""

    private fun respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = Pair(body, status)

    private fun client(
        requests: MutableList<HttpRequestData> = mutableListOf(),
        route: (HttpRequestData) -> Pair<String, HttpStatusCode>,
    ) = HttpClient(
        MockEngine { request ->
            requests += request
            val (body, status) = route(request)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    private fun youtubeRoutes(analyticsFails: Boolean = false): (HttpRequestData) -> Pair<String, HttpStatusCode> =
        { r ->
            val path = r.url.encodedPath
            when {
                r.url.host == "oauth2.googleapis.com" -> {
                    respondJson(tokenJson)
                }

                path.endsWith("/channels") -> {
                    respondJson(channelJson)
                }

                path.endsWith("/playlistItems") -> {
                    respondJson(playlistJson)
                }

                path.endsWith("/videos") -> {
                    respondJson(videosJson)
                }

                analyticsFails -> {
                    respondJson(
                        """{"error":{"code":400,"message":"The query is not supported.","errors":[{"reason":"badRequest"}]}}""",
                        HttpStatusCode.BadRequest,
                    )
                }

                r.url.parameters["dimensions"] == "day" -> {
                    respondJson(dailyJson)
                }

                r.url.parameters["dimensions"] == "ageGroup,gender" -> {
                    respondJson(
                        """{"columnHeaders":[{"name":"ageGroup"},{"name":"gender"},{"name":"viewerPercentage"}],
                           "rows":[["age25-34","male",61.5],["age65-","female",38.5]]}""",
                    )
                }

                r.url.parameters["dimensions"] == "insightTrafficSourceType" -> {
                    respondJson(
                        """{"columnHeaders":[{"name":"insightTrafficSourceType"},{"name":"views"}],
                           "rows":[["SHORTS",900],["YT_SEARCH",40]]}""",
                    )
                }

                r.url.parameters["dimensions"] == "country" -> {
                    respondJson("""{"columnHeaders":[{"name":"country"},{"name":"views"}],"kind":"none"}""")
                }

                else -> {
                    respondJson(perVideoJson)
                }
            }
        }

    @Test
    fun s256ChallengeMatchesRfc7636() {
        // RFC 7636, appendix B.
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.s256Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun signInUrlAsksForOfflineAccessWithPkce() {
        val auth = GoogleAuth(client { respondJson("{}") }, secrets(), GoogleGrant.YOUTUBE)
        val url = Url(auth.begin("http://127.0.0.1:50123").authorizeUrl)
        assertEquals("accounts.google.com", url.host)
        assertEquals("fixture-client-id", url.parameters["client_id"])
        assertEquals("http://127.0.0.1:50123", url.parameters["redirect_uri"])
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals(43, url.parameters["code_challenge"]!!.length)
        assertEquals("offline", url.parameters["access_type"])
        assertEquals("consent", url.parameters["prompt"])
        assertEquals(GoogleGrant.YOUTUBE.scopes.joinToString(" "), url.parameters["scope"])
        assertFalse("drive" in url.parameters["scope"]!!, "Google refuses YouTube and Drive in one consent")
        assertNull(url.parameters["include_granted_scopes"])
        assertEquals("/", auth.loopbackPath)
        assertEquals("", auth.loopbackRedirectSuffix, "Google uses the bare loopback address")
    }

    @Test
    fun refreshKeepsTheStoredRefreshTokenAndCachesTheAccessToken() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val store = secrets()
            val auth = GoogleAuth(client(requests) { respondJson(tokenJson) }, store, GoogleGrant.YOUTUBE)
            assertEquals("fixture-access", auth.accessToken(now))
            assertEquals("fixture-access", auth.accessToken(now))
            assertEquals(1, requests.size, "second call uses the cached token")
            val form =
                parseQueryString((requests.single().body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            assertEquals("refresh_token", form["grant_type"])
            assertEquals("fixture-google-refresh", form["refresh_token"])
            assertEquals(
                "application/x-www-form-urlencoded",
                requests
                    .single()
                    .body.contentType
                    .toString(),
            )
            assertEquals("fixture-google-refresh", store.read(SecretName.GOOGLE_YOUTUBE_REFRESH_TOKEN))
        }

    @Test
    fun expiredRefreshTokenMeansSignInAgain() =
        runTest {
            val auth =
                GoogleAuth(
                    client {
                        respondJson(
                            """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""",
                            HttpStatusCode.BadRequest,
                        )
                    },
                    secrets(),
                    GoogleGrant.YOUTUBE,
                )
            assertEquals(
                FailureKind.SIGN_IN_EXPIRED,
                assertFailsWith<CollectionException> { auth.accessToken(now) }.kind,
            )
            val none = GoogleAuth(client { respondJson("{}") }, secrets(refresh = null), GoogleGrant.YOUTUBE)
            assertEquals(FailureKind.NOT_CONNECTED, assertFailsWith<CollectionException> { none.accessToken(now) }.kind)
        }

    @Test
    fun collectsAudienceOverThe28AnalyticsDays() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val http = client(requests, youtubeRoutes())
            val c =
                YouTubeCollector(
                    YouTubeApi(http),
                    GoogleAuth(http, secrets(), GoogleGrant.YOUTUBE),
                ).collect(now, "dev-1", day)
            val audience = c.snapshots.filter { it.itemKind == ItemKind.AUDIENCE }.associateBy { it.itemId }
            assertEquals(
                mapOf("25-34 male" to 61.5, "65+ female" to 38.5),
                audience.getValue("viewer_age_gender").values,
            )
            assertEquals(mapOf("SHORTS" to 900.0, "YT_SEARCH" to 40.0), audience.getValue("traffic_source").values)
            assertTrue(audience.getValue("viewer_country").values.isEmpty(), "no rows stays empty, never zeros")
            val demo = requests.single { it.url.parameters["dimensions"] == "ageGroup,gender" }
            assertEquals("2026-11-20", demo.url.parameters["endDate"], "ends on the last Analytics day")
            assertEquals("2026-10-24", demo.url.parameters["startDate"])
            val country = requests.single { it.url.parameters["dimensions"] == "country" }
            assertEquals("-views", country.url.parameters["sort"])
            assertEquals("25", country.url.parameters["maxResults"])
        }

    @Test
    fun collectsCurrentCountersPlusAnalyticsWithTheirDate() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val http = client(requests, youtubeRoutes())
            val collection =
                YouTubeCollector(
                    YouTubeApi(http),
                    GoogleAuth(http, secrets(), GoogleGrant.YOUTUBE),
                ).collect(now, "dev-1", day)

            assertEquals(listOf("fx-a", "fx-b"), collection.posts.map { it.postId }, "private videos are left out")
            val short = collection.posts.first()
            assertEquals(ContentType.SHORT_FORM, short.contentType)
            assertEquals(58L, short.durationSeconds)
            assertEquals(Instant.parse("2026-11-15T13:00:00Z"), short.publishedAt)
            assertEquals("https://example.invalid/a.jpg", short.thumbnailUrl)
            assertEquals(ContentType.LONG_FORM, collection.posts[1].contentType)

            val account = collection.snapshots.single { it.itemKind == ItemKind.ACCOUNT }
            assertEquals("fixture-channel", account.itemId)
            assertEquals(412.0, account.value("followers"))

            val a = collection.snapshots.single { it.itemId == "fx-a" }
            assertEquals(1500.0, a.value("views"), "views from the Data API's current counter, not Analytics' 1400")
            assertEquals(60.0, a.value("likes"))
            assertEquals(12.0, a.value("shares"))
            assertEquals(9.0, a.value("followers_gained"))
            assertEquals(42_000.0, a.value("watch_time_seconds"))
            assertEquals(30.0, a.value("average_view_duration_seconds"))
            assertEquals(51.7, a.value("average_view_percentage"))
            assertEquals("2026-11-20", (a.raw["analytics_through"])!!.jsonPrimitive.content)

            val b = collection.snapshots.single { it.itemId == "fx-b" }
            assertEquals(300.0, b.value("views"))
            assertFalse("likes" in b.values, "a count YouTube left out stays missing")
            assertFalse("shares" in b.values, "no Analytics row means missing, not zero")

            val perVideo = requests.single { it.url.parameters["dimensions"] == "video" }
            assertEquals("channel==MINE", perVideo.url.parameters["ids"])
            assertEquals("video==fx-a,fx-b", perVideo.url.parameters["filters"])
            assertEquals("-views", perVideo.url.parameters["sort"])
            assertEquals("2026-10-01", perVideo.url.parameters["startDate"])
            assertEquals("2026-11-22", perVideo.url.parameters["endDate"])
            assertTrue(collection.warnings.isEmpty())
            assertNull(collection.tokenExpiresAt)
        }

    @Test
    fun analyticsFailureKeepsDataApiNumbersAndWarns() =
        runTest {
            val http = client(route = youtubeRoutes(analyticsFails = true))
            val collection =
                YouTubeCollector(
                    YouTubeApi(http),
                    GoogleAuth(http, secrets(), GoogleGrant.YOUTUBE),
                ).collect(now, "dev-1", day)
            val a = collection.snapshots.single { it.itemId == "fx-a" }
            assertEquals(1500.0, a.value("views"))
            assertFalse("watch_time_seconds" in a.values)
            assertFalse(a.raw.containsKey("analytics"))
            assertEquals(1, collection.warnings.size)
            assertTrue(collection.warnings.single().contains("Analytics"))
            assertTrue(a.raw["video"] is JsonObject)
        }

    @Test
    fun parsesYouTubeDurations() {
        assertEquals(933L, YouTubeCollector.isoDurationSeconds("PT15M33S"))
        assertEquals(3723L, YouTubeCollector.isoDurationSeconds("PT1H2M3S"))
        assertEquals(93_600L, YouTubeCollector.isoDurationSeconds("P1DT2H"))
        assertEquals(59L, YouTubeCollector.isoDurationSeconds("PT59S"))
        assertNull(YouTubeCollector.isoDurationSeconds("15:33"))
        assertEquals(ContentType.SHORT_FORM, YouTubeCollector.contentType(180))
        assertEquals(ContentType.LONG_FORM, YouTubeCollector.contentType(181))
    }
}
