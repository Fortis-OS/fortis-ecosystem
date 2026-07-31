package com.example.fortismobile

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.fortismobile.network.AgentClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class FortisNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var lastNotifTime = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        // Debounce to prevent flooding
        val now = System.currentTimeMillis()
        if (now - lastNotifTime < 1000) return
        lastNotifTime = now

        val packageName = sbn.packageName
        
        // Ignore self and system UI
        if (packageName == applicationContext.packageName || packageName == "com.android.systemui") return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (title.isEmpty() && text.isEmpty()) return

        val pm = applicationContext.packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        val displayTitle = if (title.isEmpty()) appName else "$appName - $title"

        val sharedPrefs = applicationContext.getSharedPreferences("FortisPrefs", Context.MODE_PRIVATE)
        val ip = sharedPrefs.getString("ip", "") ?: ""
        val apiKey = sharedPrefs.getString("apiKey", "") ?: ""

        if (ip.isEmpty() || apiKey.isEmpty()) return

        serviceScope.launch {
            try {
                val client = AgentClient.getClient(apiKey)
                val json = JSONObject()
                    .put("title", displayTitle)
                    .put("body", text)
                    .toString()
                
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("http://$ip:8023/api/v1/notify")
                    .post(body)
                    .build()
                
                val response = client.newCall(request).execute()
                response.close()
            } catch (e: Exception) {
                Log.e("FortisNotify", "Failed to sync notification: ${e.message}")
            }
        }
    }
}
