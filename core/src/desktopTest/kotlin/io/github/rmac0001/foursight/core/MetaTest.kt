package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.auth.SignInException
import io.github.rmac0001.foursight.core.collect.CollectionException
import io.github.rmac0001.foursight.core.collect.FailureKind
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.platform.meta.FacebookCollector
import io.github.rmac0001.foursight.core.platform.meta.InstagramCollector
import io.github.rmac0001.foursight.core.platform.meta.MetaAuth
import io.github.rmac0001.foursight.core.platform.meta.MetaGraph
import io.github.rmac0001.foursight.core.secrets.InMemorySecretStore
import io.github.rmac0001.foursight.core.secrets.SecretName
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

// TEST DATA ONLY. Shapes follow Meta's reference pages (checked November 22, 2026); every id,
// count and token is made up and labeled "fixture".

private const val SHORT_TOKEN = """{"access_token":"fixture-short-user","token_type":"bearer","expires_in":3600}"""
private const val LONG_TOKEN = """{"access_token":"fixture-long-user","token_type":"bearer","expires_in":5183944}"""
private const val ACCOUNTS = """{"data":[{"id":"fx-page","name":"Fixture Page",
  "access_token":"fixture-page-token","instagram_business_account":{"id":"fx-ig"}}]}"""
private const val PERMISSIONS = """{"data":[{"permission":"pages_show_list","status":"granted"},
  {"permission":"pages_read_engagement","status":"granted"},{"permission":"read_insights","status":"granted"},
  {"permission":"instagram_basic","status":"granted"},
  {"permission":"instagram_manage_insights","status":"declined"}]}"""

private const val PAGE = """{"id":"fx-page","name":"Fixture Page","followers_count":3500}"""
private const val REELS = """{"data":[{"id":"fx-reel","description":"Fixture reel",
  "created_time":"2026-11-20T15:00:00+0000","length":58.2,"permalink_url":"/reel/fx-reel"}]}"""
private const val REEL_INSIGHTS = """{"data":[
  {"name":"fb_reels_total_plays","period":"lifetime","values":[{"value":900}]},
  {"name":"post_video_likes_by_reaction_type","period":"lifetime",
   "values":[{"value":{"REACTION_LIKE":20,"REACTION_LOVE":5}}]},
  {"name":"post_video_social_actions","period":"lifetime","values":[{"value":{"SHARE":2}}]},
  {"name":"post_video_followers","period":"lifetime","values":[{"value":4}]},
  {"name":"post_video_view_time","period":"lifetime","values":[{"value":4500000}]},
  {"name":"post_video_avg_time_watched","period":"lifetime","values":[{"value":5000}]}]}"""

private const val IG_ACCOUNT = """{"instagram_business_account":{"id":"fx-ig","followers_count":250,
  "media_count":2},"id":"fx-page"}"""
private const val IG_MEDIA_1 = """{"data":[{"id":"fx-r1","caption":"Fixture reel","media_product_type":"REELS",
  "timestamp":"2026-11-19T14:00:00+0000","permalink":"https://example.invalid/r1"},
  {"id":"fx-photo","media_product_type":"FEED","timestamp":"2026-11-18T14:00:00+0000"}],
  "paging":{"next":"https://graph.facebook.com/v27.0/fx-ig/media?after=fx"}}"""
private const val IG_MEDIA_2 = """{"data":[{"id":"fx-r2","media_product_type":"REELS",
  "timestamp":"2026-11-17T14:00:00+0000"}]}"""
private const val IG_INSIGHTS = """{"data":[{"name":"views","period":"lifetime","values":[{"value":640}]},
  {"name":"saved","period":"lifetime","values":[{"value":7}]},
  {"name":"ig_reels_avg_watch_time","period":"lifetime","values":[{"value":6200}]}]}"""

private fun metaError(code: Int) =
    """{"error":{"message":"Fixture","type":"OAuthException","code":$code,"fbtrace_id":"fx-trace"}}"""

class MetaTest {
    private val now = Instant.parse("2026-11-22T18:00:00Z")
    private val day = LocalDate.parse("2026-11-22")

    private fun secrets(page: String? = "fixture-page-token") =
        InMemorySecretStore().apply {
            write(SecretName.META_APP_ID, "fixture-app-id")
            write(SecretName.META_APP_SECRET, "fixture-app-secret")
            write(SecretName.META_LOGIN_CONFIG_ID, "fixture-config")
            if (page != null) write(SecretName.META_PAGE_TOKEN, page)
        }

    private fun graph(
        requests: MutableList<HttpRequestData> = mutableListOf(),
        route: (HttpRequestData) -> Pair<String, HttpStatusCode>,
    ) = MetaGraph(
        HttpClient(
            MockEngine { request ->
                requests += request
                val (body, status) = route(request)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
    )

    private fun ok(body: String) = body to HttpStatusCode.OK

    private fun HttpRequestData.path() = url.encodedPath

    @Test
    fun signInUsesConfigIdAndStoresOnlyThePageToken() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val store = secrets(page = null)
            val g =
                graph(requests) { r ->
                    when {
                        r.path().endsWith("/oauth/access_token") -> {
                            ok(if (r.url.parameters["grant_type"] == "fb_exchange_token") LONG_TOKEN else SHORT_TOKEN)
                        }

                        r.path().endsWith("/me/accounts") -> {
                            ok(ACCOUNTS)
                        }

                        r.path().endsWith("/me/permissions") -> {
                            ok(PERMISSIONS)
                        }

                        else -> {
                            ok("{}")
                        }
                    }
                }
            val auth = MetaAuth(g, store)
            assertEquals("/", auth.loopbackRedirectSuffix, "Meta needs the trailing slash")
            val request = auth.begin("http://localhost:50123/")
            val url = Url(request.authorizeUrl)
            assertEquals("/v27.0/dialog/oauth", url.encodedPath)
            assertEquals("fixture-config", url.parameters["config_id"])
            assertEquals("code", url.parameters["response_type"])
            assertNull(url.parameters["scope"], "Login for Business: config_id instead of scope")
            assertEquals("localhost", auth.loopbackHost)

            val state = url.parameters["state"]!!
            val result = auth.complete(request, mapOf("code" to "fixture-code", "state" to state), now)
            assertEquals("fixture-page-token", store.read(SecretName.META_PAGE_TOKEN))
            assertEquals(setOf("instagram_manage_insights"), result.missingScopes)
            val accounts = requests.single { it.path().endsWith("/me/accounts") }
            assertEquals("Bearer fixture-long-user", accounts.headers[HttpHeaders.Authorization])

            assertFailsWith<SignInException> { auth.complete(request, mapOf("code" to "x", "state" to "wrong"), now) }
        }

    @Test
    fun collectsFacebookReelsWithInsightsInSeconds() =
        runTest {
            val g =
                graph { r ->
                    when {
                        r.path().endsWith("/me") -> ok(PAGE)
                        r.path().endsWith("/video_reels") -> ok(REELS)
                        r.path().endsWith("/video_insights") -> ok(REEL_INSIGHTS)
                        else -> ok("{}")
                    }
                }
            val c = FacebookCollector(g, MetaAuth(g, secrets())).collect(now, "dev-1", day)
            val post = c.posts.single()
            assertEquals(Instant.parse("2026-11-20T15:00:00Z"), post.publishedAt)
            assertEquals("https://www.facebook.com/reel/fx-reel", post.url)
            assertEquals(58L, post.durationSeconds)
            assertEquals(3500.0, c.snapshots.single { it.itemKind == ItemKind.ACCOUNT }.value("followers"))
            val expected =
                mapOf(
                    "views" to 900.0,
                    "likes" to 25.0,
                    // Meta leaves out types with no count: no COMMENT key means zero comments.
                    "comments" to 0.0,
                    "shares" to 2.0,
                    "followers_gained" to 4.0,
                    "watch_time_seconds" to 4500.0,
                    "average_view_duration_seconds" to 5.0,
                )
            assertEquals(expected, c.snapshots.single { it.itemId == "fx-reel" }.values)
        }

    @Test
    fun collectsOnlyInstagramReelsAndKeepsEmptyMetricsMissing() =
        runTest {
            val g =
                graph { r ->
                    when {
                        r.path().endsWith("/me") -> {
                            ok(IG_ACCOUNT)
                        }

                        r.path().endsWith("/fx-ig/media") -> {
                            ok(
                                if (r.url.parameters["after"] ==
                                    null
                                ) {
                                    IG_MEDIA_1
                                } else {
                                    IG_MEDIA_2
                                },
                            )
                        }

                        r.path().endsWith("/fx-r1/insights") -> {
                            ok(IG_INSIGHTS)
                        }

                        r.path().endsWith("/fx-r2/insights") -> {
                            metaError(100) to HttpStatusCode.BadRequest
                        }

                        else -> {
                            ok("{}")
                        }
                    }
                }
            val c = InstagramCollector(g, MetaAuth(g, secrets())).collect(now, "dev-1", day)
            assertEquals(listOf("fx-r1", "fx-r2"), c.posts.map { it.postId }, "feed posts left out; paging followed")
            assertEquals(250.0, c.snapshots.single { it.itemKind == ItemKind.ACCOUNT }.value("followers"))
            val r1 = c.snapshots.single { it.itemId == "fx-r1" }
            assertEquals(mapOf("views" to 640.0, "saves" to 7.0, "average_view_duration_seconds" to 6.2), r1.values)
            assertFalse("likes" in r1.values, "a metric Meta left out stays missing")
            assertTrue(
                c.snapshots
                    .single { it.itemId == "fx-r2" }
                    .values
                    .isEmpty(),
            )
            assertEquals(1, c.warnings.size)
        }

    @Test
    fun mapsMetaErrors() =
        runTest {
            suspend fun kind(code: Int): FailureKind {
                val g = graph { metaError(code) to HttpStatusCode.BadRequest }
                val e =
                    assertFailsWith<CollectionException> {
                        FacebookCollector(
                            g,
                            MetaAuth(g, secrets()),
                        ).collect(now, "d", day)
                    }
                assertEquals("fx-trace", e.logId)
                return e.kind
            }
            assertEquals(FailureKind.SIGN_IN_EXPIRED, kind(190))
            assertEquals(FailureKind.SCOPE_MISSING, kind(10))
            assertEquals(FailureKind.SCOPE_MISSING, kind(200))
            assertEquals(FailureKind.RATE_LIMITED, kind(4))
            assertEquals(FailureKind.RATE_LIMITED, kind(80001))

            val g = graph { ok("{}") }
            val none =
                assertFailsWith<CollectionException> {
                    FacebookCollector(g, MetaAuth(g, secrets(page = null))).collect(now, "d", day)
                }
            assertEquals(FailureKind.NOT_CONNECTED, none.kind)
        }
}
