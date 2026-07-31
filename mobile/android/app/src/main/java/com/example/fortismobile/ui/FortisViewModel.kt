package com.example.fortismobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.fortismobile.data.Device
import com.example.fortismobile.data.DeviceRepository
import com.example.fortismobile.domain.ActiveDeviceProvider
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class FortisViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DeviceRepository(application)
    
    val devices: StateFlow<List<Device>> = repository.devices
    val activeDevice: StateFlow<Device?> = ActiveDeviceProvider.activeDevice

    fun selectDevice(device: Device) {
        ActiveDeviceProvider.setActiveDevice(device)
    }

    fun clearActiveDevice() {
        ActiveDeviceProvider.setActiveDevice(null)
    }

    fun addDevice(name: String, ipAddress: String, apiKey: String, osType: String, hostname: String, capabilitiesJson: String) {
        val device = Device(
            uuid = UUID.randomUUID().toString(),
            name = name,
            ipAddress = ipAddress,
            apiKey = apiKey,
            osType = osType,
            hostname = hostname,
            lastSeen = System.currentTimeMillis(),
            capabilitiesJson = capabilitiesJson
        )
        repository.saveDevice(device)
    }

    fun removeDevice(uuid: String) {
        repository.removeDevice(uuid)
        if (activeDevice.value?.uuid == uuid) {
            clearActiveDevice()
        }
    }
}
