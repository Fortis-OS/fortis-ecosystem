package com.fortis.mobile.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
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
