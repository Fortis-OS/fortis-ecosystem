package com.fortis.mobile.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.fortis.mobile.service.EventsService
import com.fortis.mobile.ui.components.FortisSpacing
import com.fortis.mobile.ui.theme.FrostMono

/** Sudo approvals on/off plus the phone settings they need (docs/deployment §5). */
@Composable
fun SudoSettingsCard() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(EventsService.enabled(ctx)) }
    var checks by remember { mutableStateOf(Checks.read(ctx)) }
    val online by EventsService.online.collectAsState()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) checks = Checks.read(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checks = Checks.read(ctx) }

    Card(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = RoundedCornerShape(FortisSpacing.cardCorner),
    ) {
        Column(Modifier.padding(FortisSpacing.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sudo approvals", style = MaterialTheme.typography.titleMedium, fontFamily = FrostMono)
                    Text(if (!enabled) "Off — laptops ask for the password" else "Connected to ${online.size} laptop(s)",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    EventsService.setEnabled(ctx, it)
                    if (it && !checks.notifications && Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                })
            }
            if (enabled) {
                if (!checks.notifications) Fix("Notifications are off") {
                    if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
                if (!checks.fullScreen) Fix("Full-screen prompts not allowed") {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")))
                }
                if (!checks.battery) Fix("Battery optimisation may cut the connection — set Fortis to Unrestricted") {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
    }
}

@Composable
private fun Fix(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("⚠ $text", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { runCatching(onClick) }) { Text("FIX", fontFamily = FrostMono) }
    }
}

private data class Checks(val notifications: Boolean, val fullScreen: Boolean, val battery: Boolean) {
    companion object {
        fun read(ctx: Context): Checks {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val notif = nm.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            val fsi = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
            val batt = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
            return Checks(notif, fsi, batt)
        }
    }
}
