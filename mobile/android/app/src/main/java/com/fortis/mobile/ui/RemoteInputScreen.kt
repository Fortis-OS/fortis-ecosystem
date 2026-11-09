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

import android.os.Build
import com.fortis.mobile.network.BluetoothHidManager
import com.fortis.mobile.data.Device
import com.fortis.mobile.network.AgentClient
import com.fortis.mobile.network.InputSocketClient
import com.fortis.mobile.ui.components.FortisSpacing
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
        try {
            client.connect()
            socketClient = client
        } catch (e: Exception) {
            e.printStackTrace()
        }

        onDispose {
            try {
                client.disconnect()
            } catch (e: Exception) {}
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "REMOTE_INPUT — ${device.name}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text(
                            "< BACK",
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(FortisSpacing.screenPadding),
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
                    label = {
                        Text(
                            "Type here to send text...",
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        socketClient?.sendKey("Return")
                        textValue = TextFieldValue("")
                    },
                    shape = RoundedCornerShape(FortisSpacing.buttonCorner),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Send", fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))


            // Toggle Bluetooth
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Use Bluetooth HID Mode (Zero Latency)",
                    modifier = Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = useBluetooth,
                    onCheckedChange = { useBluetooth = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            Text(
                "TRACKPAD_AREA",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground
            )


            // Trackpad Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        RoundedCornerShape(16.dp)
                    )
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }

            // Dedicated Click Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
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
                    shape = RoundedCornerShape(FortisSpacing.buttonCorner),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text("LEFT_CLICK", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
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
                    shape = RoundedCornerShape(FortisSpacing.buttonCorner),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text("RIGHT_CLICK", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
