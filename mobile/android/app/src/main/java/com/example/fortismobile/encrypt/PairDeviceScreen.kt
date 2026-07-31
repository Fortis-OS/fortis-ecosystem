package com.example.fortismobile.encrypt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairDeviceScreen() {
    var ipAddress by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var isPairing by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Pair New Device",
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        OutlinedTextField(
            value = ipAddress,
            onValueChange = { ipAddress = it },
            label = { Text("Agent IP Address (e.g., 100.x.x.x)", color = Color.Gray) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color.DarkGray,
                focusedContainerColor = Color(0xFF1E1E1E),
                unfocusedContainerColor = Color(0xFF1E1E1E),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it },
            label = { Text("6-Digit Pairing Code", color = Color.Gray) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color.DarkGray,
                focusedContainerColor = Color(0xFF1E1E1E),
                unfocusedContainerColor = Color(0xFF1E1E1E),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )

        if (statusMessage.isNotEmpty()) {
            Text(
                text = statusMessage,
                color = if (statusMessage.contains("Success")) Color(0xFF4CAF50) else Color(0xFFF44336),
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        Button(
            onClick = {
                if (ipAddress.isNotEmpty() && pairingCode.isNotEmpty()) {
                    isPairing = true
                    statusMessage = "Pairing..."
                    coroutineScope.launch {
                        statusMessage = performPairingRequest(ipAddress, pairingCode)
                        isPairing = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
            shape = RoundedCornerShape(12.dp),
            enabled = !isPairing
        ) {
            if (isPairing) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            } else {
                Text("PAIR DEVICE", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

suspend fun performPairingRequest(ip: String, code: String): String {
    return withContext(Dispatchers.IO) {
        try {
            // Generate or fetch keypair
            FortisKeyManager.generateKeyIfNotExists()
            val publicKeyHex = FortisKeyManager.getPublicKeyHex()

            val json = JSONObject()
            json.put("code", code)
            json.put("public_key", publicKeyHex)

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            
            // Note: REST API uses port 8023
            val request = Request.Builder()
                .url("http://$ip:8023/api/v1/pair")
                .post(body)
                .build()

            val client = OkHttpClient()
            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                "Successfully paired!"
            } else {
                "Failed to pair. Code: ${response.code}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
