package com.fortis.mobile

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.fortis.mobile.data.DeviceRepository
import com.fortis.mobile.network.FortisApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Allow-list of apps whose notifications are mirrored. Empty = off (default). */
object NotificationMirror {
    private const val PREFS = "fortis_notify"
    fun allowed(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("apps", emptySet()) ?: emptySet()

    fun setAllowed(context: Context, pkg: String, on: Boolean) {
        val s = allowed(context).toMutableSet()
        if (on) s.add(pkg) else s.remove(pkg)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("apps", s).apply()
    }
}

/**
 * Mirrors notifications from allow-listed apps to the laptop (docs/security §6.4).
 * Never sends Fortis' own notifications or ones Android marks secret.
 */
class FortisNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var last = 0L

    /**
     * The system (re)binds this listener whenever Fortis' process starts: after an app update,
     * after Android or the OEM's battery manager killed the app, and at boot. Some phones (XOS)
     * never deliver MY_PACKAGE_REPLACED to Fortis, so this is also where the sudo/events
     * connection gets restarted.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        com.fortis.mobile.service.EventsService.start(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName
        if (pkg == packageName || pkg !in NotificationMirror.allowed(this)) return
        val n = sbn.notification
        if (n.visibility == Notification.VISIBILITY_SECRET) return
        val now = System.currentTimeMillis()
        if (now - last < 1000) return // max 1 per second
        last = now

        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val laptop = DeviceRepository.get(this).defaultLaptop() ?: return

        scope.launch {
            val body = JSONObject().put("app", app.take(60)).put("title", title.take(120)).put("body", text.take(500))
                .toString().toRequestBody("application/json".toMediaType())
            runCatching {
                FortisApiClient.getClient().newCall(FortisApiClient.buildRequest("/api/v1/notify", laptop) { post(body) }).execute().close()
            }
        }
    }
}
