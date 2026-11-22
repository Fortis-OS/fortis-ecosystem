package com.fortis.mobile.backup

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.fortis.mobile.R
import com.fortis.mobile.data.Device
import com.fortis.mobile.service.EventsService
import org.json.JSONObject

/**
 * `backup_request` from the agent (API §4): fortis-backup is waiting for the phone. Shows one
 * "Backup waiting" notification; tapping it opens [BackupModeActivity], which starts Termux
 * backup mode exactly like the Dashboard button. Nothing runs without the tap.
 */
object BackupRequests {
    private const val NOTIF_ID = 60 // one at a time: a newer request replaces the older one
    private const val MIN_WAIT_MS = 60_000L
    private const val MAX_WAIT_MS = 3_600_000L

    fun show(context: Context, laptop: Device, m: JSONObject) {
        val window = runCatching {
            require(m.getString("request_id").matches(Regex("[0-9a-f]{32}"))) { "bad request_id" }
            ((m.getLong("expires_at") - m.getLong("issued_at")) * 1000).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
        }.getOrNull() ?: return

        val open = Intent(context, BackupModeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, NOTIF_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, EventsService.CHANNEL_BACKUP)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Backup waiting for this phone")
            .setContentText("${laptop.name} is ready to push today's backup. Tap to start backup mode.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(window)
            .setContentIntent(pi)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}
