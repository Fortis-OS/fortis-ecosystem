package com.example.fortismobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.app.DownloadManager
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fortismobile.network.FortisApiClient
import com.example.fortismobile.ui.FortisViewModel
import com.example.fortismobile.ui.RemoteInputScreen
import com.example.fortismobile.domain.ActiveDeviceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import androidx.compose.foundation.background

import com.example.fortismobile.ui.theme.FortisMobileTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FortisMobileTheme {
                FortisMobileApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FortisMobileApp(viewModel: FortisViewModel = viewModel()) {
    val activeDevice by viewModel.activeDevice.collectAsState()
    
    // If no active device is selected, show the Devices Screen without the bottom bar
    if (activeDevice == null) {
        DevicesScreen(viewModel = viewModel)
        return
    }

    var selectedTab by remember { mutableStateOf(0) } // Default to Dashboard when device is selected
    val tabs = listOf("Dashboard", "Actions", "Files", "Workspace", "Vault", "Authenticator")
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    var showTerminal by remember { mutableStateOf(false) }
    var showWebcam by remember { mutableStateOf(false) }
    var showRemoteInput by remember { mutableStateOf(false) }

    if (showTerminal) {
        TerminalScreen(viewModel = viewModel, onBack = { showTerminal = false })
        return
    }
    
    if (showWebcam) {
        WebcamScreen(viewModel = viewModel, onBack = { showWebcam = false })
        return
    }

    if (showRemoteInput && activeDevice != null) {
        RemoteInputScreen(device = activeDevice!!, onNavigateBack = { showRemoteInput = false })
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(16.dp))
                Text(
                    "FORTIS_MOBILE", 
                    modifier = Modifier.padding(16.dp),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    fontSize = 20.sp
                )
                HorizontalDivider()
                tabs.forEachIndexed { index, title ->
                    NavigationDrawerItem(
                        label = { Text(title, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            coroutineScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("FORTIS_MOBILE", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = androidx.compose.ui.graphics.Color.Black) },
                    navigationIcon = {
                        IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = androidx.compose.ui.graphics.Color.White)
                )
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                when (selectedTab) {
                    0 -> DashboardScreen(
                            viewModel = viewModel, 
                            onOpenTerminal = { showTerminal = true },
                            onOpenRemoteInput = { showRemoteInput = true }
                         )
                    1 -> ActionsScreen(viewModel = viewModel)
                    2 -> FileManagerScreen(viewModel = viewModel)
                    3 -> WorkspaceScreen(viewModel = viewModel, onOpenWebcam = { showWebcam = true })
                    4 -> com.example.fortismobile.encrypt.VaultScreen(viewModel = viewModel)
                    5 -> com.example.fortismobile.encrypt.AuthenticatorScreen(viewModel = viewModel)
                }
            }
        }
    }
}

fun ensureTailscaleActive(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    var vpnActive = false
    val activeNetwork = connectivityManager.activeNetwork
    if (activeNetwork != null) {
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
            vpnActive = true
        }
    }
    if (!vpnActive) {
        val tsIntent = context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
        if (tsIntent != null) {
            context.startActivity(tsIntent)
            Toast.makeText(context, "Please turn on Tailscale!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Tailscale app not found", Toast.LENGTH_SHORT).show()
        }
        return false
    }
    return true
}

@Composable
fun TerminalCard(title: String, value: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Card(
        modifier = modifier.padding(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.Gray)
            Text(value, style = MaterialTheme.typography.bodyLarge, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = androidx.compose.ui.graphics.Color.Black)
        }
    }
}

@Composable
fun DevicesScreen(viewModel: FortisViewModel) {
    val devices by viewModel.devices.collectAsState()
    var showAddDevice by remember { mutableStateOf(false) }

    if (showAddDevice) {
        AddDeviceScreen(viewModel = viewModel, onBack = { showAddDevice = false })
        return
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("DEVICES", style = MaterialTheme.typography.headlineLarge, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(bottom = 32.dp))
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices) { device ->
                androidx.compose.material3.Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    onClick = { viewModel.selectDevice(device) }
                ) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(10.dp).background(
                                if (device.isOnline) androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Red, 
                                shape = androidx.compose.foundation.shape.CircleShape
                            ))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(device.name, style = MaterialTheme.typography.titleLarge, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${device.osType} - ${device.ipAddress}", style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.Gray, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }
        
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
            onClick = { showAddDevice = true }
        ) {
            Text("+ ADD_DEVICE", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}

@Composable
fun AddDeviceScreen(viewModel: FortisViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var ipAddress by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var isTestingConnection by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("ADD_DEVICE", style = MaterialTheme.typography.headlineLarge, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(bottom = 32.dp))
        
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Friendly Name", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = ipAddress,
            onValueChange = { ipAddress = it },
            label = { Text("Tailscale IP Address", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Agent API Key", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it },
            label = { Text("6-Digit Pairing Code (Optional)", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
        )

        OutlinedButton(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = androidx.compose.ui.graphics.Color.Black, contentColor = androidx.compose.ui.graphics.Color.White),
            enabled = !isTestingConnection,
            onClick = { 
                if (name.isNotEmpty() && ipAddress.isNotEmpty() && apiKey.isNotEmpty()) {
                    isTestingConnection = true
                    coroutineScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val client = com.example.fortismobile.network.FortisApiClient.getClient()
                                val request = okhttp3.Request.Builder()
                                    .url("http://${ipAddress}:8023/api/v1/capabilities")
                                    .header("Authorization", "Bearer $apiKey")
                                    .build()
                                val response = client.newCall(request).execute()
                                if (response.isSuccessful || response.code == 404) {
                                    val caps = if (response.isSuccessful) response.body?.string() ?: "{}" else """{"terminal": true, "telemetry": true, "media": true, "backup": true, "clipboard": true, "files": true, "webcam": true, "action": true}"""
                                    
                                    if (pairingCode.isNotEmpty()) {
                                        com.example.fortismobile.encrypt.performPairingRequest(ipAddress, pairingCode)
                                    }
                                    
                                    withContext(Dispatchers.Main) {
                                        viewModel.addDevice(name, ipAddress, apiKey, "Linux", name, caps)
                                        isTestingConnection = false
                                        onBack()
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Connection failed: ${response.code}", Toast.LENGTH_SHORT).show()
                                        isTestingConnection = false
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                isTestingConnection = false
                            }
                        }
                    }
                } else {
                    Toast.makeText(context, "Please fill all fields", Toast.LENGTH_SHORT).show()
                }
            }
        ) {
            Text(if (isTestingConnection) "CONNECTING..." else "SAVE", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
            onClick = { onBack() }
        ) {
            Text("CANCEL", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}

@Composable
fun DashboardScreen(viewModel: FortisViewModel, onOpenTerminal: () -> Unit, onOpenRemoteInput: () -> Unit = {}) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()
    
    val ip = activeDevice?.ipAddress ?: ""
    val apiKey = activeDevice?.apiKey ?: ""
    
    var uptime by remember { mutableStateOf("--") }
    var cpu by remember { mutableStateOf("--") }
    var mem by remember { mutableStateOf("--") }
    var swap by remember { mutableStateOf("--") }
    var storage by remember { mutableStateOf("--") }
    var temp by remember { mutableStateOf("--") }
    
    var showLogsDialog by remember { mutableStateOf(false) }
    var logsContent by remember { mutableStateOf("Loading logs...") }
    var showBackupDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Termux Permission Granted! Try again.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Termux RUN_COMMAND permission is required!", Toast.LENGTH_LONG).show()
        }
    }

    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            title = { Text("Live Backup Logs", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
            text = {
                val scrollState = rememberScrollState()
                Text(
                    text = logsContent,
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .fillMaxHeight(0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            },
            confirmButton = {
                TextButton(onClick = { showLogsDialog = false }) { Text("Close", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
            },
            containerColor = androidx.compose.ui.graphics.Color.White,
            textContentColor = androidx.compose.ui.graphics.Color.Black
        )
    }

    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("Confirm Backup", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
            text = { Text("Are you sure you want to force a laptop backup?", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
            confirmButton = {
                TextButton(onClick = { 
                    showBackupDialog = false
                    if (ip.isEmpty() || apiKey.isEmpty()) {
                        Toast.makeText(context, "Set IP/Key in Workspace!", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        permissionLauncher.launch("com.termux.permission.RUN_COMMAND")
                        return@TextButton
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
                }) { Text("Yes", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
            },
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) { Text("No", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
            },
            containerColor = androidx.compose.ui.graphics.Color.White,
            textContentColor = androidx.compose.ui.graphics.Color.Black
        )
    }

    // Auto-refresh telemetry
    LaunchedEffect(Unit) {
        if (ip.isNotEmpty() && apiKey.isNotEmpty()) {
            while(true) {
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

    val scrollState = rememberScrollState()

    Column(modifier = Modifier.padding(16.dp).verticalScroll(scrollState).fillMaxSize()) {
        Text("DASHBOARD", style = MaterialTheme.typography.headlineLarge, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("SYSTEM_TELEMETRY", style = MaterialTheme.typography.headlineSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(bottom = 16.dp))
        
        Row(modifier = Modifier.fillMaxWidth()) {
            TerminalCard(title = "UPTIME", value = uptime, modifier = Modifier.weight(1f))
            TerminalCard(title = "TEMP", value = temp, modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            TerminalCard(title = "CPU", value = cpu, modifier = Modifier.weight(1f))
            TerminalCard(title = "STORAGE", value = storage, modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            TerminalCard(title = "RAM", value = mem, modifier = Modifier.weight(1f))
            TerminalCard(title = "SWAP", value = swap, modifier = Modifier.weight(1f))
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text("SYSTEM_OPERATIONS", style = MaterialTheme.typography.headlineSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(bottom = 8.dp))
        
        if (com.example.fortismobile.domain.CapabilityManager.hasCapability(activeDevice, "terminal")) {
            OutlinedButton(
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
                onClick = { onOpenTerminal() }
            ) {
                Text("LAUNCH_TERMINAL", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }

        OutlinedButton(
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
            onClick = { onOpenRemoteInput() }
        ) {
            Text("REMOTE_INPUT_CONTROL", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }

        OutlinedButton(
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
            onClick = {
                if (!ensureTailscaleActive(context)) return@OutlinedButton
                if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch("com.termux.permission.RUN_COMMAND")
                    return@OutlinedButton
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
        ) {
            Text("START_PHONE_SSH", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }

        if (com.example.fortismobile.domain.CapabilityManager.hasCapability(activeDevice, "backup")) {
            OutlinedButton(
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
                onClick = { showBackupDialog = true }
            ) {
                Text("FORCE_LAPTOP_BACKUP", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }

            OutlinedButton(
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
                onClick = {
                    if (!ensureTailscaleActive(context)) return@OutlinedButton
                    showLogsDialog = true
                    logsContent = "Loading logs..."
                    coroutineScope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val client = com.example.fortismobile.network.FortisApiClient.getClient()
                                val request = com.example.fortismobile.network.FortisApiClient.buildRequest("/api/v1/backup/logs") { get() }
                                client.newCall(request).execute().body?.string() ?: "No logs returned"
                            }
                            logsContent = result
                        } catch (e: Exception) {
                            logsContent = "Failed to fetch logs: ${e.message}"
                        }
                    }
                }
            ) {
                Text("VIEW_LIVE_LOGS", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        Text("MOBILE_DEVICE_STATUS", style = MaterialTheme.typography.headlineSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(bottom = 8.dp))
        MobileStatusCard()
        Spacer(modifier = Modifier.height(32.dp))
    }
}

fun formatMicroseconds(micros: Float): String {
    val totalSeconds = (micros / 1000000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}

@Composable
fun ActionsScreen(viewModel: FortisViewModel) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()
    
    val ip = activeDevice?.ipAddress ?: ""
    val apiKey = activeDevice?.apiKey ?: ""
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager

    var showShutdownDialog by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var currentFileIndex by remember { mutableStateOf(0) }
    var totalFiles by remember { mutableStateOf(0) }
    var currentFileName by remember { mutableStateOf("") }
    var uploadProgress by remember { mutableStateOf(0f) }
    
    var mediaTitle by remember { mutableStateOf("") }
    var mediaArtist by remember { mutableStateOf("") }
    var mediaAlbum by remember { mutableStateOf("") }
    var mediaSource by remember { mutableStateOf("") }
    var mediaStatus by remember { mutableStateOf("") }
    var mediaPosition by remember { mutableStateOf(0f) }
    var mediaDuration by remember { mutableStateOf(0f) }

    if (showShutdownDialog) {
        AlertDialog(
            onDismissRequest = { showShutdownDialog = false },
            title = { Text("Confirm Shutdown", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
            text = { Text("Are you sure you want to shut down the laptop?", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
            confirmButton = {
                TextButton(onClick = { 
                    showShutdownDialog = false
                    if (ip.isEmpty() || apiKey.isEmpty()) {
                        Toast.makeText(context, "Set IP/Key in Workspace!", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    coroutineScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val client = FortisApiClient.getClient()
                                val json = """{"action":"shutdown"}"""
                                val body = json.toRequestBody("application/json".toMediaType())
                                val request = FortisApiClient.buildRequest("/api/v1/action") { post(body) }
                                client.newCall(request).execute().close()
                            }
                            withContext(Dispatchers.Main) { Toast.makeText(context, "Shutting Down", Toast.LENGTH_SHORT).show() }
                        } catch (e: Exception) {}
                    }
                }) { Text("Yes", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
            },
            dismissButton = {
                TextButton(onClick = { showShutdownDialog = false }) { Text("No", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
            },
            containerColor = androidx.compose.ui.graphics.Color.White,
            textContentColor = androidx.compose.ui.graphics.Color.Black
        )
    }

    LaunchedEffect(Unit) {
        while(true) {
            if (ip.isNotEmpty() && apiKey.isNotEmpty() && ensureTailscaleActive(context)) {
                try {
                    val client = FortisApiClient.getClient()
                    val request = FortisApiClient.buildRequest("/api/v1/media")
                    withContext(Dispatchers.IO) {
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            response.body?.string()?.let { jsonString ->
                                val json = JSONObject(jsonString)
                                withContext(Dispatchers.Main) {
                                    mediaTitle = json.optString("title", "")
                                    mediaArtist = json.optString("artist", "")
                                    mediaAlbum = json.optString("album", "")
                                    mediaSource = json.optString("player", "")
                                    mediaStatus = json.optString("status", "")
                                    mediaPosition = json.optString("position", "0").toFloatOrNull() ?: 0f
                                    mediaDuration = json.optString("duration", "0").toFloatOrNull() ?: 0f
                                }
                            }
                        }
                        response.close()
                    }
                } catch (e: Exception) {
                    // Ignore silently
                }
            }
            delay(5000)
        }
    }

    LaunchedEffect(mediaStatus) {
        if (mediaStatus == "Playing") {
            while(true) {
                delay(1000)
                if (mediaPosition < mediaDuration) {
                    mediaPosition += 1000000f
                }
            }
        }
    }

    if (isUploading) {
        AlertDialog(
            onDismissRequest = { /* Prevent dismissing */ },
            title = { Text("Uploading Files...") },
            text = {
                Column {
                    Text("File $currentFileIndex of $totalFiles: $currentFileName", maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { uploadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${(uploadProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {}
        )
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            if (ip.isEmpty() || apiKey.isEmpty()) return@rememberLauncherForActivityResult
            if (!ensureTailscaleActive(context)) return@rememberLauncherForActivityResult
            totalFiles = uris.size
            currentFileIndex = 0
            isUploading = true
            
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val client = FortisApiClient.getClient()
                        var successCount = 0
                        var failCount = 0
                        
                        for (uri in uris) {
                            try {
                                val fileName = getFileName(context, uri)
                                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                                
                                var fileSize = 0L
                                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        fileSize = cursor.getLong(0)
                                    }
                                }
                                
                                withContext(Dispatchers.Main) {
                                    currentFileIndex++
                                    currentFileName = fileName
                                    uploadProgress = 0f
                                }
                                
                                if (inputStream != null) {
                                    val streamBody = object : okhttp3.RequestBody() {
                                        override fun contentType() = "application/octet-stream".toMediaType()
                                        override fun writeTo(sink: okio.BufferedSink) {
                                            inputStream.use { stream ->
                                                val buffer = ByteArray(8192)
                                                var bytesRead: Int
                                                var totalBytesRead = 0L
                                                while (stream.read(buffer).also { bytesRead = it } != -1) {
                                                    sink.write(buffer, 0, bytesRead)
                                                    totalBytesRead += bytesRead
                                                    val progress = if (fileSize > 0) totalBytesRead.toFloat() / fileSize else 0f
                                                    // Throttle updates to prevent UI lag
                                                    if (progress - uploadProgress > 0.01f || progress == 1f) {
                                                        uploadProgress = progress
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    
                                    val requestBody = MultipartBody.Builder()
                                        .setType(MultipartBody.FORM)
                                        .addFormDataPart("file", fileName, streamBody)
                                        .build()
                                        
                                    val request = FortisApiClient.buildRequest("/api/v1/files/drop") { post(requestBody) }
                                        
                                    client.newCall(request).execute().close()
                                    successCount++
                                }
                            } catch (e: Exception) {
                                failCount++
                            }
                        }
                        withContext(Dispatchers.Main) {
                            isUploading = false
                            Toast.makeText(context, "Shared $successCount files. Failed $failCount.", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun sendAction(action: String, successMessage: String) {
        if (ip.isEmpty() || apiKey.isEmpty()) {
            Toast.makeText(context, "Set IP and API Key in Settings first!", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ensureTailscaleActive(context)) return
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val client = FortisApiClient.getClient()
                    val json = """{"action":"$action"}"""
                    val body = json.toRequestBody("application/json".toMediaType())
                    val request = FortisApiClient.buildRequest("/api/v1/action") { post(body) }
                    val response = client.newCall(request).execute()
                    var volumeMsg = ""
                    if (response.isSuccessful) {
                        response.body?.string()?.let { jsonString ->
                            val json = JSONObject(jsonString)
                            val vol = json.optString("volume", "")
                            if (vol.isNotEmpty()) {
                                volumeMsg = "Volume: $vol"
                            }
                        }
                    }
                    response.close()
                }
                if (successMessage.isNotEmpty()) {
                    Toast.makeText(context, successMessage, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    // Quick helper to show volume toast inside the coroutine launched block without UI blocking
    fun sendActionWithVolumeToast(action: String) {
        if (ip.isEmpty() || apiKey.isEmpty()) return
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val client = FortisApiClient.getClient()
                    val json = """{"action":"$action"}"""
                    val body = json.toRequestBody("application/json".toMediaType())
                    val request = FortisApiClient.buildRequest("/api/v1/action") { post(body) }
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        response.body?.string()?.let { jsonString ->
                            val jsonObj = JSONObject(jsonString)
                            val vol = jsonObj.optString("volume", "")
                            if (vol.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Volume: $vol", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    response.close()
                }
            } catch (e: Exception) {
            }
        }
    }

    val scrollState = rememberScrollState()
    Column(modifier = Modifier.padding(16.dp).verticalScroll(scrollState)) {
        if (mediaTitle.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("NOW_PLAYING", style = MaterialTheme.typography.labelMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        if (mediaSource.isNotEmpty()) {
                            Text(mediaSource.uppercase(), style = MaterialTheme.typography.labelSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(mediaTitle, style = MaterialTheme.typography.titleLarge, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Text(mediaArtist, style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    
                    if (mediaDuration > 0f) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { mediaPosition / mediaDuration },
                            modifier = Modifier.fillMaxWidth(),
                            color = androidx.compose.ui.graphics.Color.Black,
                            trackColor = androidx.compose.ui.graphics.Color.LightGray
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatMicroseconds(mediaPosition), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                            Text(formatMicroseconds(mediaDuration), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        Text("QUICK_ACTIONS", style = MaterialTheme.typography.headlineSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
            onClick = { sendAction("lock_screen", "Locked Laptop!") }
        ) {
            Text("LOCK_LAPTOP_SCREEN", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }

        Text("MEDIA_CONTROLS", style = MaterialTheme.typography.titleMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
                onClick = { sendAction("media_prev", "") }
            ) { Text("PREV", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
            
            OutlinedButton(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
                onClick = { sendAction("media_play_pause", "") }
            ) { Text("PLAY_PAUSE", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
            
            OutlinedButton(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
                onClick = { sendAction("media_next", "") }
            ) { Text("NEXT", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
                onClick = { sendActionWithVolumeToast("volume_down") }
            ) { Text("VOL_DOWN", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
            
            OutlinedButton(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
                onClick = { sendActionWithVolumeToast("volume_up") }
            ) { Text("VOL_UP", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
        }
        
        Text("POWER_CONTROLS", style = MaterialTheme.typography.titleMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
                onClick = { sendAction("suspend", "Sleeping Laptop") }
            ) { Text("SLEEP", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
            
            OutlinedButton(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
                onClick = { showShutdownDialog = true }
            ) { Text("SHUTDOWN", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            onClick = {
                if (activeDevice == null) {
                    Toast.makeText(context, "Select a device first!", Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }
                if (!ensureTailscaleActive(context)) return@OutlinedButton
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
            }
        ) {
            Text("Send Clipboard to Laptop")
        }

        Button(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            onClick = {
                filePickerLauncher.launch("*/*")
            }
        ) {
            Text("Send File to Laptop")
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "uploaded_file"
}

@Composable
fun WorkspaceScreen(viewModel: FortisViewModel, onOpenWebcam: () -> Unit) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Termux Permission Granted! Try again.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Termux RUN_COMMAND permission is required!", Toast.LENGTH_LONG).show()
        }
    }

    val scrollState = rememberScrollState()
    Column(modifier = Modifier.padding(16.dp).verticalScroll(scrollState).fillMaxSize()) {
        Text("WORKSPACE", style = MaterialTheme.typography.headlineLarge, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        
        Spacer(modifier = Modifier.height(24.dp))
        Text("CURRENT_NODE", style = MaterialTheme.typography.titleMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        Spacer(modifier = Modifier.height(8.dp))
        
        androidx.compose.material3.Card(
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Text(activeDevice?.name ?: "Unknown", style = MaterialTheme.typography.titleLarge, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = androidx.compose.ui.graphics.Color.Black)
                Spacer(modifier = Modifier.height(4.dp))
                Text(activeDevice?.ipAddress ?: "No IP", style = MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.Gray, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedButton(
            onClick = { viewModel.clearActiveDevice() },
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = androidx.compose.ui.graphics.Color.Black, contentColor = androidx.compose.ui.graphics.Color.White)
        ) {
            Text("SWITCH_DEVICE", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        Text("SYSTEM_SERVICES", style = MaterialTheme.typography.titleMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedButton(
            onClick = {
                val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black)
        ) {
            Text("NOTIFICATION_SYNC", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedButton(
            onClick = { onOpenWebcam() },
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black)
        ) {
            Text("REMOTE_WEBCAM", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}

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
        modifier = Modifier.padding(32.dp).fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
    ) {
        Text("TRUE_TERMINAL", style = MaterialTheme.typography.headlineLarge, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "This will seamlessly switch to the Termux app and automatically initiate a secure, interactive VT100 SSH session to your laptop.",
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(32.dp))
        
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
            onClick = { onBack() }
        ) {
            Text("< GO_BACK", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                containerColor = androidx.compose.ui.graphics.Color.Black,
                contentColor = androidx.compose.ui.graphics.Color.White
            ),
            onClick = {
                if (ip.isEmpty()) {
                    Toast.makeText(context, "Set IP in Workspace first", Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }
                if (!ensureTailscaleActive(context)) return@OutlinedButton
                if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch("com.termux.permission.RUN_COMMAND")
                    return@OutlinedButton
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
        ) {
            Text("LAUNCH_SSH_SESSION", style = MaterialTheme.typography.titleMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}

data class RemoteFile(val name: String, val isDir: Boolean, val size: Long)

@Composable
fun FileManagerScreen(viewModel: FortisViewModel) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()
    val ip = activeDevice?.ipAddress ?: ""
    val apiKey = activeDevice?.apiKey ?: ""
    val coroutineScope = rememberCoroutineScope()

    var currentPath by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(emptyList<RemoteFile>()) }
    var isLoading by remember { mutableStateOf(false) }

    fun fetchFiles(path: String) {
        if (ip.isEmpty() || apiKey.isEmpty()) return
        isLoading = true
        coroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val client = FortisApiClient.getClient()
                    val json = JSONObject().put("path", path).toString()
                    val body = json.toRequestBody("application/json".toMediaType())
                    val request = FortisApiClient.buildRequest("/api/v1/files/list") { post(body) }
                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val jsonObj = JSONObject(responseBody)
                        val returnedPath = jsonObj.optString("path", "")
                        val filesArray = jsonObj.optJSONArray("files")
                        val newList = mutableListOf<RemoteFile>()
                        if (filesArray != null) {
                            for (i in 0 until filesArray.length()) {
                                val fObj = filesArray.getJSONObject(i)
                                newList.add(RemoteFile(fObj.getString("name"), fObj.getBoolean("is_dir"), fObj.getLong("size")))
                            }
                        }
                        newList.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                        
                        withContext(Dispatchers.Main) {
                            currentPath = returnedPath
                            files = newList
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Error: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    response.close()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Network Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (currentPath.isEmpty()) {
            fetchFiles("")
        }
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("Laptop Files", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(
                onClick = {
                    val parent = currentPath.substringBeforeLast("/", "")
                    fetchFiles(if (parent.isEmpty()) "/" else parent)
                },
                enabled = currentPath.isNotEmpty() && currentPath != "/" && !isLoading
            ) {
                Text("Up")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(currentPath, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(files) { file ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            if (file.isDir) {
                                val nextPath = if (currentPath.endsWith("/")) currentPath + file.name else currentPath + "/" + file.name
                                fetchFiles(nextPath)
                            } else {
                                val filePath = if (currentPath.endsWith("/")) currentPath + file.name else currentPath + "/" + file.name
                                val downloadUrl = "http://$ip:8023/api/v1/files/download?path=${android.net.Uri.encode(filePath)}"
                                
                                val request = DownloadManager.Request(android.net.Uri.parse(downloadUrl))
                                    .setTitle(file.name)
                                    .setDescription("Downloading from Laptop")
                                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file.name)
                                request.addRequestHeader("Authorization", "Bearer $apiKey")
                                
                                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                dm.enqueue(request)
                                Toast.makeText(context, "Downloading ${file.name}...", Toast.LENGTH_SHORT).show()
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(if (file.isDir) "📁" else "📄", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(file.name, style = MaterialTheme.typography.bodyLarge)
                            if (!file.isDir) {
                                Text("${file.size / 1024} KB", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WebcamScreen(viewModel: FortisViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val activeDevice by viewModel.activeDevice.collectAsState()
    val ip = activeDevice?.ipAddress ?: ""
    val apiKey = activeDevice?.apiKey ?: ""
    
    val mjpegServer = remember { MjpegServer(8080) }
    var isStreaming by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    var hasCameraPermission by remember { mutableStateOf(
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
    ) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mjpegServer.stop()
            if (isStreaming) {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val client = FortisApiClient.getClient()
                        val request = FortisApiClient.buildRequest("/api/v1/webcam/stop") { post("{}".toRequestBody("application/json".toMediaType())) }
                        client.newCall(request).execute().close()
                    } catch (e: Exception) {}
                }
            }
        }
    }

    var lensFacing by remember { mutableStateOf(androidx.camera.core.CameraSelector.LENS_FACING_BACK) }
    val previewView = remember { androidx.camera.view.PreviewView(context) }

    LaunchedEffect(lensFacing, hasCameraPermission) {
        if (!hasCameraPermission) return@LaunchedEffect
        val cameraProviderFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            
            val imageAnalysis = androidx.camera.core.ImageAnalysis.Builder()
                .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(androidx.core.content.ContextCompat.getMainExecutor(context)) { imageProxy ->
                        mjpegServer.updateFrame(imageProxy)
                    }
                }

            val cameraSelector = androidx.camera.core.CameraSelector.Builder().requireLensFacing(lensFacing).build()
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            } catch(e: Exception) {
                e.printStackTrace()
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text("Wireless PC Webcam", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        if (hasCameraPermission) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )
                
                // Switch Camera Button overlay
                androidx.compose.material3.IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK) {
                            androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                        } else {
                            androidx.camera.core.CameraSelector.LENS_FACING_BACK
                        }
                    },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(16.dp)
                ) {
                    Text("🔄", style = MaterialTheme.typography.headlineMedium)
                }
            }
        } else {
            Text("Camera permission is required.")
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = {
                if (ip.isEmpty() || apiKey.isEmpty()) {
                    Toast.makeText(context, "Set IP & API Key in Settings", Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }

                if (isStreaming) {
                    mjpegServer.stop()
                    isStreaming = false
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val client = FortisApiClient.getClient()
                            val request = FortisApiClient.buildRequest("/api/v1/webcam/stop") { post("{}".toRequestBody("application/json".toMediaType())) }
                            client.newCall(request).execute().close()
                            withContext(Dispatchers.Main) { Toast.makeText(context, "Webcam Stopped", Toast.LENGTH_SHORT).show() }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to stop", Toast.LENGTH_SHORT).show() }
                        }
                    }
                } else {
                    mjpegServer.start()
                    isStreaming = true
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val client = FortisApiClient.getClient()
                            val request = FortisApiClient.buildRequest("/api/v1/webcam/start") { post("{}".toRequestBody("application/json".toMediaType())) }
                            client.newCall(request).execute().close()
                            withContext(Dispatchers.Main) { Toast.makeText(context, "Webcam Started!", Toast.LENGTH_SHORT).show() }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { Toast.makeText(context, "Failed to start", Toast.LENGTH_SHORT).show() }
                            isStreaming = false
                            mjpegServer.stop()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            enabled = hasCameraPermission,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                containerColor = if (isStreaming) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White,
                contentColor = if (isStreaming) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black)
        ) {
            Text(if (isStreaming) "STOP_WEBCAM" else "START_WEBCAM", style = MaterialTheme.typography.titleMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
            onClick = { onBack() }
        ) {
            Text("< GO_BACK", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}


@Composable
fun MobileStatusCard() {
    val context = LocalContext.current
    var tsActive by remember { mutableStateOf(false) }
    var sshActive by remember { mutableStateOf(false) }
    var storageGb by remember { mutableStateOf("--") }
    var batteryLevel by remember { mutableStateOf("--") }

    LaunchedEffect(Unit) {
        while(true) {
            tsActive = ensureTailscaleActive(context)
            
            // Check SSH 8022
            sshActive = try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", 8022), 500)
                    socket.close()
                    true
                }
            } catch (e: Exception) { false }
            
            // Storage
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val bytesAvailable = stat.blockSizeLong * stat.availableBlocksLong
            storageGb = (bytesAvailable / (1024 * 1024 * 1024)).toString() + " GB"
            
            // Battery
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            batteryLevel = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).toString() + "%"
            
            kotlinx.coroutines.delay(5000)
        }
    }

    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Tailscale:", style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text(if (tsActive) "RUNNING" else "STOPPED", style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = if (tsActive) androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Red)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("SSHD (8022):", style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text(if (sshActive) "RUNNING" else "STOPPED", style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = if (sshActive) androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Red)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Battery:", style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text(batteryLevel, style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Free Storage:", style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Text(storageGb, style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}
