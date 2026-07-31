package com.example.fortismobile.domain

import com.example.fortismobile.data.Device
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object CapabilityManager {
    private val gson = Gson()

    fun getCapabilities(device: Device?): Map<String, Boolean> {
        if (device == null || device.capabilitiesJson.isEmpty()) {
            return emptyMap()
        }
        return try {
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            gson.fromJson(device.capabilitiesJson, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun hasCapability(device: Device?, capability: String): Boolean {
        val caps = getCapabilities(device)
        return caps[capability] == true
    }
}
