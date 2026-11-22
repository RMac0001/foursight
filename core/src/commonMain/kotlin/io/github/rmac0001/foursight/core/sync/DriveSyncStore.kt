package io.github.rmac0001.foursight.core.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [SyncStore] on the Google Drive REST API v3, scope `drive.file` (FourSight sees only the files it
 * created). Endpoints checked against the Drive v3 reference on November 21, 2026:
 * - `GET  https://www.googleapis.com/drive/v3/files` with `q` (`'<id>' in parents`, `trashed = false`)
 * - `POST https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart`
 * - `PATCH https://www.googleapis.com/upload/drive/v3/files/{fileId}?uploadType=media`
 * - `GET  https://www.googleapis.com/drive/v3/files/{fileId}?alt=media`
 *
 * [accessToken] supplies a current OAuth access token; token refresh happens outside this class,
 * and the token is never logged or included in exceptions.
 */
class DriveSyncStore(
    private val http: HttpClient,
    private val accessToken: suspend () -> String,
    private val rootFolderName: String = "FourSight",
) : SyncStore {
    private val folderIds = mutableMapOf<String, String>()
    private val fileIds = mutableMapOf<String, String>()

    override suspend fun list(prefix: String): List<SyncFile> {
        val folderId = folderId(prefix.trim('/'), create = false) ?: return emptyList()
        val result = mutableListOf<SyncFile>()
        val pending = ArrayDeque(listOf(prefix.trim('/') to folderId))
        while (pending.isNotEmpty()) {
            val (path, id) = pending.removeFirst()
            for (child in children(id)) {
                val childPath = if (path.isEmpty()) child.name else "$path/${child.name}"
                if (child.mimeType == FOLDER_MIME) {
                    folderIds[childPath] = child.id
                    pending += childPath to child.id
                } else {
                    fileIds[childPath] = child.id
                    result += SyncFile(childPath, child.version ?: child.modifiedTime ?: "")
                }
            }
        }
        return result
    }

    override suspend fun read(path: String): ByteArray {
        val id = fileId(path) ?: throw SyncException("No such file in Drive: $path")
        val response =
            http.get("$API/files/$id") {
                auth()
                parameter("alt", "media")
            }
        return response.checked("read $path").bodyAsBytes()
    }

    override suspend fun write(
        path: String,
        content: ByteArray,
    ) {
        val existing = fileId(path)
        if (existing != null) {
            http
                .patch("$UPLOAD/files/$existing") {
                    auth()
                    parameter("uploadType", "media")
                    setBody(ByteArrayContent(content, ContentType.Application.OctetStream))
                }.checked("update $path")
            return
        }
        val parentPath = path.substringBeforeLast('/', "")
        val parentId = folderId(parentPath, create = true)!!
        val metadata =
            buildJsonObject {
                put("name", path.substringAfterLast('/'))
                put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parentId)) })
            }
        val boundary = "foursight-${content.size}-${path.hashCode().toUInt()}"
        val body =
            buildString {
                append("--$boundary\r\n")
                append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                append(metadata.toString())
                append("\r\n--$boundary\r\n")
                append("Content-Type: application/octet-stream\r\n\r\n")
            }.encodeToByteArray() + content + "\r\n--$boundary--\r\n".encodeToByteArray()
        val response =
            http.post("$UPLOAD/files") {
                auth()
                parameter("uploadType", "multipart")
                parameter("fields", "id")
                setBody(ByteArrayContent(body, ContentType.parse("multipart/related; boundary=$boundary")))
            }
        val created = json.decodeFromString(DriveFile.serializer(), response.checked("create $path").bodyAsText())
        fileIds[path] = created.id
    }

    private suspend fun fileId(path: String): String? {
        fileIds[path]?.let { return it }
        val parentId = folderId(path.substringBeforeLast('/', ""), create = false) ?: return null
        val name = path.substringAfterLast('/')
        return children(parentId, nameFilter = name).firstOrNull { it.mimeType != FOLDER_MIME }?.id?.also {
            fileIds[path] = it
        }
    }

    /** Resolves a folder path under the FourSight root ("" is the root itself). */
    private suspend fun folderId(
        path: String,
        create: Boolean,
    ): String? {
        folderIds[path]?.let { return it }
        if (path.isEmpty()) {
            val root =
                children("root", nameFilter = rootFolderName).firstOrNull { it.mimeType == FOLDER_MIME }?.id
                    ?: if (create) createFolder(rootFolderName, "root") else return null
            folderIds[""] = root
            return root
        }
        val parentId = folderId(path.substringBeforeLast('/', ""), create) ?: return null
        val name = path.substringAfterLast('/')
        val id =
            children(parentId, nameFilter = name).firstOrNull { it.mimeType == FOLDER_MIME }?.id
                ?: if (create) createFolder(name, parentId) else return null
        folderIds[path] = id
        return id
    }

    private suspend fun createFolder(
        name: String,
        parentId: String,
    ): String {
        val metadata =
            buildJsonObject {
                put("name", name)
                put("mimeType", FOLDER_MIME)
                put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parentId)) })
            }
        val response =
            http.post("$API/files") {
                auth()
                parameter("fields", "id")
                setBody(ByteArrayContent(metadata.toString().encodeToByteArray(), ContentType.Application.Json))
            }
        return json.decodeFromString(DriveFile.serializer(), response.checked("create folder $name").bodyAsText()).id
    }

    private suspend fun children(
        parentId: String,
        nameFilter: String? = null,
    ): List<DriveFile> {
        val all = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val query =
                buildString {
                    append("'${escape(parentId)}' in parents and trashed = false")
                    if (nameFilter != null) append(" and name = '${escape(nameFilter)}'")
                }
            val response =
                http.get("$API/files") {
                    auth()
                    parameter("q", query)
                    parameter("spaces", "drive")
                    parameter("pageSize", 1000)
                    parameter("fields", "nextPageToken, files(id, name, mimeType, version, modifiedTime)")
                    pageToken?.let { parameter("pageToken", it) }
                }
            val page = json.decodeFromString(FileList.serializer(), response.checked("list folder").bodyAsText())
            all += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return all
    }

    private suspend fun HttpRequestBuilder.auth() {
        header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
    }

    private suspend fun HttpResponse.checked(action: String): HttpResponse {
        if (!status.isSuccess()) {
            // The response body may echo request details, but never the token; keep it short.
            throw SyncException("Google Drive couldn't $action (HTTP ${status.value}): ${bodyAsText().take(300)}")
        }
        return this
    }

    @Serializable
    private data class DriveFile(
        val id: String,
        val name: String = "",
        val mimeType: String = "",
        val version: String? = null,
        val modifiedTime: String? = null,
    )

    @Serializable
    private data class FileList(
        val files: List<DriveFile> = emptyList(),
        val nextPageToken: String? = null,
    )

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private val json = Json { ignoreUnknownKeys = true }

        private fun escape(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")
    }
}
