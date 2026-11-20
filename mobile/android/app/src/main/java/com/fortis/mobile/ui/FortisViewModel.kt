package com.fortis.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fortis.mobile.data.Device
import com.fortis.mobile.data.DeviceRepository
import com.fortis.mobile.data.LaptopApi
import com.fortis.mobile.data.MediaState
import com.fortis.mobile.data.PhoneStatus
import com.fortis.mobile.data.Readiness
import com.fortis.mobile.domain.ActiveDeviceProvider
import com.fortis.mobile.keys.DeviceKeys
import com.fortis.mobile.network.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** How often the shared streams poll while a screen is watching. */
private const val POLL_MS = 5_000L

class FortisViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DeviceRepository.get(application)

    /** Paired laptops. */
    val devices: StateFlow<List<Device>> = repository.devices
    val activeDevice: StateFlow<Device?> = ActiveDeviceProvider.activeDevice

    fun selectDevice(device: Device) = ActiveDeviceProvider.setActiveDevice(device)

    fun clearActiveDevice() = ActiveDeviceProvider.setActiveDevice(null)

    fun savePaired(device: Device) {
        repository.save(device)
        com.fortis.mobile.service.EventsService.start(getApplication())
    }

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
        com.fortis.mobile.service.EventsService.start(getApplication())
    }

    // ---- Shared live data ------------------------------------------------------------------------
    // One poller per stream, shared by every screen. Each restarts when the active laptop changes,
    // stops 5 s after the last screen stops watching, skips the network while Tailscale is off, and
    // keeps the last good value when a poll fails (so a dropped request doesn't blank the screen).

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> pollLaptop(block: suspend (Device) -> T?): StateFlow<T?> =
        activeDevice.flatMapLatest { device ->
            if (device == null) flowOf<T?>(null) else pollEvery<T> { last ->
                if (!PhoneStatus.isTailscaleUp(getApplication())) last else block(device)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Emits [next] every [POLL_MS]; on failure re-emits the previous value. */
    private fun <T> pollEvery(next: suspend (last: T?) -> T?): Flow<T?> = flow {
        var last: T? = null
        while (true) {
            last = try {
                next(last)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last
            }
            emit(last)
            delay(POLL_MS)
        }
    }

    val telemetry: StateFlow<Telemetry?> = pollLaptop { LaptopApi.telemetry(it) }

    /** `null` when nothing is playing (or before the first answer). */
    val media: StateFlow<MediaState?> = pollLaptop { LaptopApi.media(it) }

    val readiness: StateFlow<Readiness?> =
        pollEvery<Readiness> { PhoneStatus.backupReadiness(getApplication()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
