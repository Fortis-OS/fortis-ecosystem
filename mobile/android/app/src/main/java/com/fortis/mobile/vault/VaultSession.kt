package com.fortis.mobile.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The unlocked vault key, in memory only. Locks (and zeroes the key) after
 * 5 minutes without use, 60 s after the app goes to the background, or on demand.
 */
object VaultSession {
    const val IDLE_MS = 5 * 60_000L
    const val BACKGROUND_MS = 60_000L

    private var key: ByteArray? = null
    private var lastUse = 0L
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    @Synchronized
    fun set(k: ByteArray) {
        require(k.size == 32)
        lock()
        key = k.copyOf()
        k.fill(0)
        lastUse = System.currentTimeMillis()
        _unlocked.value = true
    }

    @Synchronized
    fun lock() {
        key?.fill(0)
        key = null
        _unlocked.value = false
    }

    /** Run [block] with the key, or throw [VaultLocked]. Counts as activity. */
    @Synchronized
    fun <T> withKey(block: (ByteArray) -> T): T {
        val k = key ?: throw VaultLocked()
        if (System.currentTimeMillis() - lastUse > IDLE_MS) {
            lock()
            throw VaultLocked()
        }
        lastUse = System.currentTimeMillis()
        return block(k)
    }
}

class VaultLocked : Exception("The vault is locked")
