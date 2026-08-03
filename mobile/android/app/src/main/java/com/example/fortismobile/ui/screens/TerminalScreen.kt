package com.example.fortismobile.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.fortismobile.ensureTailscaleActive
import com.example.fortismobile.ui.FortisViewModel
import com.example.fortismobile.ui.components.*
import com.example.fortismobile.ui.theme.*

@Composable
fun TerminalScreen(viewModel: FortisViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()
    val ip = activeDevice?.ipAddress ?: ""

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Termux Permission Granted! Try again.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Termux RUN_COMMAND permission is required!", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "TRUE_TERMINAL",
            style = MaterialTheme.typography.headlineLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "This will seamlessly switch to the Termux app and automatically initiate a secure, interactive VT100 SSH session to your laptop.",
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))

        FortisActionButton(
            text = "< GO_BACK",
            onClick = { onBack() },
            modifier = Modifier.padding(bottom = FortisSpacing.buttonGap)
        )

        FortisActionButton(
            text = "LAUNCH_SSH_SESSION",
            isPrimary = true,
            onClick = {
                if (ip.isEmpty()) {
                    Toast.makeText(context, "Set IP in Workspace first", Toast.LENGTH_SHORT).show()
                    return@FortisActionButton
                }
                if (!ensureTailscaleActive(context)) return@FortisActionButton
                if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch("com.termux.permission.RUN_COMMAND")
                    return@FortisActionButton
                }

                try {
                    val intent = Intent()
                    intent.setClassName("com.termux", "com.termux.app.RunCommandService")
                    intent.action = "com.termux.RUN_COMMAND"
                    intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                    intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "ssh -i ~/.ssh/sshkey ganeshak11@$ip"))
                    intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                    intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                    context.startService(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to launch Termux. Is it installed?", Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}
