package com.fortis.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fortis.mobile.ensureTailscaleActive
import com.fortis.mobile.data.LaptopApi
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.rememberPhoneBackup
import com.fortis.mobile.ui.sendClipboardToLaptop
import com.fortis.mobile.ui.toast
import com.fortis.mobile.ui.components.*
import com.fortis.mobile.ui.theme.*
import com.fortis.mobile.domain.CapabilityManager
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    viewModel: FortisViewModel,
    onOpenRemoteInput: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onSwitchDevice: () -> Unit = {}
) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()

    val telemetry by viewModel.telemetry.collectAsState()
    val readiness by viewModel.readiness.collectAsState()
    val cpu = telemetry?.cpu ?: "--"
    val mem = telemetry?.mem ?: "--"
    val swap = telemetry?.swap ?: "--"
    val storage = telemetry?.storage ?: "--"
    val temp = telemetry?.temp ?: "--"
    val uptime = telemetry?.uptime ?: "--"

    // Dialogs
    var showLogsDialog by remember { mutableStateOf(false) }
    var logsContent by remember { mutableStateOf("Loading logs...") }
    var showBackupDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val startBackup = rememberPhoneBackup()

    // Backup confirmation dialog
    if (showBackupDialog) {
        FortisConfirmDialog(
            title = "Confirm Backup",
            message = "Are you sure you want to force a laptop backup?",
            onConfirm = {
                showBackupDialog = false
                startBackup()
            },
            onDismiss = { showBackupDialog = false }
        )
    }

    // Logs dialog
    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            title = {
                Text("Live Backup Logs", fontFamily = FrostMono, style = MaterialTheme.typography.titleLarge)
            },
            text = {
                val scrollState = rememberScrollState()
                Text(
                    text = logsContent,
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .fillMaxHeight(0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FrostMono,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton(onClick = { showLogsDialog = false }) {
                    Text("Close", fontFamily = FrostMono, color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            
            .padding(FortisSpacing.screenPadding)
            .verticalScroll(scrollState)
    ) {
        // ─── Connected Device Card ───────────────────────────────
        FortisCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (activeDevice?.isOnline == true) StatusGreen else StatusRed,
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeDevice?.name ?: "Unknown",
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FrostMono,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = activeDevice?.ipAddress ?: "No IP",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FrostMono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onSwitchDevice) {
                    Text(
                        "SWITCH",
                        fontFamily = FrostMono,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))

        // ─── Live Telemetry ──────────────────────────────────────
        FortisSection(title = "TELEMETRY") {
            Row(modifier = Modifier.fillMaxWidth()) {
                FortisMetricCard(label = "CPU", value = cpu, modifier = Modifier.weight(1f))
                FortisMetricCard(label = "MEMORY", value = mem, modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                FortisMetricCard(label = "SWAP", value = swap, modifier = Modifier.weight(1f))
                FortisMetricCard(label = "STORAGE", value = storage, modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                FortisMetricCard(label = "TEMP", value = temp, modifier = Modifier.weight(1f))
                FortisMetricCard(label = "UPTIME", value = uptime, modifier = Modifier.weight(1f))
            }
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))

        // ─── Backup Readiness ────────────────────────────────────
        FortisSection(title = "BACKUP_READINESS") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FortisStatusChip(
                    label = "Battery",
                    isHealthy = readiness?.batteryOk == true,
                    modifier = Modifier.weight(1f)
                )
                FortisStatusChip(
                    label = "Storage",
                    isHealthy = readiness?.storageOk == true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FortisStatusChip(
                    label = "Tailscale",
                    isHealthy = readiness?.tailscale == true,
                    modifier = Modifier.weight(1f)
                )
                FortisStatusChip(
                    label = "SSHD",
                    isHealthy = readiness?.sshd == true,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))

        // ─── Quick Actions ───────────────────────────────────────
        FortisSection(title = "QUICK_ACTIONS") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (CapabilityManager.hasCapability(activeDevice, "backup")) {
                    FortisIconActionButton(
                        icon = Icons.Default.Backup,
                        label = "Backup",
                        onClick = { showBackupDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }
                FortisIconActionButton(
                    icon = Icons.Default.Mouse,
                    label = "Input",
                    onClick = onOpenRemoteInput,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FortisIconActionButton(
                    icon = Icons.Default.Folder,
                    label = "Files",
                    onClick = onOpenFiles,
                    modifier = Modifier.weight(1f)
                )
                if (CapabilityManager.hasCapability(activeDevice, "backup")) {
                    FortisIconActionButton(
                        icon = Icons.Default.List,
                        label = "Logs",
                        onClick = {
                            if (!ensureTailscaleActive(context)) return@FortisIconActionButton
                            showLogsDialog = true
                            logsContent = "Loading logs..."
                            coroutineScope.launch {
                                logsContent = try {
                                    LaptopApi.backupLogs()
                                } catch (e: Exception) {
                                    "Couldn't fetch the logs: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                FortisIconActionButton(
                    icon = Icons.Default.ContentPaste,
                    label = "Clipboard",
                    onClick = {
                        if (activeDevice == null) {
                            context.toast("Pick a laptop first")
                            return@FortisIconActionButton
                        }
                        coroutineScope.sendClipboardToLaptop(context)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))
    }
}
