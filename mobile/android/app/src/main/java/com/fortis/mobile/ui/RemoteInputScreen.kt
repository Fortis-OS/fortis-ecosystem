package com.fortis.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import android.os.Build
import com.fortis.mobile.network.BluetoothHidManager
import com.fortis.mobile.data.Device
import com.fortis.mobile.network.InputSocketClient
import com.fortis.mobile.ui.components.FortisSpacing
import com.fortis.mobile.ui.components.FortisCompactActionButton
import com.fortis.mobile.ui.components.FrostChips
import com.fortis.mobile.ui.components.FrostTopBar
import com.fortis.mobile.ui.components.StatusDot
import com.fortis.mobile.ui.components.frostFieldColors
import com.fortis.mobile.ui.components.frostGlass
import kotlinx.coroutines.delay
import androidx.fragment.app.FragmentActivity
import com.fortis.mobile.domain.CapabilityManager
import com.fortis.mobile.network.InputSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.fortis.mobile.ui.theme.FrostMono
import com.fortis.mobile.ui.theme.FrostSans
import com.fortis.mobile.ui.theme.Frost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteInputScreen(
    device: Device,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var socketClient by remember { mutableStateOf<InputSocketClient?>(null) }
    var textValue by remember { mutableStateOf(TextFieldValue("")) }
    var scrollAccX by remember { mutableStateOf(0f) }
    var scrollAccY by remember { mutableStateOf(0f) }

    var bluetoothManager by remember { mutableStateOf<BluetoothHidManager?>(null) }
    // Network mode needs a fingerprint-confirmed session (security §5.6).
    // Capabilities change when agent.toml changes; re-read them every time this screen opens.
    val caps by com.fortis.mobile.domain.rememberFreshCapabilities(device)
    val networkAvailable = CapabilityManager.hasCapability(device.copy(capabilitiesJson = caps), "input")
    var useBluetooth by remember { mutableStateOf(!networkAvailable) }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var netStatus by remember { mutableStateOf<String?>(null) }
    var starting by remember { mutableStateOf(false) }
    LaunchedEffect(networkAvailable) { if (networkAvailable && sessionId == null) useBluetooth = false }
    val scope = rememberCoroutineScope()

    fun stopNetwork() {
        socketClient?.disconnect(); socketClient = null
        sessionId?.let { id -> CoroutineScope(Dispatchers.IO).launch { InputSession.end(device, id) } }
        sessionId = null
    }

    fun startNetwork() {
        val activity = context as? FragmentActivity ?: return
        starting = true; netStatus = null
        scope.launch {
            try {
                val id = InputSession.start(activity, device)
                sessionId = id
                socketClient = InputSocketClient(device, id) { err ->
                    scope.launch { if (sessionId == id) { socketClient = null; sessionId = null; netStatus = err ?: "Session ended" } }
                }.also { it.connect() }
            } catch (e: Exception) {
                netStatus = e.message
            } finally { starting = false }
        }
    }

    DisposableEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val bm = BluetoothHidManager(context)
                bm.initialize()
                bluetoothManager = bm
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    bluetoothManager?.uninitialize()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun handleMove(dx: Float, dy: Float) {
        if (useBluetooth) {
            val cx = dx.coerceIn(-127f, 127f).toInt().toByte()
            val cy = dy.coerceIn(-127f, 127f).toInt().toByte()
            bluetoothManager?.sendMouseMove(cx, cy)
        } else {
            socketClient?.sendMouseMove(dx * 2.5f, dy * 2.5f)
        }
    }

    fun handleScroll(dy: Float) {
        if (useBluetooth) {
            val ticks = dy.coerceIn(-127f, 127f).toInt().toByte()
            bluetoothManager?.sendScroll(ticks)
        } else {
            socketClient?.sendScroll(0f, dy)
        }
    }

    fun handleScrollX(dx: Float) {
        if (!useBluetooth) {
            socketClient?.sendScroll(dx, 0f)
        }
    }

    fun handleClick(button: Int) {
        if (useBluetooth) {
            val mask = if (button == 1) 0x01.toByte() else 0x02.toByte()
            bluetoothManager?.sendMouseClick(mask)
        } else {
            socketClient?.sendClick(button)
        }
    }

    fun handleMouseDown(button: Int) {
        if (useBluetooth) {
            val mask = if (button == 1) 0x01.toByte() else 0x02.toByte()
            bluetoothManager?.sendMouseDown(mask)
        } else {
            socketClient?.sendMouseDown(button)
        }
    }

    fun handleMouseUp(button: Int) {
        if (useBluetooth) {
            bluetoothManager?.sendMouseUp()
        } else {
            socketClient?.sendMouseUp(button)
        }
    }


    DisposableEffect(device) {
        onDispose { stopNetwork() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        FrostTopBar("Remote input", onBack = onNavigateBack)

        // Mode: network (fingerprint session) or Bluetooth HID
        FrostChips(
            listOf(false to "Network", true to "Bluetooth"),
            useBluetooth,
            { bt -> if (bt || networkAvailable) { if (bt) stopNetwork(); useBluetooth = bt; netStatus = null } },
            Modifier.padding(bottom = 0.dp),
        )
        if (!networkAvailable) {
            Text("Network mode is off on this laptop (input.enabled in agent.toml, xdotool/ydotool).",
                fontFamily = FrostSans, fontSize = 12.sp, color = Frost.muted)
        }
        if (!useBluetooth) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (sessionId != null) true else null)
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        sessionId != null -> "Controlling ${device.name} · ends after 2 min idle, 30 min max"
                        starting -> "Waiting for your fingerprint…"
                        else -> netStatus ?: "Not connected"
                    },
                    modifier = Modifier.weight(1f),
                    fontFamily = FrostSans,
                    fontSize = 13.sp,
                    color = if (sessionId != null) Frost.text else Frost.muted
                )
                Spacer(Modifier.width(10.dp))
                if (sessionId == null) {
                    FortisCompactActionButton(text = "Start", onClick = { startNetwork() }, isPrimary = true, enabled = !starting)
                } else {
                    FortisCompactActionButton(text = "Stop", onClick = { stopNetwork(); netStatus = "Stopped" })
                }
            }
        }

        // Keyboard: typed text goes straight to the laptop
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = textValue,
                onValueChange = {
                    // Detect added text and send it over socket
                    if (it.text.length > textValue.text.length) {
                        val added = it.text.substring(textValue.text.length)
                        socketClient?.sendText(added)
                    } else if (it.text.length < textValue.text.length) {
                        socketClient?.sendKey("BackSpace")
                    }
                    textValue = it
                },
                placeholder = { Text("Type to send text…", fontFamily = FrostSans) },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FrostSans, fontSize = 15.sp, color = Frost.text),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
                colors = frostFieldColors(),
            )
            Spacer(modifier = Modifier.width(8.dp))
            FortisCompactActionButton(
                text = "Enter",
                isPrimary = true,
                onClick = {
                    socketClient?.sendKey("Return")
                    textValue = TextFieldValue("")
                },
            )
        }

        // Trackpad
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .frostGlass(radius = 20.dp, soft = true)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val activePointers = event.changes.filter { it.pressed }
                                if (activePointers.size == 1) {
                                    val change = activePointers.first()
                                    if (change.previousPressed) {
                                        val dx = change.position.x - change.previousPosition.x
                                        val dy = change.position.y - change.previousPosition.y
                                        if (Math.abs(dx) > 0 || Math.abs(dy) > 0) {
                                            handleMove(dx, dy)
                                            change.consume()
                                        }
                                    }
                                } else if (activePointers.size == 2) {
                                    val change = activePointers.first()
                                    if (change.previousPressed) {
                                        val dx = change.position.x - change.previousPosition.x
                                        val dy = change.position.y - change.previousPosition.y
                                        // Invert scroll direction
                                        scrollAccY -= dy
                                        scrollAccX -= dx
                                        val threshold = 40f
                                        if (Math.abs(scrollAccY) >= threshold) {
                                            val ticks = (scrollAccY / threshold).toInt()
                                            handleScroll(ticks.toFloat())
                                            scrollAccY -= ticks * threshold
                                        }
                                        if (Math.abs(scrollAccX) >= threshold) {
                                            val ticks = (scrollAccX / threshold).toInt()
                                            handleScrollX(ticks.toFloat())
                                            scrollAccX -= ticks * threshold
                                        }
                                        activePointers.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { handleClick(1) },
                            onLongPress = { handleClick(3) }
                        )
                    }
        ) {
            Text(
                "One finger: move · Two fingers: scroll\nTap: left click · Long press: right click",
                modifier = Modifier.align(Alignment.Center),
                color = Frost.subtle,
                fontFamily = FrostSans,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }

        // Click pads (press and hold to drag)
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .frostGlass(radius = 16.dp, soft = true)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.any { it.pressed && !it.previousPressed }) {
                                        handleMouseDown(1)
                                    }
                                    if (event.changes.any { !it.pressed && it.previousPressed }) {
                                        handleMouseUp(1)
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Left click", fontFamily = FrostSans, fontSize = 14.sp, color = Frost.text)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .frostGlass(radius = 16.dp, soft = true)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.any { it.pressed && !it.previousPressed }) {
                                        handleMouseDown(3)
                                    }
                                    if (event.changes.any { !it.pressed && it.previousPressed }) {
                                        handleMouseUp(3)
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Right click", fontFamily = FrostSans, fontSize = 14.sp, color = Frost.text)
                }
        }
    }
}
