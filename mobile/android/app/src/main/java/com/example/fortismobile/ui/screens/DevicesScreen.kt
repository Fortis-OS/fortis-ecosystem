package com.example.fortismobile.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.fortismobile.ui.FortisViewModel
import com.example.fortismobile.ui.components.*
import com.example.fortismobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DevicesScreen(viewModel: FortisViewModel) {
    val devices by viewModel.devices.collectAsState()
    var showAddDevice by remember { mutableStateOf(false) }

    if (showAddDevice) {
        AddDeviceScreen(viewModel = viewModel, onBack = { showAddDevice = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(FortisSpacing.screenPadding)
    ) {
        Text(
            "DEVICES",
            style = MaterialTheme.typography.headlineLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = RoundedCornerShape(FortisSpacing.cardCorner),
                    onClick = { viewModel.selectDevice(device) }
                ) {
                    Column(
                        modifier = Modifier
                            .padding(FortisSpacing.cardPadding)
                            .fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        if (device.isOnline) StatusGreen else StatusRed,
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                device.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "${device.osType} - ${device.ipAddress}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        FortisActionButton(
            text = "+ ADD_DEVICE",
            onClick = { showAddDevice = true }
        )
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(FortisSpacing.screenPadding)
    ) {
        Text(
            "ADD_DEVICE",
            style = MaterialTheme.typography.headlineLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        val textFieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Friendly Name", fontFamily = FontFamily.Monospace) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            colors = textFieldColors
        )
        OutlinedTextField(
            value = ipAddress,
            onValueChange = { ipAddress = it },
            label = { Text("Tailscale IP Address", fontFamily = FontFamily.Monospace) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            colors = textFieldColors
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Agent API Key", fontFamily = FontFamily.Monospace) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            colors = textFieldColors
        )
        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it },
            label = { Text("6-Digit Pairing Code (Optional)", fontFamily = FontFamily.Monospace) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            colors = textFieldColors
        )

        FortisActionButton(
            text = if (isTestingConnection) "CONNECTING..." else "SAVE",
            isPrimary = true,
            enabled = !isTestingConnection,
            isLoading = isTestingConnection,
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
            },
            modifier = Modifier.padding(bottom = FortisSpacing.buttonGap)
        )

        FortisActionButton(
            text = "CANCEL",
            onClick = { onBack() }
        )
    }
}
