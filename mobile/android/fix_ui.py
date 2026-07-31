import re

with open('/home/ganeshak11/AndroidStudioProjects/FortisMobile/app/src/main/java/com/example/fortismobile/MainActivity.kt', 'r') as f:
    content = f.read()

# Add MobileStatusCard definition
mobile_status_card_code = """
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
"""

if "fun MobileStatusCard()" not in content:
    content += "\n" + mobile_status_card_code

# Remove duplicate FORCE_LAPTOP_BACKUP in DashboardScreen
# It's currently duplicated. Let's just fix the whole SYSTEM_OPERATIONS section in DashboardScreen.
content = re.sub(
    r'Text\("SYSTEM_OPERATIONS".*?Spacer\(modifier = Modifier\.height\(32\.dp\)\)', 
    r'''Text("SYSTEM_OPERATIONS", style = MaterialTheme.typography.headlineSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(bottom = 8.dp))
        
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
            onClick = {
                if (!ensureTailscaleActive(context)) return@OutlinedButton
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
        
        Spacer(modifier = Modifier.height(32.dp))''', 
    content, flags=re.DOTALL)


# Now fix ActionsScreen.
content = re.sub(
    r'Text\("DESKTOP_CONTROLS".*?Spacer\(modifier = Modifier\.height\(32\.dp\)\)',
    r'''if (com.example.fortismobile.domain.CapabilityManager.hasCapability(activeDevice, "action")) {
            Text("DESKTOP_CONTROLS", style = MaterialTheme.typography.headlineSmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(bottom = 8.dp))
            
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { sendAction("lock", "Locked screen!") },
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
                    border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black)
                ) {
                    Text("LOCK_SCREEN", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
                
                OutlinedButton(
                    onClick = { sendAction("suspend", "Suspended system!") },
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = androidx.compose.ui.graphics.Color.Black),
                    border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color.Black)
                ) {
                    Text("SUSPEND", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }''',
    content, flags=re.DOTALL)

with open('/home/ganeshak11/AndroidStudioProjects/FortisMobile/app/src/main/java/com/example/fortismobile/MainActivity.kt', 'w') as f:
    f.write(content)

