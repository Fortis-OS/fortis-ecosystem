package com.fortis.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restart the events connection after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("FortisEvents", "BootReceiver: ${intent.action}")
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            EventsService.start(context)
        }
    }
}
