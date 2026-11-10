package com.fortis.mobile.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Paired laptops, in app-private storage (no secrets — keys are in the Keystore). */
class DeviceRepository private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("fortis_laptops", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    init {
        val json = prefs.getString("laptops", "[]")
        val type = object : TypeToken<List<Device>>() {}.type
        _devices.value = try { gson.fromJson<List<Device>>(json, type) ?: emptyList() } catch (e: Exception) { emptyList() }
    }

    fun get(uuid: String): Device? = _devices.value.find { it.uuid == uuid }

    /** The vault laptop, or the only/first paired laptop — used by the widget and background jobs. */
    fun defaultLaptop(): Device? = _devices.value.find { it.isVaultLaptop } ?: _devices.value.firstOrNull()

    fun save(device: Device) {
        val list = _devices.value.toMutableList()
        val i = list.indexOfFirst { it.uuid == device.uuid }
        if (i >= 0) list[i] = device else list.add(device)
        // Exactly one vault laptop.
        val fixed = if (device.isVaultLaptop) list.map { if (it.uuid != device.uuid) it.copy(isVaultLaptop = false) else it } else list
        persist(fixed)
    }

    fun remove(uuid: String) = persist(_devices.value.filter { it.uuid != uuid })

    private fun persist(list: List<Device>) {
        prefs.edit().putString("laptops", gson.toJson(list)).apply()
        _devices.value = list
    }

    companion object {
        @Volatile private var instance: DeviceRepository? = null
        fun get(context: Context): DeviceRepository =
            instance ?: synchronized(this) { instance ?: DeviceRepository(context).also { instance = it } }
    }
}
