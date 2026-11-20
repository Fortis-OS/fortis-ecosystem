package com.fortis.mobile.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Whether the phone is ready to receive a backup from the laptop (fortis-backup's phone stage). */
data class Readiness(
    val batteryPercent: Int,
    val freeGb: Long,
    val tailscale: Boolean,
    val sshd: Boolean,
) {
    val batteryOk get() = batteryPercent > 20
    val storageOk get() = freeGb > 5
    val ready get() = batteryOk && storageOk && tailscale && sshd
}

sealed interface StartResult {
    /** Ask for [PhoneStatus.TERMUX_PERMISSION], then try again. */
    data object NeedsPermission : StartResult
    data object Started : StartResult
    data class Failed(val reason: String) : StartResult
}

/** The phone's side: VPN, battery, storage, the Termux SSH daemon. Never shows UI or prompts. */
object PhoneStatus {
    const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"

    /** Check only: is a VPN (Tailscale) carrying traffic? Safe to call from polling loops. */
    fun isTailscaleUp(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /** Is Termux's sshd listening on 127.0.0.1:8022? */
    suspend fun sshdRunning(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", 8022), 500) } }.isSuccess
    }

    suspend fun backupReadiness(context: Context): Readiness {
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.blockSizeLong * stat.availableBlocksLong / (1024L * 1024 * 1024)
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return Readiness(
            batteryPercent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            freeGb = freeGb,
            tailscale = isTailscaleUp(context),
            sshd = sshdRunning(),
        )
    }

    /** Runs `fortis-mobile start` in Termux (sshd + backup mode). */
    fun startTermuxDaemon(context: Context): StartResult {
        if (context.checkSelfPermission(TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return StartResult.NeedsPermission
        }
        return try {
            val intent = Intent("com.termux.RUN_COMMAND")
                .setClassName("com.termux", "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                .putExtra(
                    "com.termux.RUN_COMMAND_ARGUMENTS",
                    arrayOf("-c", "bash $TERMUX_HOME/.local/bin/fortis-mobile start > $TERMUX_HOME/intent.log 2>&1"),
                )
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                .putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "4")
            context.startService(intent)
            StartResult.Started
        } catch (e: Exception) {
            StartResult.Failed("Couldn't start Termux: ${e.message}")
        }
    }

    /** Starts Termux's side, then asks the laptop to run its backup (which pushes to the phone). */
    suspend fun startPhoneBackup(context: Context, device: Device? = null): StartResult {
        when (val t = startTermuxDaemon(context)) {
            StartResult.Started -> Unit
            else -> return t
        }
        return try {
            LaptopApi.action("run_backup", device)
            StartResult.Started
        } catch (e: Exception) {
            StartResult.Failed("Termux started, but the laptop didn't start the backup: ${e.message}")
        }
    }
}
