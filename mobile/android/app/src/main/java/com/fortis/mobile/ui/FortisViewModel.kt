package com.fortis.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.fortis.mobile.data.Device
import com.fortis.mobile.data.DeviceRepository
import com.fortis.mobile.domain.ActiveDeviceProvider
import com.fortis.mobile.keys.DeviceKeys
import kotlinx.coroutines.flow.StateFlow

class FortisViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DeviceRepository.get(application)

    /** Paired laptops. */
    val devices: StateFlow<List<Device>> = repository.devices
    val activeDevice: StateFlow<Device?> = ActiveDeviceProvider.activeDevice

    fun selectDevice(device: Device) = ActiveDeviceProvider.setActiveDevice(device)

    fun clearActiveDevice() = ActiveDeviceProvider.setActiveDevice(null)

    fun savePaired(device: Device) = repository.save(device)

    fun makeVaultLaptop(device: Device) = repository.save(device.copy(isVaultLaptop = true))

    fun updateCapabilities(device: Device, capabilitiesJson: String) {
        val d = device.copy(capabilitiesJson = capabilitiesJson, lastSeen = System.currentTimeMillis())
        repository.save(d)
        if (activeDevice.value?.uuid == d.uuid) ActiveDeviceProvider.setActiveDevice(d)
    }

    /** Forget a laptop here and delete its keys. The laptop should also run `fortis devices revoke`. */
    fun removeDevice(uuid: String) {
        repository.remove(uuid)
        DeviceKeys.delete(uuid)
        if (activeDevice.value?.uuid == uuid) clearActiveDevice()
    }
}
