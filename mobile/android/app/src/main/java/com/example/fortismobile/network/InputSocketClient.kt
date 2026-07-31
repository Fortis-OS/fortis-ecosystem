package com.example.fortismobile.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class InputSocketClient(private val client: OkHttpClient, private val hostUrl: String) {
    private var webSocket: WebSocket? = null

    fun connect() {
        // Strip http:// or https:// and replace with ws:// or wss://
        val wsUrl = hostUrl.replace("http://", "ws://").replace("https://", "wss://") + "/api/v1/input"
        
        val request = Request.Builder()
            .url(wsUrl)
            .build()
            
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                Log.d("InputSocket", "Connected to $wsUrl")
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e("InputSocket", "Connection failed", t)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }

    fun sendMouseMove(dx: Float, dy: Float) {
        val json = JSONObject().apply {
            put("type", "mousemove")
            put("dx", dx)
            put("dy", dy)
        }
        webSocket?.send(json.toString())
    }

    fun sendClick(button: Int) {
        val json = JSONObject().apply {
            put("type", "mouseclick")
            put("button", button)
        }
        webSocket?.send(json.toString())
    }

    fun sendMouseDown(button: Int) {
        val json = JSONObject().apply {
            put("type", "mousedown")
            put("button", button)
        }
        webSocket?.send(json.toString())
    }

    fun sendMouseUp(button: Int) {
        val json = JSONObject().apply {
            put("type", "mouseup")
            put("button", button)
        }
        webSocket?.send(json.toString())
    }

    fun sendKey(key: String) {
        val json = JSONObject().apply {
            put("type", "keydown")
            put("key", key)
        }
        webSocket?.send(json.toString())
    }

    fun sendScroll(dx: Float, dy: Float) {
        val json = JSONObject().apply {
            put("type", "scroll")
            put("dx", dx)
            put("dy", dy)
        }
        webSocket?.send(json.toString())
    }

    fun sendText(text: String) {
        val json = JSONObject().apply {
            put("type", "type")
            put("text", text)
        }
        webSocket?.send(json.toString())
    }
}
