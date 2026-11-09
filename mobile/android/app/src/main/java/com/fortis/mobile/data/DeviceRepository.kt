package com.fortis.mobile.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceRepository(context: Context) {
    private val prefs = context.getSharedPreferences("FortisDevices", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    init {
        loadDevices()
    }

    private fun loadDevices() {
        val json = prefs.getString("devices_json", "[]")
        val type = object : TypeToken<List<Device>>() {}.type
        val list: List<Device> = try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        _devices.value = list
    }

    fun saveDevice(device: Device) {
        val currentList = _devices.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.uuid == device.uuid }
        if (existingIndex >= 0) {
            currentList[existingIndex] = device
        } else {
            currentList.add(device)
        }
        persistList(currentList)
    }

    fun removeDevice(uuid: String) {
        val currentList = _devices.value.toMutableList()
        currentList.removeAll { it.uuid == uuid }
        persistList(currentList)
    }

    private fun persistList(list: List<Device>) {
        prefs.edit().putString("devices_json", gson.toJson(list)).apply()
        _devices.value = list
    }
}
