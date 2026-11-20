package com.fortis.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fortis.mobile.data.Device
import com.fortis.mobile.pairing.Pairing
import com.fortis.mobile.pairing.PairingInfo
import com.fortis.mobile.pairing.PairingResult
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.*
import com.fortis.mobile.ui.theme.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Laptops: paired laptops (tap to make one active), the vault laptop, forget, and the
 * sudo-approval card. [onPair] opens the pairing screen.
 */
@Composable
fun LaptopsSection(viewModel: FortisViewModel, onPair: () -> Unit) {
    val devices by viewModel.devices.collectAsState()
    val active by viewModel.activeDevice.collectAsState()
    val online by com.fortis.mobile.service.EventsService.online.collectAsState()
    var toRemove by remember { mutableStateOf<Device?>(null) }

    toRemove?.let { d ->
        FortisConfirmDialog(
            title = "Forget ${d.name}?",
            message = "This phone deletes its keys for ${d.name}. Also revoke it on the laptop:\n\nfortis devices revoke ${d.deviceId.take(8)}\nsudo fortis pam-enroll",
            onConfirm = { viewModel.removeDevice(d.uuid); toRemove = null },
            onDismiss = { toRemove = null },
        )
    }

    Column(Modifier.fillMaxWidth()) {
        if (devices.isEmpty()) {
            FortisCard {
                Text("No laptop paired yet. On the laptop run:", fontFamily = FrostSans, color = Frost.muted)
                Text("fortis pair", fontFamily = FrostMono, color = Frost.text, modifier = Modifier.padding(vertical = 8.dp))
                Text("then tap Pair laptop and scan the QR code.", fontFamily = FrostSans, color = Frost.muted)
            }
            Spacer(Modifier.height(12.dp))
        }
        devices.forEach { device ->
            val isActive = device.uuid == active?.uuid
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .frostGlass(soft = !isActive)
                    .then(if (isActive) Modifier.border(1.dp, Frost.accentSelectBorder, RoundedCornerShape(FortisSpacing.cardCorner)) else Modifier)
                    .pressable { viewModel.selectDevice(device) }
                    .padding(FortisSpacing.cardPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(device.uuid in online)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(device.name, fontFamily = FrostSans, fontSize = 18.sp, color = Frost.text)
                        if (device.isVaultLaptop) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.Lock, contentDescription = "Vault laptop", tint = Frost.accent, modifier = Modifier.size(15.dp))
                        }
                    }
                    Text("${device.ipAddress}:${device.port}", fontFamily = FrostMono, fontSize = 12.sp, color = Frost.muted)
                    Text(
                        listOfNotNull(
                            if (isActive) "Active" else "Tap to make active",
                            if (device.isVaultLaptop) "vault laptop" else null,
                        ).joinToString(" · "),
                        fontFamily = FrostSans, fontSize = 12.sp, color = if (isActive) Frost.accent else Frost.subtle,
                    )
                    if (!device.isVaultLaptop) {
                        Text(
                            "Use for vault sync",
                            fontFamily = FrostSans, fontSize = 12.sp, color = Frost.accent,
                            modifier = Modifier.padding(top = 6.dp).pressable { viewModel.makeVaultLaptop(device) },
                        )
                    }
                }
                IconButton(onClick = { toRemove = device }) {
                    Icon(Icons.Default.Delete, contentDescription = "Forget", tint = Frost.muted)
                }
            }
        }
        FortisActionButton(text = "+ Pair laptop", isPrimary = true, onClick = onPair)
        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SudoSettingsCard()
        }
    }
}

@Composable
fun PairScreen(viewModel: FortisViewModel, firstLaptop: Boolean, onDone: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8023") }
    var code by remember { mutableStateOf("") }
    var expectedFp by remember { mutableStateOf<String?>(null) }
    var vaultLaptop by remember { mutableStateOf(firstLaptop) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<PairingResult?>(null) }
    val scope = rememberCoroutineScope()

    val doPair: (PairingInfo) -> Unit = { info ->
        busy = true
        error = null
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) {
                    val res = Pairing.pair(info, vaultLaptop)
                    val caps = runCatching { Pairing.capabilities(res.device) }.getOrDefault("{}")
                    res.copy(device = res.device.copy(capabilitiesJson = caps))
                }
                viewModel.savePaired(r.device)
                result = r
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { scan ->
        val text = scan.contents ?: return@rememberLauncherForActivityResult
        val info = Pairing.parseUri(text)
        if (info == null) {
            error = "That isn't a Fortis pairing code."
        } else {
            host = info.host; port = info.port.toString(); code = info.code; expectedFp = info.agentFingerprint
            doPair(info)
        }
    }

    result?.let { r -> PairedScreen(r, onDone = { viewModel.selectDevice(r.device); onDone() }); return }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState())) {
        FrostTopBar("Pair laptop", onBack = onDone)
        Text("On the laptop run:", fontFamily = FrostSans, color = Frost.muted)
        Text("fortis pair", fontFamily = FrostMono, fontSize = 16.sp, color = Frost.text, modifier = Modifier.padding(vertical = 6.dp))
        Text("Scan its QR code, or type the code and the laptop's Tailscale address.",
            fontFamily = FrostSans, color = Frost.muted, modifier = Modifier.padding(bottom = 20.dp))
        FortisActionButton(text = "Scan QR code", isPrimary = true, enabled = !busy, onClick = {
            scanner.launch(ScanOptions().setPrompt("Scan the code from `fortis pair`").setBeepEnabled(false).setOrientationLocked(false))
        })
        FortisCaption("or enter it", Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp))
        FrostField(host, { host = it.trim() }, "Laptop Tailscale IP (100.x.y.z)", mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.padding(bottom = 8.dp))
        FrostField(code, { code = it }, "Pairing code (XXXX-XXXX-XX)", mono = true, modifier = Modifier.padding(bottom = 8.dp))
        FrostField(port, { port = it.filter(Char::isDigit).take(5) }, "Port", mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.padding(bottom = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Checkbox(
                checked = vaultLaptop, onCheckedChange = { vaultLaptop = it },
                colors = CheckboxDefaults.colors(checkedColor = Frost.accent, uncheckedColor = Frost.muted, checkmarkColor = Frost.textOnAccent),
            )
            Text("Sync my password vault with this laptop", fontFamily = FrostSans, color = Frost.text)
        }
        error?.let { ErrorLine(it) }
        FortisActionButton(text = if (busy) "Pairing…" else "Pair", isPrimary = true, isLoading = busy, enabled = !busy, onClick = {
            val p = port.toIntOrNull() ?: 8023
            when {
                !Pairing.isTailnetIp(host) -> error = "Use the laptop's Tailscale address (100.64.x.x – 100.127.x.x)."
                code.isBlank() -> error = "Enter the pairing code."
                else -> doPair(PairingInfo(host, p, code.trim(), expectedFp))
            }
        }, modifier = Modifier.padding(top = 8.dp, bottom = FortisSpacing.buttonGap))
        FortisActionButton(text = "Cancel", onClick = onDone)
    }
}

@Composable
private fun PairedScreen(r: PairingResult, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Icon(Icons.Default.CheckCircle, null, tint = Frost.ok, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("Paired", fontFamily = FrostSans, fontSize = 28.sp, color = Frost.text)
        }
        Text("Check these match what the laptop printed. If anything differs, forget this laptop and pair again.",
            fontFamily = FrostSans, color = Frost.muted, modifier = Modifier.padding(bottom = 16.dp))
        FortisCard(Modifier.fillMaxWidth()) {
            FpRow("Laptop", r.device.name)
            FpRow("Agent key", r.agentFp)
            FpRow("Request key", r.requestKeyFp)
            FpRow("Approval key", r.approvalKeyFp)
        }
        Spacer(Modifier.height(16.dp))
        Text("To approve sudo from this phone, run on the laptop:", fontFamily = FrostSans, color = Frost.muted)
        Text("sudo fortis pam-enroll", fontFamily = FrostMono, color = Frost.text, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.weight(1f))
        FortisActionButton(text = "Done", isPrimary = true, onClick = onDone)
    }
}

@Composable
private fun FpRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        FortisCaption(label, Modifier.width(110.dp).padding(top = 2.dp))
        Text(value, fontFamily = FrostMono, fontSize = 14.sp, color = Frost.text)
    }
}
