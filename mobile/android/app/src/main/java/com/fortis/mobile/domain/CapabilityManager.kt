package com.fortis.mobile.domain

import com.fortis.mobile.data.Device
import org.json.JSONObject

/**
 * Reads GET /capabilities (docs/api §5). Values are booleans or tool names
 * (e.g. "clipboard": "wl-copy", "input": "ydotool"); null/false = unavailable.
 */
object CapabilityManager {
    fun hasCapability(device: Device?, capability: String): Boolean {
        if (device == null) return false
        val o = runCatching { JSONObject(device.capabilitiesJson) }.getOrNull() ?: return false
        if (!o.has(capability) || o.isNull(capability)) return false
        return when (val v = o.get(capability)) {
            is Boolean -> v
            is String -> v.isNotEmpty()
            else -> false
        }
    }
}
