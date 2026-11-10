package com.fortis.mobile.network

import com.fortis.mobile.data.Device
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Remote keyboard/mouse over `GET /api/v1/input?session=<id>` (API §5b).
 * Needs a confirmed input session (see InputSession). Values are clamped to the
 * agent's limits (security §7.5) so nothing is silently dropped there.
 */
class InputSocketClient(private val laptop: Device, private val sessionId: String, private val onClosed: (String?) -> Unit) {
    private var ws: WebSocket? = null

    fun connect() {
        val req = FortisApiClient.buildRequest("/api/v1/input?session=$sessionId", laptop) { get() }
        ws = FortisApiClient.streaming.newWebSocket(req, object : WebSocketListener() {
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed(null)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                onClosed(if (response?.code == 401) "Session expired" else (t.message ?: "Connection lost"))
        })
    }

    fun disconnect() { main.removeCallbacks(flush); ws?.close(1000, "done"); ws = null }

    private fun send(o: JSONObject) { ws?.send(o.toString()) }

    // Touch moves arrive far faster than the agent's 200 events/s; coalesce to ~50/s.
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var accX = 0f
    private var accY = 0f
    private var flushPending = false
    private val flush = Runnable {
        flushPending = false
        if (accX != 0f || accY != 0f) {
            send(JSONObject().put("type", "mousemove").put("dx", accX.coerceIn(-2000f, 2000f).toDouble()).put("dy", accY.coerceIn(-2000f, 2000f).toDouble()))
            accX = 0f; accY = 0f
        }
    }

    fun sendMouseMove(dx: Float, dy: Float) {
        accX += dx; accY += dy
        if (!flushPending) { flushPending = true; main.postDelayed(flush, 20) }
    }
    fun sendClick(button: Int) = send(JSONObject().put("type", "mouseclick").put("button", button.coerceIn(1, 3)))
    fun sendMouseDown(button: Int) = send(JSONObject().put("type", "mousedown").put("button", button.coerceIn(1, 3)))
    fun sendMouseUp(button: Int) = send(JSONObject().put("type", "mouseup").put("button", button.coerceIn(1, 3)))
    fun sendKey(key: String) = send(JSONObject().put("type", "key").put("key", key))
    fun sendScroll(dx: Float, dy: Float) =
        send(JSONObject().put("type", "scroll").put("dx", dx.coerceIn(-50f, 50f).toDouble()).put("dy", dy.coerceIn(-50f, 50f).toDouble()))
    fun sendText(text: String) = text.filter { !it.isISOControl() }.chunked(1000).forEach { send(JSONObject().put("type", "type").put("text", it)) }
}
