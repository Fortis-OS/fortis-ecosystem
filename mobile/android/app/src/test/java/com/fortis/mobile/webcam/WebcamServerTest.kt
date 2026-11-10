package com.fortis.mobile.webcam

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Runs on the JVM: the stream server's access rules, and that real ffmpeg reads it like the agent does. */
class WebcamServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private var server: WebcamServer? = null
    @Volatile private var feeding = true

    @After fun tearDown() { feeding = false; server?.stop() }

    private fun jpeg(shade: Int): ByteArray {
        val img = BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) for (x in 0 until 320) img.setRGB(x, y, (shade shl 16) or (x shl 8) or y)
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun startFeeding(s: WebcamServer) = Thread {
        var i = 0
        while (feeding) { s.offer(jpeg(i++ % 255)); Thread.sleep(50) }
    }.apply { isDaemon = true; start() }

    private fun get(s: WebcamServer, path: String): String = Socket(loopback, s.port).use { c ->
        c.soTimeout = 3000
        c.getOutputStream().write("GET $path HTTP/1.0\r\n\r\n".toByteArray())
        val buf = ByteArray(64); val n = runCatching { c.getInputStream().read(buf) }.getOrDefault(-1)
        if (n <= 0) "" else String(buf, 0, n)
    }

    @Test fun tokenIsRandom128Bit() {
        val a = WebcamServer("127.0.0.1").token; val b = WebcamServer("127.0.0.1").token
        assertEquals(32, a.length); assertTrue(a != b)
    }

    @Test fun wrongTokenIs404() {
        val s = WebcamServer("127.0.0.1").also { server = it; it.start(loopback) }
        assertTrue(get(s, "/0000/stream").startsWith("HTTP/1.0 404"))
        assertTrue(get(s, "/${s.token}/other").startsWith("HTTP/1.0 404"))
    }

    @Test fun onlyThePairedLaptopMayConnect() {
        val s = WebcamServer("100.64.0.9").also { server = it; it.start(loopback) }
        assertEquals("", get(s, "/${s.token}/stream")) // closed without a reply
    }

    @Test fun ffmpegReadsTheStreamLikeTheAgent() {
        val ffmpeg = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg").firstOrNull { File(it).canExecute() }
        assumeTrue("ffmpeg not installed", ffmpeg != null)
        val s = WebcamServer("127.0.0.1").also { server = it; it.start(loopback) }
        startFeeding(s)
        // Same input options as fortis-agent's webcam_start, writing to null instead of v4l2.
        val p = ProcessBuilder(ffmpeg, "-nostdin", "-loglevel", "error", "-rw_timeout", "10000000",
            "-f", "mjpeg", "-i", "http://127.0.0.1:${s.port}/${s.token}/stream",
            "-frames:v", "10", "-vf", "format=yuv420p", "-f", "null", "-")
            .redirectErrorStream(true).start()
        assertTrue("ffmpeg timed out", p.waitFor(20, TimeUnit.SECONDS))
        val out = p.inputStream.bufferedReader().readText()
        assertEquals("ffmpeg failed: $out", 0, p.exitValue())

        // The slot frees once the first viewer is gone…
        val until = System.currentTimeMillis() + 5000
        while (s.clientConnected && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue("slot not freed", !s.clientConnected)
        // …and a second viewer is refused while another one is connected.
        val p1 = ProcessBuilder(ffmpeg, "-nostdin", "-loglevel", "error", "-f", "mjpeg",
            "-i", "http://127.0.0.1:${s.port}/${s.token}/stream", "-f", "null", "-").start()
        Thread.sleep(1500)
        assertTrue(s.clientConnected)
        assertEquals("", get(s, "/${s.token}/stream"))
        p1.destroy(); p1.waitFor(5, TimeUnit.SECONDS)
    }
}
