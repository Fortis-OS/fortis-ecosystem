package com.example.fortismobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

import android.os.Build
import com.example.fortismobile.network.BluetoothHidManager
import com.example.fortismobile.data.Device
import com.example.fortismobile.network.AgentClient
import com.example.fortismobile.network.InputSocketClient
import kotlinx.coroutines.delay

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
    var useBluetooth by remember { mutableStateOf(false) }

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


    // Connect to websocket when screen opens
    DisposableEffect(device) {
        val client = InputSocketClient(
            client = AgentClient.getClient(device.apiKey),
            hostUrl = "http://${device.ipAddress}:8023" // Assume default port
        )
        client.connect()
        socketClient = client

        onDispose {
            client.disconnect()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote Input - ${device.name}") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Software Keyboard Input
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
                    label = { Text("Type here to send text...") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    socketClient?.sendKey("Return")
                    textValue = TextFieldValue("")
                }) {
                    Text("Send")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            
            // Toggle Bluetooth
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Use Bluetooth HID Mode (Zero Latency)", modifier = Modifier.weight(1f))
                Switch(checked = useBluetooth, onCheckedChange = { useBluetooth = it })
            }
            
            Text("Trackpad Area", style = MaterialTheme.typography.titleMedium)


            // Trackpad Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.DarkGray.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
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
                    "1 Finger: Move Mouse\n2 Fingers: Scroll\nTap: Left Click\nLong Press: Right Click",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.Gray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            // Dedicated Click Buttons
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
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
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Left Click")
                }
                Button(
                    onClick = { },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
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
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Right Click")
                }
            }
        }
    }
}
