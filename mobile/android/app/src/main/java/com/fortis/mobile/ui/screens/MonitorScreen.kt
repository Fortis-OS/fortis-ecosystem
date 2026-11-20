package com.fortis.mobile.ui.screens

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
import com.fortis.mobile.ensureTailscaleActive
import com.fortis.mobile.data.LaptopApi
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.*
import com.fortis.mobile.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun MonitorScreen(viewModel: FortisViewModel) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    val telemetry by viewModel.telemetry.collectAsState()
    val readiness by viewModel.readiness.collectAsState()
    val cpu = telemetry?.cpu ?: "--"
    val mem = telemetry?.mem ?: "--"
    val swap = telemetry?.swap ?: "--"
    val storage = telemetry?.storage ?: "--"
    val temp = telemetry?.temp ?: "--"
    val uptime = telemetry?.uptime ?: "--"
    val batteryLevel = readiness?.let { "${it.batteryPercent}%" } ?: "--"
    val batteryOk = readiness?.batteryOk == true
    val storageGb = readiness?.let { "${it.freeGb} GB" } ?: "--"
    val storageOk = readiness?.storageOk == true
    val tsActive = readiness?.tailscale == true
    val sshActive = readiness?.sshd == true

    // Logs
    var logsContent by remember { mutableStateOf("Tap 'Load Logs' to fetch live backup logs.") }
    var isLoadingLogs by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            
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
                        fontFamily = FrostMono,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "$batteryLevel ${if (batteryOk) "✓" else "✗"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FrostMono,
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
                        fontFamily = FrostMono,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "$storageGb ${if (storageOk) "✓" else "✗"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FrostMono,
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
                        fontFamily = FrostMono,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (tsActive) "RUNNING ✓" else "STOPPED ✗",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FrostMono,
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
                        fontFamily = FrostMono,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (sshActive) "RUNNING ✓" else "STOPPED ✗",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FrostMono,
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
                            logsContent = LaptopApi.backupLogs()
                        } catch (e: Exception) {
                            logsContent = "Couldn't fetch the logs: ${e.message}"
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
                    fontFamily = FrostMono,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))
    }
}
