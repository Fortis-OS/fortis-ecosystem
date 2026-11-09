package com.fortis.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import com.fortis.mobile.network.AgentClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class FortisWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_LOCK = "com.fortis.mobile.ACTION_LOCK"
        const val ACTION_BACKUP = "com.fortis.mobile.ACTION_BACKUP"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.fortis_widget)

        // Lock Button Intent
        val lockIntent = Intent(context, FortisWidgetProvider::class.java).apply { action = ACTION_LOCK }
        val lockPendingIntent = PendingIntent.getBroadcast(context, 0, lockIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.btn_lock, lockPendingIntent)

        // Backup Button Intent
        val backupIntent = Intent(context, FortisWidgetProvider::class.java).apply { action = ACTION_BACKUP }
        val backupPendingIntent = PendingIntent.getBroadcast(context, 1, backupIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.btn_backup, backupPendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_LOCK -> sendWidgetAction(context, "lock_screen", "Laptop Locked from Widget!")
            ACTION_BACKUP -> sendWidgetAction(context, "run_backup", "Backup Started from Widget!")
        }
    }

    private fun sendWidgetAction(context: Context, action: String, successMsg: String) {
        val sharedPrefs = context.getSharedPreferences("FortisPrefs", Context.MODE_PRIVATE)
        val ip = sharedPrefs.getString("ip", "") ?: ""
        val apiKey = sharedPrefs.getString("apiKey", "") ?: ""
        
        if (ip.isEmpty() || apiKey.isEmpty()) {
            Toast.makeText(context, "Widget: Set IP and API Key in app first!", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = AgentClient.getClient(apiKey)
                val json = """{"action":"$action"}"""
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("http://$ip:8023/api/v1/action")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, successMsg, Toast.LENGTH_SHORT).show()
                    }
                }
                response.close()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Widget Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
