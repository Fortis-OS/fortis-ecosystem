package com.fortis.mobile.domain

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.fortis.mobile.data.Device
import com.fortis.mobile.data.DeviceRepository
import com.fortis.mobile.pairing.Pairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/**
 * The laptop's capabilities JSON: the stored copy at first, then a fresh
 * GET /capabilities (saved back so other screens see it too).
 */
@Composable
fun rememberFreshCapabilities(device: Device): MutableState<String> {
    val ctx = LocalContext.current
    val state = remember(device.uuid) { mutableStateOf(device.capabilitiesJson) }
    LaunchedEffect(device.uuid) {
        val fresh = withContext(Dispatchers.IO) { runCatching { Pairing.capabilities(device) }.getOrNull() } ?: return@LaunchedEffect
        state.value = fresh
        val repo = DeviceRepository.get(ctx)
        repo.get(device.uuid)?.let { repo.save(it.copy(capabilitiesJson = fresh, lastSeen = System.currentTimeMillis())) }
    }
    return state
}
