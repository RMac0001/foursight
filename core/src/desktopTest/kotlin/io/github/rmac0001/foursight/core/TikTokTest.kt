package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.auth.Pkce
import io.github.rmac0001.foursight.core.auth.SignInException
import io.github.rmac0001.foursight.core.collect.CollectionException
import io.github.rmac0001.foursight.core.collect.FailureKind
import io.github.rmac0001.foursight.core.model.ItemKind
import io.github.rmac0001.foursight.core.platform.tiktok.TikTokApi
import io.github.rmac0001.foursight.core.platform.tiktok.TikTokAuth
import io.github.rmac0001.foursight.core.platform.tiktok.TikTokCollector
import io.github.rmac0001.foursight.core.secrets.InMemorySecretStore
import io.github.rmac0001.foursight.core.secrets.SecretName
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * TEST DATA ONLY. The JSON below follows the shapes in TikTok's reference pages (checked November
 * 21, 2026); every id, count and token is made up and labeled "fixture".
 */
class TikTokTest {
    private val now = Instant.parse("2026-11-21T14:00:00Z")
    private val day = LocalDate.parse("2026-11-21")

    private fun secrets(refresh: String? = "fixture-refresh-1") =
        InMemorySecretStore().apply {
            write(SecretName.TIKTOK_CLIENT_KEY, "fixture-client-key")
            write(SecretName.TIKTOK_CLIENT_SECRET, "fixture-client-secret")
            if (refresh != null) write(SecretName.TIKTOK_REFRESH_TOKEN, refresh)
        }

    private fun tokenJson(refresh: String = "fixture-refresh-2") =
        """{"access_token":"fixture-access","expires_in":7200,"open_id":"fixture-open-id",
           "refresh_expires_in":15552000,"refresh_token":"$refresh",
           "scope":"user.info.basic,user.info.stats,video.list","token_type":"Bearer"}"""

    private val userJson =
        """{"data":{"user":{"open_id":"fixture-open-id","display_name":"Fixture",
           "follower_count":1200,"video_count":3}},"error":{"code":"ok","message":"","log_id":"fx"}}"""

    private fun json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): MockRequestHandleScope.() -> HttpResponseData =
        { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }

    private class Recorder {
        val requests = mutableListOf<HttpRequestData>()
    }

    private fun client(
        recorder: Recorder = Recorder(),
        route: (HttpRequestData) -> (MockRequestHandleScope.() -> HttpResponseData),
    ) = HttpClient(
        MockEngine { request ->
            recorder.requests += request
            route(request)(this)
        },
    )

    private fun HttpRequestData.form(): Map<String, String> {
        val text = (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""
        val params = parseQueryString(text)
        return params.names().associateWith { params[it]!! }
    }

    private fun HttpRequestData.jsonBody(): String = (body as? TextContent)?.text ?: ""

    @Test
    fun pkceChallengeIsHexSha256() {
        // SHA-256("abc") from FIPS 180-2.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Pkce.tiktokChallenge("abc"))
        val verifier = Pkce.newVerifier()
        assertEquals(64, verifier.length)
        assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" })
        assertTrue(Pkce.newVerifier() != verifier)
    }

    @Test
    fun authorizeUrlCarriesPkceAndReadScopes() {
        val auth = TikTokAuth(TikTokApi(client { json("{}") }), secrets())
        val request = auth.begin("http://127.0.0.1:50123/callback/")
        val url = Url(request.authorizeUrl)
        assertEquals("www.tiktok.com", url.host)
        assertEquals("/v2/auth/authorize/", url.encodedPath)
        assertEquals("fixture-client-key", url.parameters["client_key"])
        assertEquals("code", url.parameters["response_type"])
        assertEquals("user.info.basic,user.info.stats,video.list", url.parameters["scope"])
        assertEquals("http://127.0.0.1:50123/callback/", url.parameters["redirect_uri"])
        assertEquals("S256", url.parameters["code_challenge_method"])
        assertEquals(64, url.parameters["code_challenge"]!!.length)
        assertFalse(request.toString().contains(url.parameters["state"]!!))
    }

    @Test
    fun signInExchangesCodeWithVerifierAndStoresRefreshToken() =
        runTest {
            val recorder = Recorder()
            val store = secrets(refresh = null)
            val auth = TikTokAuth(TikTokApi(client(recorder) { json(tokenJson()) }), store)
            val request = auth.begin("http://127.0.0.1:50123/callback/")
            val state = Url(request.authorizeUrl).parameters["state"]!!
            val result = auth.complete(request, mapOf("code" to "fixture-code", "state" to state), now)

            val form = recorder.requests.single().form()
            // TikTok rejects a Content-Type with a charset parameter (found live, November 22, 2026).
            assertEquals(
                "application/x-www-form-urlencoded",
                recorder.requests
                    .single()
                    .body.contentType
                    .toString(),
            )
            assertEquals("authorization_code", form["grant_type"])
            assertEquals("fixture-code", form["code"])
            assertEquals("http://127.0.0.1:50123/callback/", form["redirect_uri"])
            assertEquals(64, form["code_verifier"]!!.length)
            assertEquals("fixture-refresh-2", store.read(SecretName.TIKTOK_REFRESH_TOKEN))
            assertTrue(result.missingScopes.isEmpty())
        }

    @Test
    fun signInRejectsWrongStateAndUserCancel() =
        runTest {
            val recorder = Recorder()
            val auth = TikTokAuth(TikTokApi(client(recorder) { json(tokenJson()) }), secrets())
            val request = auth.begin("http://127.0.0.1:50123/callback/")
            assertFailsWith<SignInException> {
                auth.complete(request, mapOf("code" to "fixture-code", "state" to "wrong"), now)
            }
            assertFailsWith<SignInException> {
                auth.complete(request, mapOf("error" to "access_denied"), now)
            }
            assertTrue(recorder.requests.isEmpty())
        }

    @Test
    fun collectsAccountAndPagedVideosAndRotatesRefreshToken() =
        runTest {
            val recorder = Recorder()
            val http =
                client(recorder) { request ->
                    when {
                        request.url.encodedPath.endsWith("/oauth/token/") -> {
                            json(tokenJson())
                        }

                        request.url.encodedPath.endsWith("/user/info/") -> {
                            json(userJson)
                        }

                        request.jsonBody().contains("\"cursor\"") -> {
                            json(
                                """{"data":{"videos":[{"id":"fx-1","create_time":1762000000,"title":"",
                                   "video_description":"Fixture caption","duration":42,"view_count":500}],
                                   "cursor":0,"has_more":false},"error":{"code":"ok"}}""",
                            )
                        }

                        else -> {
                            json(
                                """{"data":{"videos":[{"id":"fx-2","create_time":1763000000,"title":"Fixture title",
                                   "view_count":900,"like_count":40,"comment_count":3,"share_count":5,
                                   "share_url":"https://example.invalid/fx-2"}],
                                   "cursor":1762500000000,"has_more":true},"error":{"code":"ok"}}""",
                            )
                        }
                    }
                }
            val store = secrets()
            val api = TikTokApi(http)
            val collection = TikTokCollector(api, TikTokAuth(api, store)).collect(now, "dev-1", day)

            val refreshForm = recorder.requests.first().form()
            assertEquals("refresh_token", refreshForm["grant_type"])
            assertEquals("fixture-refresh-1", refreshForm["refresh_token"])
            assertEquals("fixture-refresh-2", store.read(SecretName.TIKTOK_REFRESH_TOKEN))
            assertTrue(
                recorder.requests.drop(1).all { it.headers[HttpHeaders.Authorization] == "Bearer fixture-access" },
            )
            assertEquals(
                TikTokApi.VIDEO_FIELDS.joinToString(","),
                recorder.requests
                    .last()
                    .url.parameters["fields"],
            )

            assertEquals(listOf("fx-2", "fx-1"), collection.posts.map { it.postId })
            val older = collection.posts.single { it.postId == "fx-1" }
            assertEquals("Fixture caption", older.title)
            assertEquals(Instant.fromEpochSeconds(1762000000), older.publishedAt)
            assertEquals(42L, older.durationSeconds)

            val account = collection.snapshots.single { it.itemKind == ItemKind.ACCOUNT }
            assertEquals("fixture-open-id", account.itemId)
            assertEquals(1200.0, account.value("followers"))

            val fx1 = collection.snapshots.single { it.itemId == "fx-1" }
            assertEquals(500.0, fx1.value("views"))
            assertFalse("likes" in fx1.values, "a field TikTok left out stays missing, not zero")
            val fx2 = collection.snapshots.single { it.itemId == "fx-2" }
            assertEquals(mapOf("views" to 900.0, "likes" to 40.0, "comments" to 3.0, "shares" to 5.0), fx2.values)
            assertEquals(now + kotlin.time.Duration.parse("180d"), collection.tokenExpiresAt)
        }

    @Test
    fun mapsTikTokErrorsWithoutLeakingSecrets() =
        runTest {
            suspend fun failure(
                userInfo: String,
                status: HttpStatusCode,
            ): CollectionException {
                val http =
                    client { request ->
                        if (request.url.encodedPath.endsWith(
                                "/oauth/token/",
                            )
                        ) {
                            json(tokenJson())
                        } else {
                            json(userInfo, status)
                        }
                    }
                val api = TikTokApi(http)
                return assertFailsWith { TikTokCollector(api, TikTokAuth(api, secrets())).collect(now, "dev-1", day) }
            }

            fun error(code: String) = """{"data":{},"error":{"code":"$code","message":"m","log_id":"fx-log"}}"""

            val expired = failure(error("access_token_invalid"), HttpStatusCode.Unauthorized)
            assertEquals(FailureKind.SIGN_IN_EXPIRED, expired.kind)
            assertEquals("fx-log", expired.logId)
            assertEquals(
                FailureKind.SCOPE_MISSING,
                failure(error("scope_not_authorized"), HttpStatusCode.Unauthorized).kind,
            )
            assertEquals(
                FailureKind.RATE_LIMITED,
                failure(error("rate_limit_exceeded"), HttpStatusCode.TooManyRequests).kind,
            )
            assertEquals(FailureKind.PLATFORM_ERROR, failure("<html>", HttpStatusCode.BadGateway).kind)
            for (e in listOf(expired)) {
                assertFalse(e.message!!.contains("fixture-access"))
                assertFalse(e.message!!.contains("fixture-client-secret"))
            }
        }

    @Test
    fun refreshFailuresMeanSignInAgainOrMissingKeys() =
        runTest {
            val oauthError = """{"error":"invalid_grant","error_description":"expired","log_id":"fx"}"""
            val api = TikTokApi(client { json(oauthError, HttpStatusCode.BadRequest) })
            val expired = assertFailsWith<CollectionException> { TikTokAuth(api, secrets()).accessToken(now) }
            assertEquals(FailureKind.SIGN_IN_EXPIRED, expired.kind)

            val notConnected = assertFailsWith<CollectionException> { TikTokAuth(api, secrets(null)).accessToken(now) }
            assertEquals(FailureKind.NOT_CONNECTED, notConnected.kind)

            val noKeys = InMemorySecretStore().apply { write(SecretName.TIKTOK_REFRESH_TOKEN, "fixture") }
            val missing = assertFailsWith<CollectionException> { TikTokAuth(api, noKeys).accessToken(now) }
            assertEquals(FailureKind.MISSING_CLIENT_KEYS, missing.kind)
            assertTrue(missing.message!!.contains("FourSight/tiktok/client_key"))
            assertNull(noKeys.read(SecretName.TIKTOK_CLIENT_KEY))
        }
}
