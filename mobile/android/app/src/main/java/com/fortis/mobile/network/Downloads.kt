package com.fortis.mobile.network

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.fortis.mobile.data.Device
import java.io.File

/**
 * Download a file from the laptop over a signed request (DownloadManager
 * can't sign requests) into the phone's Downloads (docs/api §5).
 */
object Downloads {
    /**
     * Returns a short description of where the file was saved. Blocking.
     * [subdir] puts it under Downloads/Fortis/<subdir> (used when downloading a whole folder).
     */
    fun download(context: Context, remotePath: String, name: String, device: Device? = null, subdir: String = ""): String {
        val req = FortisApiClient.buildRequest("/api/v1/files/download?path=${Uri.encode(remotePath)}", device) { get() }
        FortisApiClient.getClient().newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val body = r.body ?: throw IllegalStateException("empty response")
            val safeName = name.substringAfterLast('/').ifBlank { "download" }
            // "/a/b" from "a/b"; no "..", no empty parts
            val folder = subdir.split('/').filter { it.isNotBlank() && it != "." && it != ".." }.joinToString("") { "/$it" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Fortis" + folder)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("cannot create file")
                try {
                    resolver.openOutputStream(uri)!!.use { out -> body.byteStream().copyTo(out) }
                    values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null); throw e
                }
                return "Downloads/Fortis$folder/$safeName"
            } else {
                // Android 9: app-specific folder, no storage permission needed.
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, folder.trimStart('/')).apply { mkdirs() }
                val f = File(dir, safeName)
                f.outputStream().use { out -> body.byteStream().copyTo(out) }
                return f.absolutePath
            }
        }
    }
}
