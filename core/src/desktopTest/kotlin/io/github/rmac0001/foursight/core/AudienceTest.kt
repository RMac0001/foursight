package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.metrics.AudienceBreakdown
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.platform.meta.FacebookCollector
import io.github.rmac0001.foursight.core.platform.meta.InstagramCollector
import io.github.rmac0001.foursight.core.platform.meta.MetaAuth
import io.github.rmac0001.foursight.core.platform.meta.MetaGraph
import io.github.rmac0001.foursight.core.platform.meta.demographics
import io.github.rmac0001.foursight.core.query.Exporter
import io.github.rmac0001.foursight.core.query.QueryJson
import io.github.rmac0001.foursight.core.query.QueryService
import io.github.rmac0001.foursight.core.query.audience
import io.github.rmac0001.foursight.core.secrets.InMemorySecretStore
import io.github.rmac0001.foursight.core.secrets.SecretName
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

// TEST DATA ONLY. Shapes follow the responses seen live on November 22, 2026; every id and count is
// made up.

private const val FB_COUNTRY = """{"data":[{"name":"page_follows_country","period":"day","values":[
  {"value":{"US":90,"CA":40},"end_time":"2026-11-20T08:00:00+0000"},
  {"value":{"US":100,"CA":50,"GB":10},"end_time":"2026-11-21T08:00:00+0000"}]}]}"""

private const val IG_AGE = """{"data":[{"name":"follower_demographics","period":"lifetime","total_value":{
  "breakdowns":[{"dimension_keys":["age"],"results":[{"dimension_values":["25-34"],"value":30},
  {"dimension_values":["65+"],"value":70}]}]}}]}"""

private const val META_ERROR =
    """{"error":{"message":"An unknown error has occurred.","type":"OAuthException","code":1,"fbtrace_id":"fx"}}"""

class AudienceTest {
    private val now = Instant.parse("2026-11-22T12:00:00Z")
    private val day = LocalDate.parse("2026-11-22")

    private val secrets =
        InMemorySecretStore().apply {
            write(SecretName.META_APP_ID, "fixture-app-id")
            write(SecretName.META_APP_SECRET, "fixture-app-secret")
            write(SecretName.META_PAGE_TOKEN, "fixture-page-token")
        }

    private fun graph(
        requests: MutableList<HttpRequestData>,
        route: (HttpRequestData) -> Pair<String, HttpStatusCode>,
    ) = MetaGraph(
        HttpClient(
            MockEngine { r ->
                requests += r
                val (body, status) = route(r)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
    )

    @Test
    fun facebookTakesTheLatestDayAndAFailedBreakdownIsAWarning() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val g =
                graph(requests) { r ->
                    val metric = r.url.parameters["metric"]
                    when {
                        r.url.encodedPath.endsWith("/me") -> {
                            """{"id":"fx-page","followers_count":160}""" to
                                HttpStatusCode.OK
                        }

                        metric == "page_follows_country" -> {
                            FB_COUNTRY to HttpStatusCode.OK
                        }

                        metric == "page_follows_city" -> {
                            META_ERROR to HttpStatusCode.BadRequest
                        }

                        else -> {
                            """{"data":[]}""" to HttpStatusCode.OK
                        }
                    }
                }
            val c = FacebookCollector(g, MetaAuth(g, secrets)).collect(now, "dev-1", day)
            val country = c.snapshots.single { it.itemKind == ItemKind.AUDIENCE }
            assertEquals("follower_country", country.itemId)
            assertEquals(mapOf("US" to 100.0, "CA" to 50.0, "GB" to 10.0), country.values)
            assertEquals(1, c.warnings.count { "followers by city" in it })
            assertEquals(
                "day",
                requests.first { it.url.parameters["metric"] == "page_follows_country" }.url.parameters["period"],
            )
        }

    @Test
    fun instagramAsksForEachBreakdownOfFollowerDemographics() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val g =
                graph(requests) { r ->
                    when {
                        r.url.encodedPath.endsWith("/me") -> {
                            """{"instagram_business_account":{"id":"fx-ig","followers_count":100}}""" to
                                HttpStatusCode.OK
                        }

                        r.url.encodedPath.endsWith("/fx-ig/insights") && r.url.parameters["breakdown"] == "age" -> {
                            IG_AGE to HttpStatusCode.OK
                        }

                        else -> {
                            """{"data":[]}""" to HttpStatusCode.OK
                        }
                    }
                }
            val c = InstagramCollector(g, MetaAuth(g, secrets)).collect(now, "dev-1", day)
            val audience = c.snapshots.filter { it.itemKind == ItemKind.AUDIENCE }.associateBy { it.itemId }
            assertEquals(setOf("follower_age", "follower_gender", "follower_country"), audience.keys)
            assertEquals(mapOf("25-34" to 30.0, "65+" to 70.0), audience.getValue("follower_age").values)
            assertTrue(audience.getValue("follower_gender").values.isEmpty(), "no data stays empty, never zeros")
            val asked = requests.filter { it.url.encodedPath.endsWith("/fx-ig/insights") }
            assertEquals(setOf("age", "gender", "country"), asked.map { it.url.parameters["breakdown"] }.toSet())
            asked.forEach {
                assertEquals("follower_demographics", it.url.parameters["metric"])
                assertEquals("total_value", it.url.parameters["metric_type"])
                assertEquals("lifetime", it.url.parameters["period"])
            }
        }

    @Test
    fun demographicsJoinsMultiPartGroups() {
        val body =
            Json
                .parseToJsonElement(
                    """{"data":[{"total_value":{"breakdowns":[{"results":[
                    {"dimension_values":["F","25-34"],"value":3}]}]}}]}""",
                ).jsonObject
        assertEquals(mapOf("F 25-34" to 3.0), demographics(body))
        assertTrue(demographics(Json.parseToJsonElement("""{"data":[]}""").jsonObject).isEmpty())
    }

    @Test
    fun audienceQueryShowsTheLatestCaptureLargestFirstWithLabeledShares() {
        val repo = Fixtures.repo()
        val d = repo.deviceId()
        repo.insertSnapshots(
            listOf(
                Fixtures.snapshot(
                    d,
                    SocialPlatform.FACEBOOK,
                    "follower_country",
                    now - 30.hours,
                    mapOf("US" to 1.0),
                    ItemKind.AUDIENCE,
                ),
                Fixtures.snapshot(
                    d,
                    SocialPlatform.FACEBOOK,
                    "follower_country",
                    now - 2.hours,
                    mapOf("CA" to 25.0, "US" to 75.0),
                    ItemKind.AUDIENCE,
                ),
                Fixtures.snapshot(
                    d,
                    SocialPlatform.YOUTUBE,
                    "viewer_age_gender",
                    now - 2.hours,
                    mapOf("25-34 male" to 60.0, "18-24 female" to 40.0),
                    ItemKind.AUDIENCE,
                ),
            ),
        )
        val queries = QueryService(repo)
        val r = queries.audience(now)
        val fb = r.platforms.single { it.platform == SocialPlatform.FACEBOOK }
        val country = fb.breakdowns.single { it.breakdown == AudienceBreakdown.FACEBOOK_FOLLOWER_COUNTRY }
        assertEquals(listOf("US", "CA"), country.groups.map { it.group })
        assertEquals(75.0, country.groups.first().shareOfListed)
        val city = fb.breakdowns.single { it.breakdown == AudienceBreakdown.FACEBOOK_FOLLOWER_CITY }
        assertEquals("Not collected yet.", city.missingReason)
        assertTrue(fb.notAvailable.any { "age and gender" in it })
        val yt =
            r.platforms
                .single { it.platform == SocialPlatform.YOUTUBE }
                .breakdowns
                .first()
        assertNull(yt.groups.first().shareOfListed, "percentages aren't re-shared")
        val tiktok = r.platforms.single { it.platform == SocialPlatform.TIKTOK }
        assertTrue(tiktok.breakdowns.isEmpty())
        assertEquals(1, tiktok.notAvailable.size)
        assertTrue(QueryJson.encode(QueryJson.audience(r)).contains("share_of_listed_definition"))
        assertTrue(Exporter.csv(queries, setOf(SocialPlatform.FACEBOOK)).contains("facebook,audience,follower_country"))
    }
}
