package com.fortis.mobile.webcam

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fortis.mobile.R
import com.fortis.mobile.data.Device
import com.fortis.mobile.domain.CapabilityManager
import com.fortis.mobile.network.FortisApiClient
import com.fortis.mobile.ui.components.FortisSpacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import com.fortis.mobile.ui.theme.FrostMono

private const val MAX_STREAM_MS = 60 * 60 * 1000L
private const val NOTIF_ID = 60
private const val CHANNEL = "fortis.webcam"

/** Phone camera → laptop `/dev/video10` (security §7.6). Streams only while this screen is open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebcamScreen(device: Device, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val caps by com.fortis.mobile.domain.rememberFreshCapabilities(device)
    val available = CapabilityManager.hasCapability(device.copy(capabilitiesJson = caps), "webcam")
    var hasPerm by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPerm = it }
    var front by remember { mutableStateOf(true) }
    var server by remember { mutableStateOf<WebcamServer?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val previewView = remember { PreviewView(ctx) }
    val analyzer = remember { Executors.newSingleThreadExecutor() }

    fun stop(reason: String?) {
        val s = server ?: return
        server = null
        s.stop()
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        CoroutineScope(Dispatchers.IO).launch { runCatching { post(device, "/api/v1/webcam/stop", JSONObject()) } }
        status = reason
    }

    fun start() {
        val addr = WebcamServer.tailnetAddress() ?: run { status = "Tailscale is not connected on this phone"; return }
        val s = WebcamServer(device.ipAddress)
        try { s.start(addr) } catch (e: Exception) { status = "Couldn't open the stream: ${e.message}"; return }
        server = s; status = "Starting…"
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching { post(device, "/api/v1/webcam/start", JSONObject().put("port", s.port).put("token", s.token)) }
                    .fold({ null }, { it.message })
            }
            if (server !== s) return@launch
            if (err != null) { stop(err); return@launch }
            startedAt = System.currentTimeMillis()
            status = null
            showNotification(ctx, device.name)
        }
    }

    // Camera: preview + JPEG frames for the server (~15 fps, 1280x720).
    DisposableEffect(hasPerm, front) {
        if (!hasPerm) return@DisposableEffect onDispose { }
        val providerF = ProcessCameraProvider.getInstance(ctx)
        var lastFrame = 0L
        providerF.addListener({
            val provider = providerF.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analyzer) { img ->
                img.use {
                    val s = server
                    val t = System.currentTimeMillis()
                    if (s == null || !s.clientConnected || t - lastFrame < 66) return@use
                    lastFrame = t
                    var bmp = it.toBitmap()
                    val rot = it.imageInfo.rotationDegrees
                    if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                    val out = ByteArrayOutputStream(128 * 1024)
                    bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
                    s.offer(out.toByteArray())
                }
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { status = "Camera unavailable: ${it.message}" }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose { runCatching { providerF.get().unbindAll() } }
    }

    // Leaving the screen (or the app going to the background) stops the stream.
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) stop("Stopped — app left the screen")
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); stop(null); analyzer.shutdown() }
    }
    LaunchedEffect(server) {
        while (server != null) {
            now = System.currentTimeMillis()
            if (startedAt > 0 && now - startedAt > MAX_STREAM_MS) stop("Stopped after 1 hour")
            delay(1000)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("WEBCAM — ${device.name}", fontFamily = FrostMono, style = MaterialTheme.typography.titleMedium) },
            navigationIcon = { TextButton(onClick = onBack) { Text("< BACK", fontFamily = FrostMono) } },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(FortisSpacing.screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                !available -> Text("Webcam is off on this laptop. Set webcam.enabled = true in agent.toml and load v4l2loopback (see docs/installation).",
                    fontFamily = FrostMono, style = MaterialTheme.typography.bodyMedium)
                !hasPerm -> Button(onClick = { askPerm.launch(Manifest.permission.CAMERA) }) { Text("ALLOW CAMERA", fontFamily = FrostMono) }
                else -> {
                    AndroidView({ previewView }, Modifier.fillMaxWidth().weight(1f))
                    val s = server
                    Text(
                        when {
                            s == null -> status ?: "Not streaming"
                            startedAt == 0L -> status ?: "Starting…"
                            else -> {
                                val left = ((MAX_STREAM_MS - (now - startedAt)) / 60000).coerceAtLeast(0)
                                (if (s.clientConnected) "● Streaming to ${device.name}" else "Waiting for ${device.name}…") + " · stops in ${left} min"
                            }
                        },
                        fontFamily = FrostMono, style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (s == null) Button(onClick = { start() }, Modifier.weight(1f)) { Text("START", fontFamily = FrostMono) }
                        else OutlinedButton(onClick = { stop("Stopped") }, Modifier.weight(1f)) { Text("STOP", fontFamily = FrostMono) }
                        OutlinedButton(onClick = { front = !front }, Modifier.weight(1f)) { Text(if (front) "BACK CAM" else "FRONT CAM", fontFamily = FrostMono) }
                    }
                }
            }
        }
    }
}

private fun post(device: Device, path: String, body: JSONObject) {
    val req = FortisApiClient.buildRequest(path, device) { post(body.toString().toRequestBody("application/json".toMediaType())) }
    FortisApiClient.getClient().newCall(req).execute().use { r ->
        if (!r.isSuccessful) {
            val msg = runCatching { JSONObject(r.body!!.string()).getJSONObject("error").getString("message") }.getOrNull()
            throw Exception(msg ?: "HTTP ${r.code}")
        }
    }
}

private fun showNotification(ctx: Context, laptop: String) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Webcam", NotificationManager.IMPORTANCE_LOW))
    val n = NotificationCompat.Builder(ctx, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("Camera streaming to $laptop")
        .setContentText("Leave the webcam screen to stop")
        .setOngoing(true).setSilent(true).build()
    runCatching { nm.notify(NOTIF_ID, n) }
}
