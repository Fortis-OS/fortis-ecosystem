package com.fortis.mobile.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.fortis.mobile.ImageSyncWorker
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.FortisCaption
import com.fortis.mobile.ui.components.FortisCard
import com.fortis.mobile.ui.components.StatusRow
import com.fortis.mobile.ui.toast
import com.fortis.mobile.ui.theme.Frost
import com.fortis.mobile.ui.theme.FrostMono
import com.fortis.mobile.ui.theme.FrostSans
import com.fortis.mobile.vault.VaultSession

/** Settings: laptops, what the phone sends to the laptop, and security. */
@Composable
fun SettingsScreen(viewModel: FortisViewModel, onOpenNotificationApps: () -> Unit) {
    val context = LocalContext.current
    var pairing by remember { mutableStateOf(false) }
    if (pairing) {
        val devices by viewModel.devices.collectAsState()
        PairScreen(viewModel = viewModel, firstLaptop = devices.isEmpty(), onDone = { pairing = false })
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Settings", fontFamily = FrostSans, fontSize = 26.sp, color = Frost.text)

        // ─── Laptops ────────────────────────────────────────────────
        FortisCaption("Laptops", Modifier.padding(start = 4.dp, top = 8.dp))
        LaptopsSection(viewModel, onPair = { pairing = true })

        // ─── Phone → laptop ─────────────────────────────────────────
        FortisCaption("Phone → laptop", Modifier.padding(start = 4.dp, top = 16.dp))
        FortisCard {
            val listenerOn = remember {
                NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            }
            StatusRow(
                "Notification access",
                if (listenerOn) "Allowed" else "Not allowed",
                if (listenerOn) true else null,
                onClick = { context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) },
            )
            StatusRow("Mirrored apps", "Choose", null, onClick = onOpenNotificationApps)
            var cameraSync by remember { mutableStateOf(ImageSyncWorker.isEnabled(context)) }
            StatusRow(
                "Camera-roll sync",
                if (cameraSync) "Daily, Wi-Fi only" else "Off",
                if (cameraSync) true else null,   // off is a choice, not a fault
                onClick = {
                    cameraSync = ImageSyncWorker.setEnabled(context, !cameraSync)
                    context.toast(if (cameraSync) "Daily camera-roll sync on (Wi-Fi only)" else "Camera-roll sync off")
                },
            )
            Text(
                "Off until you choose. Only the apps you tick are mirrored to the laptop.",
                fontFamily = FrostSans, fontSize = 12.sp, color = Frost.subtle, modifier = Modifier.padding(top = 6.dp),
            )
        }

        // ─── Security ───────────────────────────────────────────────
        FortisCaption("Security", Modifier.padding(start = 4.dp, top = 16.dp))
        FortisCard {
            val afm = remember { context.getSystemService(AutofillManager::class.java) }
            var autofillOn by remember { mutableStateOf(afm?.hasEnabledAutofillServices() == true) }
            val afLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                autofillOn = afm?.hasEnabledAutofillServices() == true
            }
            StatusRow(
                "Autofill",
                if (autofillOn) "Fortis is the autofill service" else "Off · tap to turn on",
                autofillOn,
                onClick = {
                    runCatching {
                        afLauncher.launch(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${context.packageName}")))
                    }.onFailure { context.toast("Open Settings → Passwords → Autofill service and pick Fortis", long = true) }
                },
            )
            StatusRow("Vault auto-lock", "${VaultSession.BACKGROUND_MS / 1000} s after leaving the app", null)
            StatusRow("Screenshots of the vault", "Blocked", true)
        }

        // ─── About ──────────────────────────────────────────────────
        FortisCaption("About", Modifier.padding(start = 4.dp, top = 16.dp))
        FortisCard {
            val version = remember {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
            }
            Text("Fortis $version", fontFamily = FrostSans, fontSize = 16.sp, color = Frost.text)
            Text(context.packageName, fontFamily = FrostMono, fontSize = 12.sp, color = Frost.muted)
            Text(
                "Your vault, sudo approvals and laptop control, over Tailscale. No cloud.",
                fontFamily = FrostSans, fontSize = 12.sp, color = Frost.subtle, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
