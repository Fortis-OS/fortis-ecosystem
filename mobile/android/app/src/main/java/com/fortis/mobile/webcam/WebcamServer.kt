package com.fortis.mobile.webcam

import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The phone side of "phone as webcam" (security §7.6): an MJPEG stream (JPEG
 * frames back to back, as ffmpeg's `-f mjpeg` reads them) served
 *  - only on the phone's Tailscale address, on a random port,
 *  - under a random 128-bit token path,
 *  - only to the paired laptop's Tailscale address, one connection at a time.
 */
class WebcamServer(private val laptopIp: String) {
    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null
    private val lock = Object()
    private var frame: ByteArray? = null
    private var frameSeq = 0L
    @Volatile private var client: Socket? = null
    @Volatile private var running = false
    @Volatile var clientConnected = false
        private set

    val port: Int get() = server?.localPort ?: 0

    /** Bind to [bindAddr] (the phone's Tailscale address) on a random port. */
    fun start(bindAddr: InetAddress) {
        val s = ServerSocket(0, 1, bindAddr)
        server = s
        running = true
        Thread({ acceptLoop(s) }, "fortis-webcam").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        runCatching { client?.close() }
        synchronized(lock) { lock.notifyAll() }
    }

    /** Publish the newest JPEG; slow clients just skip frames. */
    fun offer(jpeg: ByteArray) = synchronized(lock) { frame = jpeg; frameSeq++; lock.notifyAll() }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val c = runCatching { s.accept() }.getOrNull() ?: break
            if (c.inetAddress.hostAddress != laptopIp || client != null) { runCatching { c.close() }; continue }
            client = c
            Thread({ serve(c) }, "fortis-webcam-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(c: Socket) {
        try {
            c.soTimeout = 5_000
            val line = readLine(BufferedInputStream(c.getInputStream())) ?: return
            val parts = line.split(' ')
            val expected = "/$token/stream"
            if (parts.size < 2 || parts[0] != "GET" ||
                !MessageDigest.isEqual(parts[1].toByteArray(), expected.toByteArray())) {
                c.getOutputStream().write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }
            c.soTimeout = 0
            val out = c.getOutputStream().buffered(256 * 1024)
            out.write("HTTP/1.0 200 OK\r\nContent-Type: video/x-motion-jpeg\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            clientConnected = true
            var seen = -1L
            while (running) {
                val f = synchronized(lock) {
                    while (running && frameSeq == seen) lock.wait(1_000)
                    seen = frameSeq; frame
                } ?: continue
                out.write(f); out.flush()
            }
        } catch (_: Exception) {
        } finally {
            clientConnected = false
            runCatching { c.close() }
            client = null
        }
    }

    /** Request line only; headers are ignored. Bounded to 2 KiB. */
    private fun readLine(i: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 2048) {
            val b = i.read(); if (b < 0) return null
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return null
    }

    companion object {
        /** The phone's own Tailscale IPv4 address (100.64.0.0/10), or null if Tailscale is off. */
        fun tailnetAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && com.fortis.mobile.pairing.Pairing.isTailnetIp(it.hostAddress ?: "") }
        }.getOrNull()
    }
}
