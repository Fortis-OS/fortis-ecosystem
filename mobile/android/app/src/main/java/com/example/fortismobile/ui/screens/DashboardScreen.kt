package com.example.fortismobile.ui.screens

import android.content.Context
import android.widget.Toast
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
import com.example.fortismobile.ensureTailscaleActive
import com.example.fortismobile.network.FortisApiClient
import com.example.fortismobile.ui.FortisViewModel
import com.example.fortismobile.ui.components.*
import com.example.fortismobile.ui.theme.*
import com.example.fortismobile.domain.CapabilityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Composable
fun DashboardScreen(
    viewModel: FortisViewModel,
    onOpenTerminal: () -> Unit,
    onOpenRemoteInput: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onSwitchDevice: () -> Unit = {}
) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()

    val ip = activeDevice?.ipAddress ?: ""
    val apiKey = activeDevice?.apiKey ?: ""

    // Telemetry state
    var uptime by remember { mutableStateOf("--") }
    var cpu by remember { mutableStateOf("--") }
    var mem by remember { mutableStateOf("--") }
    var swap by remember { mutableStateOf("--") }
    var storage by remember { mutableStateOf("--") }
    var temp by remember { mutableStateOf("--") }

    // Backup readiness state
    var tsActive by remember { mutableStateOf(false) }
    var sshActive by remember { mutableStateOf(false) }
    var storageGb by remember { mutableStateOf("--") }
    var batteryLevel by remember { mutableStateOf("--") }
    var batteryOk by remember { mutableStateOf(false) }
    var storageOk by remember { mutableStateOf(false) }

    // Dialogs
    var showLogsDialog by remember { mutableStateOf(false) }
    var logsContent by remember { mutableStateOf("Loading logs...") }
    var showBackupDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Termux Permission Granted! Try again.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Termux RUN_COMMAND permission is required!", Toast.LENGTH_LONG).show()
        }
    }

    // Backup confirmation dialog
    if (showBackupDialog) {
        FortisConfirmDialog(
            title = "Confirm Backup",
            message = "Are you sure you want to force a laptop backup?",
            onConfirm = {
                showBackupDialog = false
                if (ip.isEmpty() || apiKey.isEmpty()) {
                    Toast.makeText(context, "Set IP/Key in Workspace!", Toast.LENGTH_SHORT).show()
                    return@FortisConfirmDialog
                }
                if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch("com.termux.permission.RUN_COMMAND")
                    return@FortisConfirmDialog
                }
                try {
                    val intent = android.content.Intent("com.termux.RUN_COMMAND")
                    intent.setClassName("com.termux", "com.termux.app.RunCommandService")
                    intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                    intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "bash /data/data/com.termux/files/home/.local/bin/fortis-mobile start > /data/data/com.termux/files/home/intent.log 2>&1"))
                    intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                    intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "4")
                    context.startService(intent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to start Termux: ${e.message}", Toast.LENGTH_LONG).show()
                }

                coroutineScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val client = FortisApiClient.getClient()
                            val json = """{"action":"run_backup"}"""
                            val body = json.toRequestBody("application/json".toMediaType())
                            val request = FortisApiClient.buildRequest("/api/v1/action") { post(body) }
                            client.newCall(request).execute().close()
                        }
                    } catch (e: Exception) {}
                }
            },
            onDismiss = { showBackupDialog = false }
        )
    }

    // Logs dialog
    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            title = {
                Text("Live Backup Logs", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge)
            },
            text = {
                val scrollState = rememberScrollState()
                Text(
                    text = logsContent,
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .fillMaxHeight(0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                TextButton(onClick = { showLogsDialog = false }) {
                    Text("Close", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }

    // Auto-refresh telemetry
    LaunchedEffect(Unit) {
        if (ip.isNotEmpty() && apiKey.isNotEmpty()) {
            while (true) {
                try {
                    withContext(Dispatchers.IO) {
                        val client = FortisApiClient.getClient()
                        val request = FortisApiClient.buildRequest("/api/v1/telemetry") { get() }
                        client.newCall(request).execute().use { response ->
                            val bodyStr = response.body?.string() ?: "{}"
                            val json = JSONObject(bodyStr)
                            uptime = json.optString("uptime", "Unknown").replace("\n", "")
                            cpu = json.optString("cpu", "--")
                            mem = "${json.optString("mem_used", "--")} / ${json.optString("mem_total", "--")}"
                            swap = "${json.optString("swap_used", "--")} / ${json.optString("swap_total", "--")}"
                            storage = "${json.optString("storage_used", "--")} / ${json.optString("storage_total", "--")}"
                            temp = json.optString("temp", "--")
                        }
                    }
                } catch (e: Exception) {}
                delay(5000)
            }
        }
    }

    // Auto-refresh backup readiness
    LaunchedEffect(Unit) {
        while (true) {
            tsActive = ensureTailscaleActive(context)

            sshActive = try {
                withContext(Dispatchers.IO) {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", 8022), 500)
                    socket.close()
                    true
                }
            } catch (e: Exception) { false }

            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
            val gbAvailable = bytesAvailable / (1024 * 1024 * 1024)
            storageGb = "${gbAvailable} GB"
            storageOk = gbAvailable > 5

            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            batteryLevel = "${level}%"
            batteryOk = level > 20

            delay(5000)
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
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = activeDevice?.ipAddress ?: "No IP",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onSwitchDevice) {
                    Text(
                        "SWITCH",
                        fontFamily = FontFamily.Monospace,
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
                    isHealthy = batteryOk,
                    modifier = Modifier.weight(1f)
                )
                FortisStatusChip(
                    label = "Storage",
                    isHealthy = storageOk,
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
                    isHealthy = tsActive,
                    modifier = Modifier.weight(1f)
                )
                FortisStatusChip(
                    label = "SSHD",
                    isHealthy = sshActive,
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
                if (CapabilityManager.hasCapability(activeDevice, "terminal")) {
                    FortisIconActionButton(
                        icon = Icons.Default.Terminal,
                        label = "Terminal",
                        onClick = onOpenTerminal,
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
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        val client = FortisApiClient.getClient()
                                        val request = FortisApiClient.buildRequest("/api/v1/backup/logs") { get() }
                                        client.newCall(request).execute().body?.string() ?: "No logs returned"
                                    }
                                    logsContent = result
                                } catch (e: Exception) {
                                    logsContent = "Failed to fetch logs: ${e.message}"
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
                            Toast.makeText(context, "Select a device first!", Toast.LENGTH_SHORT).show()
                            return@FortisIconActionButton
                        }
                        if (!ensureTailscaleActive(context)) return@FortisIconActionButton
                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clipData = clipboardManager.primaryClip
                        if (clipData != null && clipData.itemCount > 0) {
                            val text = clipData.getItemAt(0).text.toString()
                            coroutineScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        val client = FortisApiClient.getClient()
                                        val body = text.toRequestBody("text/plain".toMediaType())
                                        val request = FortisApiClient.buildRequest("/api/v1/clipboard") { post(body) }
                                        client.newCall(request).execute().close()
                                    }
                                    Toast.makeText(context, "Sent to Laptop!", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            Toast.makeText(context, "Clipboard is empty!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))
    }
}
