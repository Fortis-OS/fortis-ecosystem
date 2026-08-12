package com.example.fortismobile.network

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.P)
class BluetoothHidManager(private val context: Context) {
    private val TAG = "BluetoothHidManager"
    
    private val bluetoothManager: BluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var hidDevice: BluetoothHidDevice? = null
    var hostDevice: BluetoothDevice? = null
    
    var onConnectionStateChanged: ((Int) -> Unit)? = null

    // Composite HID Descriptor for Keyboard and Mouse
    private val descriptor = byteArrayOf(
        // Keyboard Report (ID 1)
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(), // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        0x85.toByte(), 0x01.toByte(), // Report ID (1)
        0x05.toByte(), 0x07.toByte(), // Usage Page (Key Codes)
        0x19.toByte(), 0xE0.toByte(), // Usage Minimum (224)
        0x29.toByte(), 0xE7.toByte(), // Usage Maximum (231)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(), // Report Size (1)
        0x95.toByte(), 0x08.toByte(), // Report Count (8)
        0x81.toByte(), 0x02.toByte(), // Input (Data, Variable, Absolute) - Modifiers
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x81.toByte(), 0x01.toByte(), // Input (Constant) - Reserved byte
        0x95.toByte(), 0x06.toByte(), // Report Count (6)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x25.toByte(), 0x65.toByte(), // Logical Maximum (101)
        0x05.toByte(), 0x07.toByte(), // Usage Page (Key Codes)
        0x19.toByte(), 0x00.toByte(), // Usage Minimum (0)
        0x29.toByte(), 0x65.toByte(), // Usage Maximum (101)
        0x81.toByte(), 0x00.toByte(), // Input (Data, Array) - Key arrays (6 bytes)
        0xC0.toByte(),                // End Collection
        
        // Mouse Report (ID 2)
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x02.toByte(), // Usage (Mouse)
        0xA1.toByte(), 0x01.toByte(), // Collection (Application)
        0x85.toByte(), 0x02.toByte(), // Report ID (2)
        0x09.toByte(), 0x01.toByte(), // Usage (Pointer)
        0xA1.toByte(), 0x00.toByte(), // Collection (Physical)
        0x05.toByte(), 0x09.toByte(), // Usage Page (Button)
        0x19.toByte(), 0x01.toByte(), // Usage Minimum (Button 1)
        0x29.toByte(), 0x03.toByte(), // Usage Maximum (Button 3)
        0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(), // Report Size (1)
        0x95.toByte(), 0x03.toByte(), // Report Count (3)
        0x81.toByte(), 0x02.toByte(), // Input (Data, Variable, Absolute) - 3 buttons
        0x75.toByte(), 0x05.toByte(), // Report Size (5)
        0x95.toByte(), 0x01.toByte(), // Report Count (1)
        0x81.toByte(), 0x01.toByte(), // Input (Constant) - 5 bit padding
        0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
        0x09.toByte(), 0x30.toByte(), // Usage (X)
        0x09.toByte(), 0x31.toByte(), // Usage (Y)
        0x09.toByte(), 0x38.toByte(), // Usage (Wheel)
        0x15.toByte(), 0x81.toByte(), // Logical Minimum (-127)
        0x25.toByte(), 0x7F.toByte(), // Logical Maximum (127)
        0x75.toByte(), 0x08.toByte(), // Report Size (8)
        0x95.toByte(), 0x03.toByte(), // Report Count (3)
        0x81.toByte(), 0x06.toByte(), // Input (Data, Variable, Relative) - X,Y,Wheel
        0xC0.toByte(),                // End Collection
        0xC0.toByte()                 // End Collection
    )

    private val sdpSettings = BluetoothHidDeviceAppSdpSettings(
        "Fortis HID",
        "Fortis Ecosystem",
        "Android Bluetooth HID Emulation",
        BluetoothHidDevice.SUBCLASS1_COMBO,
        descriptor
    )

    private val qosSettings = BluetoothHidDeviceAppQosSettings(
        BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
        800,
        9,
        0,
        11250,
        BluetoothHidDeviceAppQosSettings.MAX
    )

    private val hidDeviceCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged: registered=$registered, device=$pluggedDevice")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            Log.d(TAG, "onConnectionStateChanged: $device, state=$state")
            hostDevice = if (state == BluetoothProfile.STATE_CONNECTED) device else null
            onConnectionStateChanged?.invoke(state)
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            try {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    hidDevice = proxy as BluetoothHidDevice
                    registerApp()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in onServiceConnected: ${e.message}")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
            }
        }
    }

    fun initialize() {
        if (bluetoothAdapter?.isEnabled == true) {
            bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        } else {
            Log.e(TAG, "Bluetooth is disabled")
        }
    }

    fun uninitialize() {
        hidDevice?.unregisterApp()
        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
    }

    private fun registerApp() {
        hidDevice?.registerApp(
            sdpSettings,
            null,
            qosSettings,
            Executors.newSingleThreadExecutor(),
            hidDeviceCallback
        )
        try {
            val connected = hidDevice?.connectedDevices
            if (!connected.isNullOrEmpty()) {
                hostDevice = connected[0]
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sendMouseMove(dx: Byte, dy: Byte) {
        if (hostDevice == null) return
        val report = byteArrayOf(0x00, dx, dy, 0x00)
        hidDevice?.sendReport(hostDevice, 2, report)
    }

    fun sendMouseClick(buttonMask: Byte) {
        if (hostDevice == null) return
        val reportDown = byteArrayOf(buttonMask, 0x00, 0x00, 0x00)
        val reportUp = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        hidDevice?.sendReport(hostDevice, 2, reportDown)
        hidDevice?.sendReport(hostDevice, 2, reportUp)
    }

    fun sendMouseDown(buttonMask: Byte) {
        if (hostDevice == null) return
        val reportDown = byteArrayOf(buttonMask, 0x00, 0x00, 0x00)
        hidDevice?.sendReport(hostDevice, 2, reportDown)
    }

    fun sendMouseUp() {
        if (hostDevice == null) return
        val reportUp = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        hidDevice?.sendReport(hostDevice, 2, reportUp)
    }

    fun sendScroll(dy: Byte) {
        if (hostDevice == null) return
        val report = byteArrayOf(0x00, 0x00, 0x00, dy)
        hidDevice?.sendReport(hostDevice, 2, report)
    }
}
