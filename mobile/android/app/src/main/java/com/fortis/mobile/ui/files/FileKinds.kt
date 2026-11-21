package com.fortis.mobile.ui.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
import com.fortis.mobile.ui.theme.Frost
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a file is, for its icon, tint and the app Android should open it with. */
enum class FileKind(val label: String, val icon: ImageVector, val tint: Color) {
    Folder("Folder", Icons.Default.Folder, Color(0xFFE8B04A)),
    Image("Image", Icons.Default.Image, Color(0xFF5FB8F2)),
    Video("Video", Icons.Default.VideoFile, Color(0xFFB47CF2)),
    Audio("Audio", Icons.Default.AudioFile, Color(0xFF4FD1A5)),
    Pdf("PDF", Icons.Default.PictureAsPdf, Color(0xFFFF6B6B)),
    Sheet("Spreadsheet", Icons.Default.TableChart, Color(0xFF5BC27A)),
    Document("Document", Icons.Default.Description, Color(0xFF6D9CF2)),
    Code("Code / text", Icons.Default.Code, Color(0xFFF2A65A)),
    Archive("Archive", Icons.Default.Archive, Color(0xFFC9A26B)),
    Other("File", Icons.AutoMirrored.Filled.InsertDriveFile, Frost.muted);

    companion object {
        private val map = mapOf(
            Image to "jpg jpeg png gif webp heic heif bmp svg avif",
            Video to "mp4 mkv webm mov avi m4v 3gp",
            Audio to "mp3 m4a aac flac ogg opus wav",
            Pdf to "pdf",
            Sheet to "csv xls xlsx ods",
            Document to "doc docx odt rtf ppt pptx odp epub md",
            Code to "txt log json xml yaml yml toml ini conf sh py kt kts java rs js ts tsx jsx c h cpp go rb php html css sql gradle",
            Archive to "zip tar gz tgz xz bz2 7z rar zst apk deb",
        ).flatMap { (k, exts) -> exts.split(' ').map { it to k } }.toMap()

        fun of(name: String, isDir: Boolean): FileKind =
            if (isDir) Folder else map[name.substringAfterLast('.', "").lowercase()] ?: Other
    }
}

fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: when (FileKind.of(name, false)) {
            FileKind.Code -> "text/plain"
            else -> "application/octet-stream"
        }
}

fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** "21 Nov 2026, 02:14" (or "" when unknown). */
fun humanDate(unixSecs: Long): String =
    if (unixSecs <= 0) "" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(unixSecs * 1000))

private fun uriFor(context: Context, file: File) =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

/**
 * Opens [file] in the app Android picks for its type; [chooser] always asks ("Open with…").
 * Returns false when no installed app can open it.
 */
fun openInApp(context: Context, file: File, name: String, chooser: Boolean = false): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uriFor(context, file), mimeOf(name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(if (chooser) Intent.createChooser(intent, "Open $name with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

fun shareFile(context: Context, file: File, name: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mimeOf(name))
        .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, "Share $name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
