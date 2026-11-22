package io.github.rmac0001.foursight.core

import io.github.rmac0001.foursight.core.sync.DriveSyncStore
import io.github.rmac0001.foursight.core.sync.SyncException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises [DriveSyncStore] against a small in-memory fake of the Drive v3 endpoints it uses.
 * The fake follows the request shapes in the Drive reference; it's not a recording of real traffic.
 */
class DriveSyncStoreTest {
    private data class Item(
        val id: String,
        val name: String,
        val parent: String,
        val folder: Boolean,
        var content: ByteArray = ByteArray(0),
        var version: Int = 1,
    )

    private val items = mutableListOf<Item>()
    private val requests = mutableListOf<HttpRequestData>()
    private var nextId = 1

    private val engine =
        MockEngine { request ->
            requests += request
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            handle(request)
        }
    private val store = DriveSyncStore(HttpClient(engine), accessToken = { "test-token" })

    private fun MockRequestHandleScope.json(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun bytes(request: HttpRequestData) = (request.body as OutgoingContent.ByteArrayContent).bytes()

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val url = request.url
        val path = url.encodedPath
        return when {
            request.method == HttpMethod.Get && path == "/drive/v3/files" -> {
                val q = url.parameters["q"]!!
                val parent = Regex("'([^']+)' in parents").find(q)!!.groupValues[1]
                val name =
                    Regex("name = '((?:[^'\\\\]|\\\\.)*)'")
                        .find(q)
                        ?.groupValues
                        ?.get(1)
                        ?.replace("\\'", "'")
                assertTrue("trashed = false" in q)
                val matches = items.filter { it.parent == parent && (name == null || it.name == name) }
                val files =
                    matches.joinToString(",") {
                        val mime = if (it.folder) "application/vnd.google-apps.folder" else "application/octet-stream"
                        """{"id":"${it.id}","name":"${it.name}","mimeType":"$mime","version":"${it.version}"}"""
                    }
                json("""{"files":[$files]}""")
            }

            request.method == HttpMethod.Post && path == "/drive/v3/files" -> {
                val meta = Json.parseToJsonElement(bytes(request).decodeToString()).jsonObject
                assertEquals("application/vnd.google-apps.folder", meta["mimeType"]!!.jsonPrimitive.content)
                val item =
                    Item(
                        "id${nextId++}",
                        meta["name"]!!.jsonPrimitive.content,
                        (meta["parents"] as JsonArray).single().jsonPrimitive.content,
                        folder = true,
                    )
                items += item
                json("""{"id":"${item.id}"}""")
            }

            request.method == HttpMethod.Post && path == "/upload/drive/v3/files" -> {
                assertEquals("multipart", url.parameters["uploadType"])
                val contentType = request.body.contentType.toString()
                val boundary = contentType.substringAfter("boundary=")
                val parts = bytes(request).decodeToString().split("--$boundary")
                val meta = Json.parseToJsonElement(parts[1].substringAfter("\r\n\r\n").trim()).jsonObject
                val content = parts[2].substringAfter("\r\n\r\n").removeSuffix("\r\n")
                val item =
                    Item(
                        "id${nextId++}",
                        meta["name"]!!.jsonPrimitive.content,
                        meta["parents"]!!
                            .jsonArray
                            .single()
                            .jsonPrimitive.content,
                        folder = false,
                        content = content.encodeToByteArray(),
                    )
                items += item
                json("""{"id":"${item.id}"}""")
            }

            request.method == HttpMethod.Patch && path.startsWith("/upload/drive/v3/files/") -> {
                assertEquals("media", url.parameters["uploadType"])
                val item = items.single { it.id == path.substringAfterLast('/') }
                item.content = bytes(request)
                item.version++
                json("""{"id":"${item.id}"}""")
            }

            request.method == HttpMethod.Get && path.startsWith("/drive/v3/files/") -> {
                assertEquals("media", url.parameters["alt"])
                val item = items.singleOrNull { it.id == path.substringAfterLast('/') }
                if (item ==
                    null
                ) {
                    respond("""{"error":"notFound"}""", HttpStatusCode.NotFound)
                } else {
                    respond(item.content)
                }
            }

            else -> {
                error("Unexpected request ${request.method} $url")
            }
        }
    }

    @Test
    fun writeCreatesFoldersAndReadReturnsTheContent() =
        runTest {
            store.write("snapshots/dev-a/TIKTOK/2026-11-21.json", """{"a":1}""".encodeToByteArray())
            assertEquals(
                listOf("FourSight", "snapshots", "dev-a", "TIKTOK"),
                items.filter { it.folder }.map { it.name },
            )
            assertEquals("root", items.first { it.name == "FourSight" }.parent)
            assertContentEquals(
                """{"a":1}""".encodeToByteArray(),
                store.read("snapshots/dev-a/TIKTOK/2026-11-21.json"),
            )
        }

    @Test
    fun rewritingAFileUpdatesItInPlaceAndBumpsItsVersion() =
        runTest {
            store.write("events/dev-a/2026-11-21.jsonl", "one\n".encodeToByteArray())
            val before = store.list("events").single()
            store.write("events/dev-a/2026-11-21.jsonl", "one\ntwo\n".encodeToByteArray())
            val after = store.list("events").single()
            assertEquals(before.path, after.path)
            assertTrue(before.version != after.version)
            assertEquals(1, items.count { !it.folder })
        }

    @Test
    fun listIsRecursiveAndFoldersAreReused() =
        runTest {
            store.write("snapshots/dev-a/TIKTOK/2026-11-20.json", "{}".encodeToByteArray())
            store.write("snapshots/dev-a/TIKTOK/2026-11-21.json", "{}".encodeToByteArray())
            store.write("snapshots/dev-b/YOUTUBE/2026-11-21.json", "{}".encodeToByteArray())
            assertEquals(
                listOf(
                    "snapshots/dev-a/TIKTOK/2026-11-20.json",
                    "snapshots/dev-a/TIKTOK/2026-11-21.json",
                    "snapshots/dev-b/YOUTUBE/2026-11-21.json",
                ),
                store.list("snapshots").map { it.path }.sorted(),
            )
            assertEquals(1, items.count { it.folder && it.name == "dev-a" })
        }

    @Test
    fun aFreshDeviceFindsFilesAnotherClientCreated() =
        runTest {
            store.write("devices/dev-a.json", "{}".encodeToByteArray())
            val other = DriveSyncStore(HttpClient(engine), accessToken = { "test-token" })
            assertEquals(listOf("devices/dev-a.json"), other.list("devices").map { it.path })
        }

    @Test
    fun listingAMissingFolderIsEmptyAndCreatesNothing() =
        runTest {
            assertEquals(emptyList(), store.list("snapshots"))
            assertTrue(items.isEmpty())
        }

    @Test
    fun quotesInNamesAreEscaped() =
        runTest {
            store.write("devices/roger's pc.json", "{}".encodeToByteArray())
            // A second client has no cached ids, so it has to look the file up by name.
            val other = DriveSyncStore(HttpClient(engine), accessToken = { "test-token" })
            assertContentEquals("{}".encodeToByteArray(), other.read("devices/roger's pc.json"))
            val q = requests.last { it.method == HttpMethod.Get && it.url.parameters["q"]?.contains("roger") == true }
            assertTrue("name = 'roger\\'s pc.json'" in q.url.parameters["q"]!!)
        }

    @Test
    fun readingAMissingFileThrowsASyncException() =
        runTest {
            assertFailsWith<SyncException> { store.read("snapshots/nope.json") }
        }
}
