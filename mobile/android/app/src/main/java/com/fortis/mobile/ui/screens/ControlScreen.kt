package com.fortis.mobile.ui.screens

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
import com.fortis.mobile.ensureTailscaleActive
import com.fortis.mobile.formatMicroseconds
import com.fortis.mobile.network.FortisApiClient
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.*
import com.fortis.mobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Composable
fun ControlScreen(
    viewModel: FortisViewModel,
    onOpenRemoteInput: () -> Unit = {}
) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()

    val ip = activeDevice?.ipAddress ?: ""
    val apiKey = activeDevice?.apiKey ?: ""
    val coroutineScope = rememberCoroutineScope()

    var showShutdownDialog by remember { mutableStateOf(false) }

    // Media state
    var mediaTitle by remember { mutableStateOf("") }
    var mediaArtist by remember { mutableStateOf("") }
    var mediaAlbum by remember { mutableStateOf("") }
    var mediaSource by remember { mutableStateOf("") }
    var mediaStatus by remember { mutableStateOf("") }
    var mediaPosition by remember { mutableStateOf(0f) }
    var mediaDuration by remember { mutableStateOf(0f) }

    // Shutdown dialog
    if (showShutdownDialog) {
        FortisConfirmDialog(
            title = "Confirm Shutdown",
            message = "Are you sure you want to shut down the laptop?",
            onConfirm = {
                showShutdownDialog = false
                if (ip.isEmpty() || apiKey.isEmpty()) {
                    Toast.makeText(context, "Set IP/Key in Workspace!", Toast.LENGTH_SHORT).show()
                    return@FortisConfirmDialog
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
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Shutting Down", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {}
                }
            },
            onDismiss = { showShutdownDialog = false }
        )
    }

    // Media polling
    LaunchedEffect(Unit) {
        while (true) {
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
                } catch (e: Exception) {}
            }
            delay(5000)
        }
    }

    // Position ticker
    LaunchedEffect(mediaStatus) {
        if (mediaStatus == "Playing") {
            while (true) {
                delay(1000)
                if (mediaPosition < mediaDuration) {
                    mediaPosition += 1000000f
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
            } catch (e: Exception) {}
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
        // ─── Power Controls ──────────────────────────────────────
        FortisSection(title = "POWER_CONTROLS") {
            FortisActionButton(
                text = "LOCK_LAPTOP",
                onClick = { sendAction("lock_screen", "Locked Laptop!") },
                modifier = Modifier.padding(bottom = FortisSpacing.buttonGap)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FortisCompactActionButton(
                    text = "SLEEP",
                    onClick = { sendAction("suspend", "Sleeping Laptop") },
                    modifier = Modifier.weight(1f)
                )
                FortisCompactActionButton(
                    text = "SHUTDOWN",
                    onClick = { showShutdownDialog = true },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))

        // ─── Media Controls ──────────────────────────────────────
        FortisSection(title = "MEDIA_CONTROLS") {
            // Now Playing Card
            if (mediaTitle.isNotEmpty()) {
                FortisCard(modifier = Modifier.padding(bottom = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "NOW_PLAYING",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (mediaSource.isNotEmpty()) {
                            Text(
                                mediaSource.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        mediaTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        mediaArtist,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (mediaDuration > 0f) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { mediaPosition / mediaDuration },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outline
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                formatMicroseconds(mediaPosition),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                formatMicroseconds(mediaDuration),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Transport Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FortisCompactActionButton(
                    text = "PREV",
                    onClick = { sendAction("media_prev", "") },
                    modifier = Modifier.weight(1f)
                )
                FortisCompactActionButton(
                    text = "PLAY/PAUSE",
                    onClick = { sendAction("media_play_pause", "") },
                    modifier = Modifier.weight(1f)
                )
                FortisCompactActionButton(
                    text = "NEXT",
                    onClick = { sendAction("media_next", "") },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FortisCompactActionButton(
                    text = "VOL -",
                    onClick = { sendActionWithVolumeToast("volume_down") },
                    modifier = Modifier.weight(1f)
                )
                FortisCompactActionButton(
                    text = "VOL +",
                    onClick = { sendActionWithVolumeToast("volume_up") },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))

        // ─── Remote Input ────────────────────────────────────────
        FortisSection(title = "REMOTE_INPUT") {
            FortisActionButton(
                text = "REMOTE_INPUT_CONTROL",
                onClick = onOpenRemoteInput
            )
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))
    }
}
