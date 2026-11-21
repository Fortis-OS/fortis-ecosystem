package com.fortis.mobile.data

import android.content.Context
import com.fortis.mobile.network.Downloads
import com.fortis.mobile.network.FortisApiClient
import com.fortis.mobile.network.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

/** The laptop answered, but not with success. [message] is the agent's own explanation when it gave one. */
class ApiError(val code: Int, message: String) : IOException(message)

/** GET /media. Nothing playing is a 404 from the agent and becomes `null`, not an error. */
data class MediaState(
    val title: String,
    val artist: String,
    val album: String,
    val player: String,
    val status: String,
    val positionUs: Long,
    val lengthUs: Long,
) {
    val isPlaying get() = status == "Playing"

    companion object {
        fun parse(json: String): MediaState? {
            val o = JSONObject(json)
            val title = o.optString("title", "")
            if (title.isEmpty()) return null
            return MediaState(
                title = title,
                artist = o.optString("artist", ""),
                album = o.optString("album", ""),
                player = o.optString("player", ""),
                status = o.optString("status", ""),
                positionUs = o.optLong("position_us", 0L),
                lengthUs = o.optLong("length_us", 0L),
            )
        }
    }
}

/** [modified] is Unix seconds (0 when the laptop didn't say). */
data class RemoteEntry(val name: String, val isDir: Boolean, val size: Long, val modified: Long = 0)

/** GET /files/list: the folder the agent actually listed, directories first, then by name. */
data class Listing(val path: String, val entries: List<RemoteEntry>) {
    /** The path of [entry] inside this folder. */
    fun childPath(entry: RemoteEntry) = if (path.endsWith("/")) path + entry.name else "$path/${entry.name}"

    /** The parent folder, or null at the top. */
    val parent: String? get() = if (path.isEmpty() || path == "/") null else path.substringBeforeLast("/", "").ifEmpty { "/" }

    companion object {
        fun parse(json: String): Listing {
            val o = JSONObject(json)
            val arr = o.optJSONArray("entries")
            val entries = (0 until (arr?.length() ?: 0)).map { i ->
                val f = arr!!.getJSONObject(i)
                RemoteEntry(f.getString("name"), f.optBoolean("is_dir"), f.optLong("size"), f.optLong("modified"))
            }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            return Listing(o.optString("path", ""), entries)
        }
    }
}

/**
 * Every call the app makes to a paired laptop (docs/api §5). All functions are `suspend`, run on
 * [Dispatchers.IO], sign their request through [FortisApiClient], and throw [ApiError] when the
 * laptop answers with anything but success. [device] defaults to the active laptop.
 */
object LaptopApi {
    private val JSON = "application/json".toMediaType()

    /** The agent's error message from `{"error": {"message": …}}`, or a plain fallback. */
    internal fun errorMessage(code: Int, body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "The laptop answered HTTP $code"

    private suspend fun call(path: String, device: Device?, build: Request.Builder.() -> Unit): String =
        withContext(Dispatchers.IO) {
            val req = FortisApiClient.buildRequest(path, device, build)
            FortisApiClient.getClient().newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiError(r.code, errorMessage(r.code, body))
                body
            }
        }

    private fun json(o: JSONObject) = o.toString().toRequestBody(JSON)

    suspend fun telemetry(device: Device? = null): Telemetry =
        Telemetry.parse(call("/api/v1/telemetry", device) { get() })

    /** POST /actions. Returns the response body; volume actions include `volume_percent`. */
    suspend fun action(name: String, device: Device? = null): JSONObject? {
        val body = call("/api/v1/actions", device) { post(json(JSONObject().put("action", name))) }
        return body.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    suspend fun media(device: Device? = null): MediaState? = try {
        MediaState.parse(call("/api/v1/media", device) { get() })
    } catch (e: ApiError) {
        if (e.code == 404) null else throw e
    }

    suspend fun sendClipboard(text: String, device: Device? = null) {
        call("/api/v1/clipboard", device) { post(json(JSONObject().put("text", text))) }
    }

    suspend fun backupLogs(device: Device? = null): String =
        call("/api/v1/backup/logs", device) { get() }.ifBlank { "No logs yet." }

    /** The folders the laptop shares (agent.toml `[files] roots`), as absolute paths. */
    suspend fun fileRoots(device: Device? = null): List<String> {
        val arr = JSONObject(call("/api/v1/files/roots", device) { get() }).optJSONArray("roots")
        return (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }
    }

    /** An empty [path] asks for the laptop's default folder. */
    suspend fun listFiles(path: String, device: Device? = null): Listing {
        val q = if (path.isEmpty()) "" else "?path=" + android.net.Uri.encode(path)
        return Listing.parse(call("/api/v1/files/list$q", device) { get() })
    }

    /**
     * Multipart upload to the laptop's drop folder. [open] is called for each attempt (OkHttp may
     * retry), [onProgress] gets 0..1 when [size] is known.
     */
    suspend fun uploadFile(
        name: String,
        size: Long,
        open: () -> InputStream,
        onProgress: (Float) -> Unit = {},
        device: Device? = null,
    ) {
        val stream = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = if (size > 0) size else -1L
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    var lastReported = 0f
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        sink.write(buf, 0, n)
                        sent += n
                        if (size > 0) {
                            val p = (sent.toFloat() / size).coerceAtMost(1f)
                            if (p - lastReported >= 0.01f || p == 1f) { lastReported = p; onProgress(p) }
                        }
                    }
                }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", name, stream).build()
        call("/api/v1/files/drop?dest=drop", device) { post(body) }
    }

    /**
     * Downloads [remotePath] into the app's private cache (cache/open/), for opening in another app.
     * Re-uses the cached copy when it has the same size and is newer than [modified].
     * [onProgress] gets 0..1 when the laptop sends a length.
     */
    suspend fun fetchToCache(
        context: Context,
        remotePath: String,
        name: String,
        size: Long,
        modified: Long,
        onProgress: (Float) -> Unit = {},
        device: Device? = null,
    ): java.io.File = withContext(Dispatchers.IO) {
        val dir = java.io.File(context.cacheDir, "open").apply { mkdirs() }
        // drop copies older than a day so the cache doesn't grow forever
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "file" }
        val target = java.io.File(dir, safe)
        if (target.exists() && target.length() == size && target.lastModified() >= modified * 1000) return@withContext target
        val req = FortisApiClient.buildRequest("/api/v1/files/download?path=${android.net.Uri.encode(remotePath)}", device) { get() }
        FortisApiClient.getClient().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiError(r.code, errorMessage(r.code, r.body?.string().orEmpty()))
            val body = r.body ?: throw IOException("empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: size
            val tmp = java.io.File(dir, "$safe.part")
            tmp.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        got += n
                        if (total > 0) onProgress((got.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        }
        target
    }

    /** A file inside a folder being downloaded: its path on the laptop and its folder relative to the top. */
    data class FolderFile(val remotePath: String, val relDir: String, val entry: RemoteEntry)

    class FolderTooBig(message: String) : IOException(message)

    /**
     * Every file under [path] (depth-first), for downloading a whole folder. Folders the laptop
     * refuses are skipped and counted in [skipped]. Stops with [FolderTooBig] past [maxFiles].
     */
    suspend fun walkFolder(
        path: String,
        maxFiles: Int = 2000,
        skipped: (String) -> Unit = {},
        onFound: (Int) -> Unit = {},
        device: Device? = null,
    ): List<FolderFile> {
        val out = mutableListOf<FolderFile>()
        suspend fun walk(dir: String, rel: String) {
            val listing = try { listFiles(dir, device) } catch (e: ApiError) { skipped(dir); return }
            for (e in listing.entries) {
                if (e.isDir) walk(listing.childPath(e), if (rel.isEmpty()) e.name else "$rel/${e.name}")
                else {
                    out += FolderFile(listing.childPath(e), rel, e)
                    if (out.size > maxFiles) throw FolderTooBig("more than $maxFiles files")
                    onFound(out.size)
                }
            }
        }
        walk(path, "")
        return out
    }

    /** Saves into the phone's Downloads/Fortis; returns where. */
    suspend fun download(context: Context, remotePath: String, name: String, device: Device? = null, subdir: String = ""): String =
        withContext(Dispatchers.IO) { Downloads.download(context, remotePath, name, device, subdir) }
}
