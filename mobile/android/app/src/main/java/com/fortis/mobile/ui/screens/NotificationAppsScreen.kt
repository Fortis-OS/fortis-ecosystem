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
import com.fortis.mobile.ui.theme.FrostMono
import com.fortis.mobile.ui.theme.FrostSans
import com.fortis.mobile.ui.theme.Frost
import androidx.compose.ui.unit.sp

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

    var query by remember { mutableStateOf("") }
    val shown = apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        FrostTopBar("Mirrored apps", onBack = onBack)
        Text(
            "Ticked apps' notifications appear on the laptop. Nothing is sent until you tick an app." +
                if (allowed.isNotEmpty()) " ${allowed.size} ticked." else "",
            fontFamily = FrostSans, color = Frost.muted, modifier = Modifier.padding(bottom = 12.dp),
        )
        if (!hasAccess) {
            ErrorLine("Fortis can't read notifications yet.")
            FortisActionButton(text = "Grant notification access", isPrimary = true, onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }, modifier = Modifier.padding(bottom = 12.dp))
        }
        FrostField(query, { query = it }, "", placeholder = "Search apps", modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.pkg }) { app ->
                val on = app.pkg in allowed
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).frostGlass(radius = 14.dp, soft = true)
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(app.label, fontFamily = FrostSans, fontSize = 15.sp, color = if (on) Frost.text else Frost.muted, modifier = Modifier.weight(1f), maxLines = 1)
                    Switch(checked = on, colors = frostSwitchColors(), onCheckedChange = { checked ->
                        NotificationMirror.setAllowed(context, app.pkg, checked)
                        allowed = NotificationMirror.allowed(context)
                    })
                }
            }
        }
    }
}
