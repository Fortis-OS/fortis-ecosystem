package com.fortis.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fortis.mobile.data.LaptopApi
import com.fortis.mobile.domain.CapabilityManager
import com.fortis.mobile.ensureTailscaleActive
import com.fortis.mobile.service.EventsService
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.FortisActionButton
import com.fortis.mobile.ui.components.FortisCaption
import com.fortis.mobile.ui.components.FortisCard
import com.fortis.mobile.ui.components.FortisConfirmDialog
import com.fortis.mobile.ui.components.FortisMetricCard
import com.fortis.mobile.ui.components.FrostChips
import com.fortis.mobile.ui.components.StatusDot
import com.fortis.mobile.ui.components.StatusRow
import com.fortis.mobile.ui.rememberPhoneBackup
import com.fortis.mobile.ui.rememberTermuxDaemonStart
import com.fortis.mobile.ui.theme.Frost
import com.fortis.mobile.ui.theme.FrostMono
import com.fortis.mobile.ui.theme.FrostSans
import kotlinx.coroutines.launch

enum class LaptopPane(val label: String) { Control("Control"), Files("Files"), Health("Health"), Backup("Backup") }

/** The Laptop tab: everything about the selected laptop, one chip per area. */
@Composable
fun LaptopScreen(
    viewModel: FortisViewModel,
    onOpenRemoteInput: () -> Unit,
    onOpenWebcam: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val device by viewModel.activeDevice.collectAsState()
    val online by EventsService.online.collectAsState()
    var pane by rememberSaveable { mutableStateOf(LaptopPane.Control) }
    val d = device
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        if (d == null) {
            NoLaptopCard(onOpenSettings)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            StatusDot(d.uuid in online)
            Spacer(Modifier.width(10.dp))
            Text(
                d.name, fontFamily = FrostSans, fontSize = 22.sp, color = Frost.text,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(12.dp))
            Text(d.ipAddress, fontFamily = FrostMono, fontSize = 12.sp, color = Frost.muted, maxLines = 1)
        }
        FrostChips(LaptopPane.entries.map { it to it.label }, pane, { pane = it })
        Box(Modifier.weight(1f)) {
            when (pane) {
                LaptopPane.Control -> ControlScreen(viewModel, onOpenRemoteInput = onOpenRemoteInput, onOpenWebcam = onOpenWebcam)
                LaptopPane.Files -> FilesScreen(viewModel)
                LaptopPane.Health -> HealthPane(viewModel)
                LaptopPane.Backup -> BackupPane(viewModel)
            }
        }
    }
}

@Composable
private fun HealthPane(viewModel: FortisViewModel) {
    val t by viewModel.telemetry.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // short values side by side, long ones ("4965 / 5805 MB") full width so they never get cut
        Row(Modifier.fillMaxWidth()) {
            FortisMetricCard("CPU", t?.cpu ?: "--", Modifier.weight(1f))
            FortisMetricCard("Temp", t?.temp ?: "--", Modifier.weight(1f))
            FortisMetricCard("Uptime", t?.uptime ?: "--", Modifier.weight(1f))
        }
        FortisMetricCard("Memory", t?.mem ?: "--", Modifier.fillMaxWidth())
        FortisMetricCard("Swap", t?.swap ?: "--", Modifier.fillMaxWidth())
        FortisMetricCard("Storage", t?.storage ?: "--", Modifier.fillMaxWidth())
        FortisCaption(
            if (t == null) "Waiting for the laptop…" else "Updates every 5 seconds",
            Modifier.padding(start = 4.dp, top = 8.dp, bottom = 16.dp),
        )
    }
}

@Composable
private fun BackupPane(viewModel: FortisViewModel) {
    val context = LocalContext.current
    val device by viewModel.activeDevice.collectAsState()
    val r by viewModel.readiness.collectAsState()
    val scope = rememberCoroutineScope()
    val startBackup = rememberPhoneBackup()
    val startDaemon = rememberTermuxDaemonStart()
    var confirm by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<String?>(null) }
    var loadingLogs by remember { mutableStateOf(false) }
    val canBackup = CapabilityManager.hasCapability(device, "backup")

    if (confirm) {
        FortisConfirmDialog(
            title = "Run a backup now?",
            message = "The laptop runs fortis-backup and pushes today's snapshot to this phone.",
            onConfirm = { confirm = false; startBackup() },
            onDismiss = { confirm = false },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FortisCard {
            FortisCaption("Phone readiness", Modifier.padding(bottom = 4.dp))
            StatusRow("Battery", r?.let { "${it.batteryPercent}%" } ?: "--", r?.batteryOk)
            StatusRow("Free storage", r?.let { "${it.freeGb} GB" } ?: "--", r?.storageOk)
            StatusRow("Tailscale", if (r?.tailscale == true) "Connected" else "Off", r?.tailscale)
            StatusRow(
                "SSH daemon (Termux)",
                if (r?.sshd == true) "Running" else "Stopped · tap to start",
                r?.sshd,
                onClick = if (r?.sshd == true) null else startDaemon,
            )
        }
        if (canBackup) {
            FortisActionButton(text = "Run backup", isPrimary = true, onClick = { confirm = true })
        } else {
            FortisCaption("This laptop has no backup script configured.")
        }
        FortisCard {
            FortisCaption("Backup log", Modifier.padding(bottom = 8.dp))
            Text(
                logs ?: "Load the latest backup log from the laptop.",
                fontFamily = FrostMono,
                fontSize = 11.sp,
                color = if (logs == null) Frost.muted else Frost.text,
            )
            Spacer(Modifier.height(10.dp))
            FortisActionButton(
                text = if (loadingLogs) "Loading…" else "Load log",
                isLoading = loadingLogs,
                enabled = !loadingLogs,
                onClick = {
                    if (!ensureTailscaleActive(context)) return@FortisActionButton
                    loadingLogs = true
                    scope.launch {
                        logs = try { LaptopApi.backupLogs() } catch (e: Exception) { "Couldn't fetch the log: ${e.message}" }
                        loadingLogs = false
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Shown wherever a laptop is needed but none is selected or paired. */
@Composable
fun NoLaptopCard(onOpenSettings: () -> Unit) {
    FortisCard {
        Text("No laptop selected", fontFamily = FrostSans, fontSize = 20.sp, color = Frost.text)
        Spacer(Modifier.height(6.dp))
        Text(
            "Pair a laptop, or pick one, in Settings → Laptops. The vault works without one.",
            fontFamily = FrostSans,
            color = Frost.muted,
        )
        Spacer(Modifier.height(14.dp))
        FortisActionButton(text = "Open Settings", isPrimary = true, onClick = onOpenSettings)
    }
}
