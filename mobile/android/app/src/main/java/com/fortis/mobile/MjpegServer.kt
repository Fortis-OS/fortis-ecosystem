package com.fortis.mobile

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class MjpegServer(private val port: Int) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val clients = CopyOnWriteArrayList<Socket>()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Store latest frame
    private var latestJpeg: ByteArray? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    clients.add(client)
                    scope.launch { handleClient(client) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        clients.forEach { 
            try { it.close() } catch (e: Exception) {}
        }
        clients.clear()
    }

    fun updateFrame(image: ImageProxy) {
        if (clients.isEmpty()) {
            image.close()
            return
        }

        try {
            val bitmap = image.toBitmap()
            val matrix = Matrix()
            matrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
            val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val out = ByteArrayOutputStream()
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            latestJpeg = out.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val out: OutputStream = client.getOutputStream()
            out.write(("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=--boundary\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Cache-Control: no-cache\r\n\r\n").toByteArray())
            out.flush()

            while (isRunning && !client.isClosed) {
                val jpeg = latestJpeg
                if (jpeg != null) {
                    out.write(("--boundary\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${jpeg.size}\r\n\r\n").toByteArray())
                    out.write(jpeg)
                    out.write("\r\n".toByteArray())
                    out.flush()
                }
                Thread.sleep(30) // ~30fps cap
            }
        } catch (e: Exception) {
            // Client disconnected
        } finally {
            clients.remove(client)
            try { client.close() } catch (e: Exception) {}
        }
    }
}
