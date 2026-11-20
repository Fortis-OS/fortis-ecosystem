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
import com.fortis.mobile.formatMicroseconds
import com.fortis.mobile.ui.laptopAction
import com.fortis.mobile.ui.toast
import com.fortis.mobile.ui.FortisViewModel
import com.fortis.mobile.ui.components.*
import com.fortis.mobile.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun ControlScreen(
    viewModel: FortisViewModel,
    onOpenRemoteInput: () -> Unit = {},
    onOpenWebcam: () -> Unit = {}
) {
    val context = LocalContext.current
    val activeDevice by viewModel.activeDevice.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    var showShutdownDialog by remember { mutableStateOf(false) }

    // Media: shared poll from the ViewModel; the position ticks locally between polls
    val media by viewModel.media.collectAsState()
    val mediaTitle = media?.title ?: ""
    val mediaArtist = media?.artist ?: ""
    val mediaSource = media?.player ?: ""
    val mediaDuration = media?.lengthUs?.toFloat() ?: 0f
    var mediaPosition by remember { mutableStateOf(0f) }
    LaunchedEffect(media) {
        mediaPosition = media?.positionUs?.toFloat() ?: 0f
        if (media?.isPlaying == true) {
            while (true) {
                delay(1000)
                if (mediaPosition < mediaDuration) mediaPosition += 1_000_000f
            }
        }
    }

    // Shutdown dialog
    if (showShutdownDialog) {
        FortisConfirmDialog(
            title = "Confirm Shutdown",
            message = "Are you sure you want to shut down the laptop?",
            onConfirm = {
                showShutdownDialog = false
                coroutineScope.laptopAction(context, "shutdown", "Shutting down")
            },
            onDismiss = { showShutdownDialog = false }
        )
    }

    fun sendAction(action: String, successMessage: String) =
        coroutineScope.laptopAction(context, action, successMessage)

    fun sendActionWithVolumeToast(action: String) =
        coroutineScope.laptopAction(context, action) { body ->
            if (body?.has("volume_percent") == true) context.toast("Volume: ${body.optInt("volume_percent")}%")
        }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            
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
                            fontFamily = FrostMono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (mediaSource.isNotEmpty()) {
                            Text(
                                mediaSource.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FrostMono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        mediaTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = FrostMono,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        mediaArtist,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FrostMono,
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
                                fontFamily = FrostMono,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                formatMicroseconds(mediaDuration),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FrostMono,
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
            Spacer(modifier = Modifier.height(8.dp))
            FortisActionButton(
                text = "PHONE_AS_WEBCAM",
                onClick = onOpenWebcam
            )
        }

        Spacer(modifier = Modifier.height(FortisSpacing.sectionGap))
    }
}
