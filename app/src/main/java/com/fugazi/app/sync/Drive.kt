package com.fugazi.app.sync

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The few Drive v3 REST calls the backup needs, over the platform's HTTP. Blocking — call
 * from a background thread.
 */
class Drive(private val token: String) {

    data class Item(val id: String, val name: String, val folder: Boolean, val modified: String)

    fun findFolder(name: String, parent: String): Item? = find(name, parent, folder = true)
    fun findFile(name: String, parent: String): Item? = find(name, parent, folder = false)

    fun createFolder(name: String, parent: String): String {
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER)
            .put("parents", org.json.JSONArray().put(parent))
        return JSONObject(call("POST", "$API/files?fields=id", "application/json", meta.toString().toByteArray()).text()).getString("id")
    }

    fun create(name: String, parent: String, bytes: ByteArray): String {
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put(parent))
        val boundary = "fugazi${System.nanoTime()}"
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n".toByteArray())
            write("--$boundary\r\nContent-Type: ${mime(name)}\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val r = call("POST", "$UPLOAD/files?uploadType=multipart&fields=id", "multipart/related; boundary=$boundary", body)
        return JSONObject(r.text()).getString("id")
    }

    /** Replace a file's content. False if the file is gone (deleted or trashed in Drive). */
    fun update(id: String, bytes: ByteArray): Boolean {
        val r = call("PATCH", "$UPLOAD/files/$id?uploadType=media&fields=id", "application/octet-stream", bytes, allow404 = true)
        return r.code != 404
    }

    fun children(folderId: String): List<Item> {
        val out = mutableListOf<Item>()
        var page: String? = null
        do {
            val q = enc("'$folderId' in parents and trashed = false")
            val url = "$API/files?q=$q&pageSize=1000&fields=${enc("nextPageToken,files(id,name,mimeType,modifiedTime)")}" +
                (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val j = JSONObject(call("GET", url).text())
            out += items(j)
            page = j.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out
    }

    fun download(id: String): ByteArray = call("GET", "$API/files/$id?alt=media").body

    private fun find(name: String, parent: String, folder: Boolean): Item? {
        val type = if (folder) "=" else "!="
        val q = enc("name = '${name.replace("'", "\\'")}' and '$parent' in parents and mimeType $type '$FOLDER' and trashed = false")
        val j = JSONObject(call("GET", "$API/files?q=$q&fields=${enc("files(id,name,mimeType,modifiedTime)")}").text())
        return items(j).maxByOrNull { it.modified }
    }

    private fun items(j: JSONObject): List<Item> {
        val a = j.optJSONArray("files") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Item(it.getString("id"), it.getString("name"), it.optString("mimeType") == FOLDER, it.optString("modifiedTime"))
        }
    }

    private class Response(val code: Int, val body: ByteArray) {
        fun text() = String(body)
    }

    private fun call(method: String, url: String, type: String? = null, body: ByteArray? = null, allow404: Boolean = false): Response {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            // HttpURLConnection has no PATCH; Google's APIs accept the override header instead.
            requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", type)
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            body?.let { b -> conn.outputStream.use { it.write(b) } }
            val code = conn.responseCode
            val bytes = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (code in 200..299 || (allow404 && code == 404)) return Response(code, bytes)
            val msg = runCatching { JSONObject(String(bytes)).optJSONObject("error")?.optString("message") }.getOrNull()
            error("Drive: HTTP $code${if (msg.isNullOrBlank()) "" else " — $msg"}")
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun mime(name: String) = when {
        name.endsWith(".md") -> "text/markdown"
        name.endsWith(".jsonl") || name.endsWith(".json") -> "application/json"
        else -> "text/plain"
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER = "application/vnd.google-apps.folder"
    }
}
