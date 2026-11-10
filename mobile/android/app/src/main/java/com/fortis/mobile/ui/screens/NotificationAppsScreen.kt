package com.fortis.mobile.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.fortis.mobile.NotificationMirror
import com.fortis.mobile.ui.components.*

private data class AppRow(val pkg: String, val label: String)

/** Pick which apps' notifications are mirrored to the laptop (docs/security §6.4). */
@Composable
fun NotificationAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val apps = remember {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { AppRow(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != context.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }
    var allowed by remember { mutableStateOf(NotificationMirror.allowed(context)) }
    val hasAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(FortisSpacing.screenPadding)) {
        Text("NOTIFICATIONS", style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 8.dp))
        Text("Ticked apps' notifications appear on the laptop. Nothing is sent until you tick an app.",
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
        if (!hasAccess) {
            FortisActionButton(text = "GRANT NOTIFICATION ACCESS", isPrimary = true, onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }, modifier = Modifier.padding(bottom = 12.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(apps, key = { it.pkg }) { app ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(app.label, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                    Switch(checked = app.pkg in allowed, onCheckedChange = { on ->
                        NotificationMirror.setAllowed(context, app.pkg, on)
                        allowed = NotificationMirror.allowed(context)
                    })
                }
            }
        }
        FortisActionButton(text = "DONE", onClick = onBack)
    }
}
