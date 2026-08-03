package com.example.fortismobile.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.fortismobile.ensureTailscaleActive
import com.example.fortismobile.network.FortisApiClient
import com.example.fortismobile.ui.FortisViewModel
import com.example.fortismobile.ui.components.*
import com.example.fortismobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun MonitorScreen(viewModel: FortisViewModel) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()

    val ip = activeDevice?.ipAddress ?: ""
    val apiKey = activeDevice?.apiKey ?: ""
    val coroutineScope = rememberCoroutineScope()

    // Telemetry state
    var uptime by remember { mutableStateOf("--") }
    var cpu by remember { mutableStateOf("--") }
    var mem by remember { mutableStateOf("--") }
    var swap by remember { mutableStateOf("--") }
    var storage by remember { mutableStateOf("--") }
    var temp by remember { mutableStateOf("--") }

    // Backup readiness
    var tsActive by remember { mutableStateOf(false) }
    var sshActive by remember { mutableStateOf(false) }
    var storageGb by remember { mutableStateOf("--") }
    var batteryLevel by remember { mutableStateOf("--") }
    var batteryOk by remember { mutableStateOf(false) }
    var storageOk by remember { mutableStateOf(false) }

    // Logs
    var logsContent by remember { mutableStateOf("Tap 'Load Logs' to fetch live backup logs.") }
    var isLoadingLogs by remember { mutableStateOf(false) }

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
        // ─── Full Telemetry ──────────────────────────────────────
        FortisSection(title = "SYSTEM_TELEMETRY") {
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

        // ─── Backup Readiness (Detailed) ─────────────────────────
        FortisSection(title = "BACKUP_READINESS") {
            FortisCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Battery > 20%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "$batteryLevel ${if (batteryOk) "✓" else "✗"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (batteryOk) StatusGreen else StatusRed
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Free Space > 5GB",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "$storageGb ${if (storageOk) "✓" else "✗"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (storageOk) StatusGreen else StatusRed
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Tailscale Connected",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (tsActive) "RUNNING ✓" else "STOPPED ✗",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (tsActive) StatusGreen else StatusRed
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "SSHD Running",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (sshActive) "RUNNING ✓" else "STOPPED ✗",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (sshActive) StatusGreen else StatusRed
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))

        // ─── Live Logs ───────────────────────────────────────────
        FortisSection(title = "LIVE_LOGS") {
            FortisActionButton(
                text = if (isLoadingLogs) "LOADING..." else "LOAD_LOGS",
                onClick = {
                    if (!ensureTailscaleActive(context)) return@FortisActionButton
                    isLoadingLogs = true
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
                        } finally {
                            isLoadingLogs = false
                        }
                    }
                },
                enabled = !isLoadingLogs,
                isLoading = isLoadingLogs,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            FortisCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = logsContent,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))
    }
}
