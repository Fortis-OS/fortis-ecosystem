package com.fortis.mobile.ui.screens

import androidx.compose.foundation.background
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

/** Paired laptops; pick one to control, or pair a new one. */
@Composable
fun DevicesScreen(viewModel: FortisViewModel) {
    val devices by viewModel.devices.collectAsState()
    var showPair by remember { mutableStateOf(false) }
    var toRemove by remember { mutableStateOf<Device?>(null) }

    if (showPair) {
        PairScreen(viewModel = viewModel, firstLaptop = devices.isEmpty(), onDone = { showPair = false })
        return
    }

    toRemove?.let { d ->
        FortisConfirmDialog(
            title = "Forget ${d.name}?",
            message = "This phone deletes its keys for ${d.name}. Also revoke it on the laptop:\n\nfortis devices revoke ${d.deviceId.take(8)}\nsudo fortis pam-enroll",
            onConfirm = { viewModel.removeDevice(d.uuid); toRemove = null },
            onDismiss = { toRemove = null },
        )
    }

    Column(Modifier.fillMaxSize().padding(FortisSpacing.screenPadding)) {
        Text("LAPTOPS", style = MaterialTheme.typography.headlineLarge, fontFamily = FrostMono,
            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 24.dp))
        if (devices.isEmpty()) {
            Text("No laptop paired yet. On the laptop run:\n\nfortis pair\n\nthen tap PAIR LAPTOP and scan the QR code.",
                fontFamily = FrostMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (devices.isNotEmpty()) SudoSettingsCard()
        LazyColumn(Modifier.weight(1f)) {
            items(devices, key = { it.uuid }) { device ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(FortisSpacing.cardCorner),
                    onClick = { viewModel.selectDevice(device) },
                ) {
                    Row(Modifier.padding(FortisSpacing.cardPadding).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(if (device.isOnline) StatusGreen else StatusRed, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(device.name, style = MaterialTheme.typography.titleLarge, fontFamily = FrostMono,
                                    color = MaterialTheme.colorScheme.onSurface)
                                if (device.isVaultLaptop) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Default.Lock, contentDescription = "Vault laptop", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                }
                            }
                            Text("${device.ipAddress}:${device.port}", style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FrostMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!device.isVaultLaptop) {
                                TextButton(onClick = { viewModel.makeVaultLaptop(device) }, contentPadding = PaddingValues(0.dp)) {
                                    Text("Use for vault sync", fontFamily = FrostMono, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        IconButton(onClick = { toRemove = device }) {
                            Icon(Icons.Default.Delete, contentDescription = "Forget", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        FortisActionButton(text = "+ PAIR LAPTOP", isPrimary = true, onClick = { showPair = true })
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

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = MaterialTheme.colorScheme.outline,
        focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    )
    Column(Modifier.fillMaxSize().padding(FortisSpacing.screenPadding).verticalScroll(rememberScrollState())) {
        Text("PAIR LAPTOP", style = MaterialTheme.typography.headlineLarge, fontFamily = FrostMono,
            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(bottom = 12.dp))
        Text("On the laptop run `fortis pair`. Scan its QR code, or type the code and the laptop's Tailscale address.",
            fontFamily = FrostMono, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 20.dp))
        FortisActionButton(text = "SCAN QR CODE", isPrimary = true, enabled = !busy, onClick = {
            scanner.launch(ScanOptions().setPrompt("Scan the code from `fortis pair`").setBeepEnabled(false).setOrientationLocked(false))
        })
        Text("— or enter it —", fontFamily = FrostMono, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp))
        OutlinedTextField(host, { host = it.trim() }, label = { Text("Laptop Tailscale IP (100.x.y.z)", fontFamily = FrostMono) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), colors = fieldColors,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        OutlinedTextField(code, { code = it }, label = { Text("Pairing code (XXXX-XXXX-XX)", fontFamily = FrostMono) },
            singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port", fontFamily = FrostMono) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Checkbox(checked = vaultLaptop, onCheckedChange = { vaultLaptop = it })
            Text("Sync my password vault with this laptop", fontFamily = FrostMono, color = MaterialTheme.colorScheme.onBackground)
        }
        error?.let { Text(it, color = StatusRed, fontFamily = FrostMono, modifier = Modifier.padding(vertical = 8.dp)) }
        FortisActionButton(text = if (busy) "PAIRING…" else "PAIR", isPrimary = true, isLoading = busy, enabled = !busy, onClick = {
            val p = port.toIntOrNull() ?: 8023
            when {
                !Pairing.isTailnetIp(host) -> error = "Use the laptop's Tailscale address (100.64.x.x – 100.127.x.x)."
                code.isBlank() -> error = "Enter the pairing code."
                else -> doPair(PairingInfo(host, p, code.trim(), expectedFp))
            }
        }, modifier = Modifier.padding(bottom = FortisSpacing.buttonGap))
        FortisActionButton(text = "CANCEL", onClick = onDone)
    }
}

@Composable
private fun PairedScreen(r: PairingResult, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(FortisSpacing.screenPadding)) {
        Text("PAIRED", style = MaterialTheme.typography.headlineLarge, fontFamily = FrostMono,
            color = StatusGreen, modifier = Modifier.padding(bottom = 16.dp))
        Text("Check these match what the laptop printed. If anything differs, forget this laptop and pair again.",
            fontFamily = FrostMono, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp))
        FortisCard(Modifier.fillMaxWidth()) {
            FpRow("Laptop", r.device.name)
            FpRow("Agent key", r.agentFp)
            FpRow("Request key", r.requestKeyFp)
            FpRow("Approval key", r.approvalKeyFp)
        }
        Spacer(Modifier.height(16.dp))
        Text("To approve sudo from this phone, run on the laptop:\nsudo fortis pam-enroll",
            fontFamily = FrostMono, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        FortisActionButton(text = "DONE", isPrimary = true, onClick = onDone)
    }
}

@Composable
private fun FpRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontFamily = FrostMono, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(value, fontFamily = FrostMono, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
}
