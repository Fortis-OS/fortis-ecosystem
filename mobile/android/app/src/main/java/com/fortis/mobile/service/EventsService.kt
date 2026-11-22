package com.fortis.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fortis.mobile.MainActivity
import com.fortis.mobile.R
import com.fortis.mobile.backup.BackupRequests
import com.fortis.mobile.data.Device
import com.fortis.mobile.data.DeviceRepository
import com.fortis.mobile.network.FortisApiClient
import com.fortis.mobile.sudo.SudoRequests
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Keeps an outgoing WebSocket to every paired laptop's /api/v1/events so sudo
 * requests arrive without any cloud push service (architecture §5.4). Outgoing
 * only — the phone never listens on a port for this.
 */
class EventsService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val sockets = mutableMapOf<String, WebSocket>()
    private val lastFrame = mutableMapOf<String, Long>()
    private val backoff = mutableMapOf<String, Long>()
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        channels(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, statusNotification(0), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, statusNotification(0))
        main.post(watchdog)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enabled(this) || DeviceRepository.get(this).devices.value.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        reconcile()
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        running = false
        main.removeCallbacksAndMessages(null)
        sockets.values.forEach { it.close(1000, "stopping") }
        sockets.clear()
        _online.value = emptySet()
        super.onDestroy()
    }

    /** Connect to every paired laptop that has no socket; drop sockets of forgotten ones. */
    private fun reconcile() {
        val laptops = DeviceRepository.get(this).devices.value
        val ids = laptops.map { it.uuid }.toSet()
        sockets.keys.filter { it !in ids }.forEach { sockets.remove(it)?.close(1000, "forgotten") }
        laptops.filter { it.uuid !in sockets }.forEach { connect(it) }
    }

    private fun connect(laptop: Device) {
        if (stopped) return
        val req = FortisApiClient.buildRequest("/api/v1/events", laptop) { get() }
        // Start the silence clock now, not at onOpen: a connection that never opens (app frozen
        // mid-connect, link dropped) must still time out instead of blocking reconnects forever.
        lastFrame[laptop.uuid] = System.currentTimeMillis()
        android.util.Log.i(TAG, "connecting events to ${laptop.name}")
        sockets[laptop.uuid] = FortisApiClient.events.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post { backoff[laptop.uuid] = 0; lastFrame[laptop.uuid] = System.currentTimeMillis() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { lastFrame[laptop.uuid] = System.currentTimeMillis() }
                val current = DeviceRepository.get(this@EventsService).get(laptop.uuid) ?: return
                val msg = EventFrames.verify(current.agentPubkey, text) ?: return // not from our agent: drop
                handle(current, webSocket, msg)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.w(TAG, "events to ${laptop.name} failed: ${t.javaClass.simpleName}: ${t.message} (HTTP ${response?.code})")
                main.post { dropped(laptop.uuid, webSocket) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                android.util.Log.i(TAG, "events to ${laptop.name} closed: $code $reason")
                main.post { dropped(laptop.uuid, webSocket) }
            }
        })
        updateStatus()
    }

    private fun handle(laptop: Device, ws: WebSocket, m: JSONObject) {
        when (m.optString("type")) {
            "hello" -> main.post { setOnline(laptop.uuid, true) }
            "ping" -> ws.send(JSONObject().put("type", "pong").put("t", m.optLong("t")).toString())
            "sudo_request" -> main.post { SudoRequests.show(this, laptop, m) }
            "sudo_cancelled" -> main.post { SudoRequests.cancel(this, m.optString("request_id")) }
            "backup_request" -> main.post { BackupRequests.show(this, laptop, m) }
        }
    }

    private fun dropped(uuid: String, ws: WebSocket) {
        if (sockets[uuid] !== ws) return
        sockets.remove(uuid)
        setOnline(uuid, false)
        if (stopped || DeviceRepository.get(this).get(uuid) == null) return
        val next = ((backoff[uuid] ?: 0L) * 2).coerceIn(5_000, 60_000)
        backoff[uuid] = next
        main.postDelayed({ DeviceRepository.get(this).get(uuid)?.let { if (uuid !in sockets) connect(it) } }, next)
    }

    /**
     * The agent pings every 25 s; a laptop silent for 45 s (or a connection that never opened in
     * that time) is cancelled. The socket is also dropped from [sockets] right away, so the next
     * reconcile reconnects even if OkHttp never calls back.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            sockets.entries.filter { now - (lastFrame[it.key] ?: 0L) > 45_000 }.toList().forEach { (uuid, ws) ->
                android.util.Log.w(TAG, "events to $uuid silent for ${(now - (lastFrame[uuid] ?: 0L)) / 1000}s: reconnecting")
                sockets.remove(uuid)
                setOnline(uuid, false)
                ws.cancel()
            }
            reconcile()
            main.postDelayed(this, 30_000)
        }
    }

    private fun setOnline(uuid: String, on: Boolean) {
        _online.value = if (on) _online.value + uuid else _online.value - uuid
        updateStatus()
    }

    private fun updateStatus() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, statusNotification(_online.value.size))
    }

    private fun statusNotification(online: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val total = DeviceRepository.get(this).devices.value.size
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Fortis connected")
            .setContentText(if (online > 0) "Listening for sudo requests ($online of $total laptops online)" else "Connecting to your laptops…")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "FortisEvents"
        const val CHANNEL_SERVICE = "fortis.connection"
        const val CHANNEL_SUDO = "fortis.sudo"
        const val CHANNEL_BACKUP = "fortis.backup"
        private const val NOTIF_ID = 50
        private const val PREFS = "fortis_events"

        @Volatile private var running = false
        private val _online = MutableStateFlow<Set<String>>(emptySet())
        /** Paired laptop uuids currently connected. */
        val online: StateFlow<Set<String>> = _online.asStateFlow()

        fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
            if (on) start(context) else context.stopService(Intent(context, EventsService::class.java))
        }

        /** Start, refresh or (with no laptops left) stop the connections. */
        fun start(context: Context) {
            if (!enabled(context)) return
            if (DeviceRepository.get(context).devices.value.isEmpty() && !running) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, EventsService::class.java)) }
                .onFailure { android.util.Log.w(TAG, "couldn't start the events service: ${it.javaClass.simpleName}: ${it.message}") }
        }

        fun channels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Fortis listens for sudo requests"
            })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_SUDO, "Sudo requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A laptop asks you to approve sudo"
                setBypassDnd(true)
            })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_BACKUP, "Backup requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "The laptop's nightly backup is waiting for this phone"
            })
        }
    }
}
