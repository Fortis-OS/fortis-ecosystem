package com.fortis.mobile.ui

import android.content.ClipboardManager
import android.net.Uri
import android.provider.OpenableColumns
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fortis.mobile.ui.theme.Frost
import com.fortis.mobile.ui.theme.FrostMono
import com.fortis.mobile.ui.theme.FrostSans
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.fortis.mobile.data.LaptopApi
import com.fortis.mobile.data.PhoneStatus
import com.fortis.mobile.data.StartResult
import com.fortis.mobile.ensureTailscaleActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

// Tap handlers shared by several screens. These run on the main thread, may prompt for Tailscale
// (unlike the polling loops), and report what the laptop really answered.

fun Context.toast(message: String, long: Boolean = false) =
    Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

/**
 * POST /actions [name]; shows [success] (if any) only when the laptop confirms, otherwise the
 * agent's error. [onResult] receives the response body (volume actions carry `volume_percent`).
 */
fun CoroutineScope.laptopAction(
    context: Context,
    name: String,
    success: String = "",
    onResult: (JSONObject?) -> Unit = {},
) {
    if (!ensureTailscaleActive(context)) return
    launch {
        try {
            val body = LaptopApi.action(name)
            if (success.isNotEmpty()) context.toast(success)
            onResult(body)
        } catch (e: Exception) {
            context.toast("Couldn't $name: ${e.message}", long = true)
        }
    }
}

/** Sends the phone's clipboard text to the laptop's clipboard. */
fun CoroutineScope.sendClipboardToLaptop(context: Context) {
    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    if (text.isEmpty()) {
        context.toast("The clipboard is empty")
        return
    }
    if (!ensureTailscaleActive(context)) return
    launch {
        try {
            LaptopApi.sendClipboard(text)
            context.toast("Sent to the laptop's clipboard")
        } catch (e: Exception) {
            context.toast("Couldn't send the clipboard: ${e.message}", long = true)
        }
    }
}

/** Returns a tap handler that starts Termux's side and asks the laptop to run its backup. */
@Composable
fun rememberPhoneBackup(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        context.toast(
            if (granted) "Termux permission granted. Tap Backup again."
            else "Fortis needs Termux's \"Run command\" permission to start a backup.",
            long = !granted,
        )
    }
    return {
        if (ensureTailscaleActive(context)) {
            scope.launch {
                when (val r = PhoneStatus.startPhoneBackup(context)) {
                    StartResult.NeedsPermission -> permission.launch(PhoneStatus.TERMUX_PERMISSION)
                    StartResult.Started -> context.toast("Backup started on the laptop")
                    is StartResult.Failed -> context.toast(r.reason, long = true)
                }
            }
        }
    }
}

/** Returns a tap handler that starts Termux's SSH daemon (`fortis-mobile start`). */
@Composable
fun rememberTermuxDaemonStart(): () -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        context.toast(if (granted) "Termux permission granted. Tap again." else "Fortis needs Termux's \"Run command\" permission.")
    }
    return {
        when (val r = PhoneStatus.startTermuxDaemon(context)) {
            StartResult.NeedsPermission -> permission.launch(PhoneStatus.TERMUX_PERMISSION)
            StartResult.Started -> context.toast("SSH daemon started")
            is StartResult.Failed -> context.toast(r.reason, long = true)
        }
    }
}

private data class Upload(val index: Int, val total: Int, val name: String, val fraction: Float)

/**
 * Returns a tap handler that picks files and sends them to the laptop's drop folder, showing its
 * own progress dialog and reporting exactly which files failed. Used by Home and Laptop → Files.
 */
@Composable
fun fileSender(): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var upload by remember { mutableStateOf<Upload?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isEmpty() || !ensureTailscaleActive(context)) return@rememberLauncherForActivityResult
        scope.launch {
            var sent = 0
            val failed = mutableListOf<String>()
            uris.forEachIndexed { i, uri ->
                val name = displayName(context, uri)
                upload = Upload(i + 1, uris.size, name, 0f)
                try {
                    LaptopApi.uploadFile(
                        name = name,
                        size = sizeOf(context, uri),
                        open = { context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("can't read $name") },
                        onProgress = { p -> upload = upload?.copy(fraction = p) },
                    )
                    sent++
                } catch (e: Exception) {
                    failed += "$name (${e.message})"
                }
            }
            upload = null
            context.toast(
                if (failed.isEmpty()) "Sent $sent file(s) to the laptop"
                else "Sent $sent, failed ${failed.size}: ${failed.joinToString()}",
                long = failed.isNotEmpty(),
            )
        }
    }
    upload?.let { u ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Sending to the laptop", fontFamily = FrostSans) },
            text = {
                Column {
                    Text("${u.index} of ${u.total}: ${u.name}", maxLines = 1, fontFamily = FrostMono, color = Frost.text)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { u.fraction }, modifier = Modifier.fillMaxWidth(), color = Frost.accent, trackColor = Frost.faint)
                    Spacer(Modifier.height(4.dp))
                    Text("${(u.fraction * 100).toInt()}%", fontFamily = FrostMono, color = Frost.muted)
                }
            },
            confirmButton = {},
            containerColor = Frost.sheet,
        )
    }
    return { picker.launch("*/*") }
}

private fun sizeOf(context: Context, uri: Uri): Long =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
    } ?: 0L

private fun displayName(context: Context, uri: Uri): String {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
    }
    return uri.path?.substringAfterLast('/')?.ifBlank { null } ?: "file"
}
