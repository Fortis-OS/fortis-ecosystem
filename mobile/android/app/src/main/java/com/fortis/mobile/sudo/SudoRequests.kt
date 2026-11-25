package com.fortis.mobile.sudo

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.fortis.mobile.R
import com.fortis.mobile.data.Device
import com.fortis.mobile.service.EventsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** A sudo request waiting for the user. Kept in memory only — never written to disk. */
data class PendingSudo(
    val requestId: String,
    val laptopUuid: String,
    val laptopName: String,
    val user: String,
    val host: String,
    val service: String,
    val tty: String,
    /** Exactly the seven fields that get signed (EventFrames.approvalRequestJson). */
    val requestJson: String,
    /** Local deadline, from the phone's own clock (immune to clock skew). */
    val deadlineMillis: Long,
) {
    /** Fortis Lock asking to show the locked sites again (security §7.8), not sudo. */
    val isBrowserUnlock: Boolean get() = service == "fortis-lock"
}

object SudoRequests {
    private const val MAX_WAIT_MS = 60_000L // auth.approval_timeout_secs is capped at 60
    private val main = Handler(Looper.getMainLooper())
    private val _pending = MutableStateFlow<Map<String, PendingSudo>>(emptyMap())
    val pending: StateFlow<Map<String, PendingSudo>> = _pending.asStateFlow()

    private fun notifId(requestId: String) = 1000 + (requestId.hashCode() and 0xffff)

    fun show(context: Context, laptop: Device, m: JSONObject) {
        val p = runCatching {
            val id = m.getString("request_id")
            require(id.matches(Regex("[0-9a-f]{32}"))) { "bad request_id" }
            val window = ((m.getLong("expires_at") - m.getLong("issued_at")) * 1000).coerceIn(1_000, MAX_WAIT_MS)
            PendingSudo(
                requestId = id,
                laptopUuid = laptop.uuid,
                laptopName = laptop.name,
                user = m.getString("user"),
                host = m.getString("host"),
                service = m.getString("service"),
                tty = m.getString("tty"),
                requestJson = com.fortis.mobile.service.EventFrames.approvalRequestJson(m),
                deadlineMillis = System.currentTimeMillis() + window,
            )
        }.getOrNull() ?: return
        if (p.requestId in _pending.value) return
        _pending.value = _pending.value + (p.requestId to p)
        main.postDelayed({ cancel(context, p.requestId) }, p.deadlineMillis - System.currentTimeMillis())

        val open = Intent(context, SudoApprovalActivity::class.java)
            .putExtra(SudoApprovalActivity.EXTRA_REQUEST_ID, p.requestId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(context, notifId(p.requestId), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, EventsService.CHANNEL_SUDO)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (p.isBrowserUnlock) "Unlock the browser on ${p.host}?" else "Approve sudo on ${p.host}?")
            .setContentText(if (p.isBrowserUnlock) "Fortis Lock · show the locked sites again" else "${p.user} · ${p.service} · ${p.tty}")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, EventsService.CHANNEL_SUDO)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Sudo request").setContentText("Unlock to review").build()
            )
            .setAutoCancel(true)
            .setTimeoutAfter(p.deadlineMillis - System.currentTimeMillis())
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(notifId(p.requestId), n) }
    }

    /** Remove a request (answered, cancelled by the laptop, or expired). */
    fun cancel(context: Context, requestId: String) {
        if (requestId !in _pending.value) return
        _pending.value = _pending.value - requestId
        context.getSystemService(NotificationManager::class.java).cancel(notifId(requestId))
    }
}
