package com.example.fortismobile.ui.screens

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
import com.example.fortismobile.ensureTailscaleActive
import com.example.fortismobile.ui.FortisViewModel
import com.example.fortismobile.ui.components.*
import com.example.fortismobile.ui.theme.*

@Composable
fun MoreScreen(
    viewModel: FortisViewModel,
    onOpenWebcam: () -> Unit,
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

        // ─── Peripherals ─────────────────────────────────────────
        FortisSection(title = "PERIPHERALS") {
            FortisActionButton(
                text = "REMOTE_WEBCAM",
                onClick = onOpenWebcam,
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
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))

        // ─── Background Sync ─────────────────────────────────────
        FortisSection(title = "BACKGROUND_SYNC") {
            FortisActionButton(
                text = "START_CAMERA_ROLL_SYNC",
                onClick = {
                    val workRequest = androidx.work.PeriodicWorkRequestBuilder<com.example.fortismobile.ImageSyncWorker>(1, java.util.concurrent.TimeUnit.DAYS).build()
                    androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork("CameraSync", androidx.work.ExistingPeriodicWorkPolicy.KEEP, workRequest)
                    Toast.makeText(context, "Camera Roll Sync Scheduled!", Toast.LENGTH_SHORT).show()
                },
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
