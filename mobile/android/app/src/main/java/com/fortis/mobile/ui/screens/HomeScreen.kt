package com.fortis.mobile.ui.screens

import android.view.autofill.AutofillManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.fortis.mobile.service.EventsService
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.FortisCaption
import com.fortis.mobile.ui.components.FortisCard
import com.fortis.mobile.ui.components.FrostChip
import com.fortis.mobile.ui.components.QuickAction
import com.fortis.mobile.ui.components.StatusDot
import com.fortis.mobile.ui.components.StatusRow
import com.fortis.mobile.ui.fileSender
import com.fortis.mobile.ui.laptopAction
import com.fortis.mobile.ui.sendClipboardToLaptop
import com.fortis.mobile.ui.theme.Frost
import com.fortis.mobile.ui.theme.FrostMono
import com.fortis.mobile.ui.theme.FrostSans
import com.fortis.mobile.vault.VaultRepository
import com.fortis.mobile.vault.VaultSession

@Composable
fun HomeScreen(
    viewModel: FortisViewModel,
    onOpenRemoteInput: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val device by viewModel.activeDevice.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val online by EventsService.online.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val vaultUnlocked by VaultSession.unlocked.collectAsState()
    val sendFiles = fileSender()

    // Re-read things the user changes in system settings when they come back to the app
    var resumed by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumed++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val autofillOn = remember(resumed) {
        context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
    }
    val sudoOn = remember(resumed) { EventsService.enabled(context) }
    val vaultState = remember(resumed, vaultUnlocked) { VaultRepository.get(context).state() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ─── The laptop ─────────────────────────────────────────────
        val d = device
        if (d == null) {
            NoLaptopCard(onOpenSettings)
        } else {
            val isOnline = d.uuid in online
            FortisCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(isOnline)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontFamily = FrostSans, fontSize = 22.sp, color = Frost.text, maxLines = 1)
                        Text(
                            (if (isOnline) "Connected · " else "Not connected · ") + d.ipAddress,
                            fontFamily = FrostMono, fontSize = 12.sp, color = Frost.muted, maxLines = 1,
                        )
                    }
                    if (devices.size > 1) {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            FrostChip("Switch", on = false) { open = true }
                            DropdownMenu(open, { open = false }, containerColor = Frost.sheet) {
                                devices.forEach { other ->
                                    DropdownMenuItem(
                                        text = { Text(other.name, fontFamily = FrostSans, color = if (other.uuid == d.uuid) Frost.accent else Frost.text) },
                                        onClick = { open = false; viewModel.selectDevice(other) },
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth()) {
                    Stat("CPU", telemetry?.cpu, Modifier.weight(1f))
                    Stat("Memory", telemetry?.mem?.substringBefore(" /")?.let { "$it MB" }, Modifier.weight(1f))
                    Stat("Temp", telemetry?.temp, Modifier.weight(1f))
                }
            }
        }

        // ─── Security status ────────────────────────────────────────
        FortisCard {
            FortisCaption("Security", Modifier.padding(bottom = 4.dp))
            val sudoConnected = sudoOn && online.isNotEmpty()
            StatusRow(
                "Fingerprint sudo",
                when { !sudoOn -> "Off"; sudoConnected -> "Ready"; else -> "Not connected" },
                if (!sudoOn) null else sudoConnected,
                onClick = onOpenSettings,
            )
            StatusRow("Autofill", if (autofillOn) "On" else "Off", autofillOn, onClick = onOpenSettings)
            StatusRow(
                "Vault",
                when (vaultState) {
                    VaultRepository.State.Unlocked -> "Unlocked"
                    VaultRepository.State.Locked -> "Locked"
                    VaultRepository.State.NotSetUp -> "Not set up"
                    VaultRepository.State.NoVaultLaptop -> "No vault laptop"
                },
                when (vaultState) {
                    VaultRepository.State.Unlocked, VaultRepository.State.Locked -> true
                    else -> null
                },
                onClick = onOpenVault,
            )
        }

        // ─── Quick actions ──────────────────────────────────────────
        FortisCaption("Quick actions", Modifier.padding(start = 4.dp))
        val hasLaptop = d != null
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            QuickAction(Icons.Default.Lock, "Lock", enabled = hasLaptop) {
                scope.laptopAction(context, "lock_screen", "Laptop locked")
            }
            QuickAction(Icons.Default.ContentPaste, "Clipboard", enabled = hasLaptop) {
                scope.sendClipboardToLaptop(context)
            }
            QuickAction(Icons.Default.UploadFile, "Send file", enabled = hasLaptop, onClick = sendFiles)
            QuickAction(Icons.Default.Mouse, "Remote", enabled = hasLaptop, onClick = onOpenRemoteInput)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Stat(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        FortisCaption(label)
        Spacer(Modifier.height(2.dp))
        Text(value ?: "--", fontFamily = FrostMono, fontSize = 16.sp, color = Frost.text, maxLines = 1)
    }
}
