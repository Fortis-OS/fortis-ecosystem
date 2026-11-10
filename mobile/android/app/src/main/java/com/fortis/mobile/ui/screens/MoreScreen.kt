package com.fortis.mobile.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fortis.mobile.ensureTailscaleActive
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.*
import com.fortis.mobile.ui.theme.*

@Composable
fun MoreScreen(
    viewModel: FortisViewModel,
    onOpenNotificationApps: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenAuthenticator: () -> Unit
) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Termux Permission Granted! Try again.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Termux RUN_COMMAND permission is required!", Toast.LENGTH_LONG).show()
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(FortisSpacing.screenPadding)
            .verticalScroll(scrollState)
    ) {
        // ─── Communication ───────────────────────────────────────
        FortisSection(title = "COMMUNICATION") {
            FortisActionButton(
                text = "NOTIFICATION_SYNC",
                onClick = {
                    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    context.startActivity(intent)
                },
                modifier = Modifier.padding(bottom = FortisSpacing.buttonGap)
            )
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))

        // ─── Security ────────────────────────────────────────────
        FortisSection(title = "SECURITY") {
            FortisActionButton(
                text = "VAULT",
                onClick = onOpenVault,
                modifier = Modifier.padding(bottom = FortisSpacing.buttonGap)
            )
            FortisActionButton(
                text = "AUTHENTICATOR",
                onClick = onOpenAuthenticator,
                modifier = Modifier.padding(bottom = FortisSpacing.buttonGap)
            )
            // Password autofill (security §6.5): Fortis must be the selected autofill service.
            val afm = remember { context.getSystemService(android.view.autofill.AutofillManager::class.java) }
            var autofillOn by remember { mutableStateOf(afm?.hasEnabledAutofillServices() == true) }
            val afLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
            ) { autofillOn = afm?.hasEnabledAutofillServices() == true }
            FortisActionButton(
                text = if (autofillOn) "AUTOFILL: ON" else "TURN_ON_AUTOFILL",
                onClick = {
                    if (autofillOn) {
                        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, android.net.Uri.parse("package:${context.packageName}"))) }
                    } else {
                        runCatching {
                            afLauncher.launch(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, android.net.Uri.parse("package:${context.packageName}")))
                        }.onFailure { Toast.makeText(context, "Open Settings → Passwords → Autofill service and pick Fortis", Toast.LENGTH_LONG).show() }
                    }
                },
                modifier = Modifier.padding(bottom = FortisSpacing.buttonGap)
            )
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))

        // ─── Background Sync ─────────────────────────────────────
        var cameraSyncOn by remember { mutableStateOf(com.fortis.mobile.ImageSyncWorker.isEnabled(context)) }
        FortisSection(title = "BACKGROUND_SYNC") {
            FortisActionButton(
                text = if (cameraSyncOn) "STOP_CAMERA_ROLL_SYNC" else "START_CAMERA_ROLL_SYNC",
                onClick = {
                    cameraSyncOn = com.fortis.mobile.ImageSyncWorker.setEnabled(context, !cameraSyncOn)
                    Toast.makeText(context, if (cameraSyncOn) "Daily camera-roll sync on (Wi-Fi only)" else "Camera-roll sync off", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.padding(bottom = FortisSpacing.buttonGap)
            )
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))

        FortisSection(title = "NOTIFICATION_MIRRORING") {
            Text("Off until you pick apps. Only the apps you tick are sent to the laptop.",
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            FortisActionButton(
                text = "CHOOSE_APPS",
                onClick = onOpenNotificationApps,
                modifier = Modifier.padding(bottom = FortisSpacing.buttonGap)
            )
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))

        // ─── System Services ─────────────────────────────────────
        FortisSection(title = "SYSTEM_SERVICES") {
            FortisActionButton(
                text = "START_SSH_DAEMON",
                onClick = {
                    if (!ensureTailscaleActive(context)) return@FortisActionButton
                    if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        permissionLauncher.launch("com.termux.permission.RUN_COMMAND")
                        return@FortisActionButton
                    }
                    try {
                        val intent = android.content.Intent("com.termux.RUN_COMMAND")
                        intent.setClassName("com.termux", "com.termux.app.RunCommandService")
                        intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "bash /data/data/com.termux/files/home/.local/bin/fortis-mobile start > /data/data/com.termux/files/home/intent.log 2>&1"))
                        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "4")
                        context.startService(intent)
                        Toast.makeText(context, "SSH Daemon Started!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to start Termux: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))
    }
}
