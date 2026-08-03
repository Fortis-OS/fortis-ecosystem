package com.example.fortismobile.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.fortismobile.MjpegServer
import com.example.fortismobile.network.FortisApiClient
import com.example.fortismobile.ui.FortisViewModel
import com.example.fortismobile.ui.components.*
import com.example.fortismobile.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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

    var hasCameraPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(FortisSpacing.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "WIRELESS_PC_WEBCAM",
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (hasCameraPermission) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )

                // Switch Camera Button overlay
                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_BACK) {
                            androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                        } else {
                            androidx.camera.core.CameraSelector.LENS_FACING_BACK
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Text(
                        "🔄",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Camera permission is required.",
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            enabled = hasCameraPermission,
            shape = RoundedCornerShape(FortisSpacing.buttonCorner),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (isStreaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                contentColor = if (isStreaming) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Text(
                if (isStreaming) "STOP_WEBCAM" else "START_WEBCAM",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        FortisActionButton(
            text = "< GO_BACK",
            onClick = { onBack() }
        )
    }
}
