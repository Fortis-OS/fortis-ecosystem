package com.fortis.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import com.fortis.mobile.data.DeviceRepository
import com.fortis.mobile.network.FortisApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Home-screen widget: Lock laptop / Back up now, as signed requests to the
 * default laptop. Buttons use explicit, immutable PendingIntents, and the
 * receiver has no custom intent-filter actions, so other apps can't trigger them.
 */
class FortisWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.fortis_widget)
            views.setOnClickPendingIntent(R.id.btn_lock, pending(context, ACTION_LOCK))
            views.setOnClickPendingIntent(R.id.btn_backup, pending(context, ACTION_BACKUP))
            manager.updateAppWidget(id, views)
        }
    }

    private fun pending(context: Context, action: String): PendingIntent {
        val i = Intent(context, FortisWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(context, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_LOCK -> send(context, "lock_screen", "Laptop locked")
            ACTION_BACKUP -> send(context, "run_backup", "Backup started")
        }
    }

    private fun send(context: Context, action: String, ok: String) {
        val laptop = DeviceRepository.get(context).defaultLaptop()
        if (laptop == null) {
            Toast.makeText(context, "Pair a laptop in the Fortis app first", Toast.LENGTH_SHORT).show()
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val msg = try {
                val body = """{"action":"$action"}""".toRequestBody("application/json".toMediaType())
                FortisApiClient.getClient().newCall(FortisApiClient.buildRequest("/api/v1/actions", laptop) { post(body) })
                    .execute().use { if (it.isSuccessful) ok else "Failed (HTTP ${it.code})" }
            } catch (e: Exception) {
                "Can't reach ${laptop.name}"
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LOCK = "com.fortis.mobile.widget.LOCK"
        const val ACTION_BACKUP = "com.fortis.mobile.widget.BACKUP"
    }
}
