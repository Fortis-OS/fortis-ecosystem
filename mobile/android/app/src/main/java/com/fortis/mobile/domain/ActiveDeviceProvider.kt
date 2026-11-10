package com.fortis.mobile.domain

import com.fortis.mobile.data.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ActiveDeviceProvider {
    private val _activeDevice = MutableStateFlow<Device?>(null)
    val activeDevice: StateFlow<Device?> = _activeDevice.asStateFlow()

    fun setActiveDevice(device: Device?) {
        _activeDevice.value = device
    }
    
    fun get(): Device? {
        return _activeDevice.value
    }
}
