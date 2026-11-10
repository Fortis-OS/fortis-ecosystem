package com.fortis.mobile.network

import com.fortis.mobile.data.Device
import com.fortis.mobile.domain.ActiveDeviceProvider
import com.fortis.mobile.keys.DeviceKeys
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Signs every request to a paired laptop (docs/security §5.3):
 * X-Fortis-Device, X-Fortis-Timestamp, X-Fortis-Nonce, X-Fortis-Signature
 * over the canonical bytes built by fortis-bindings (identical to the agent).
 *
 * The target laptop travels with the request as a tag, so background jobs
 * can address a specific laptop without touching the active one.
 */
class SigningInterceptor : Interceptor {
    private val random = SecureRandom()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val device = original.tag(Device::class.java) ?: return chain.proceed(original)

        val bodyBytes = original.body?.let { b -> Buffer().also { b.writeTo(it) }.readByteArray() } ?: ByteArray(0)
        val ts = System.currentTimeMillis() / 1000
        val nonce = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val url = original.url
        val pathAndQuery = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val message = uniffi.fortis_bindings.canonicalRequest(original.method, pathAndQuery, ts.toULong(), nonce, bodyBytes)
        val signature = DeviceKeys.signRequest(device.uuid, message)

        val builder = original.newBuilder()
            .header("X-Fortis-Device", device.deviceId)
            .header("X-Fortis-Timestamp", ts.toString())
            .header("X-Fortis-Nonce", nonce)
            .header("X-Fortis-Signature", signature)
        original.body?.let { builder.method(original.method, bodyBytes.toRequestBody(it.contentType())) }
        return chain.proceed(builder.build())
    }
}

object FortisApiClient {
    private val client = OkHttpClient.Builder()
        .addInterceptor(SigningInterceptor())
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    /** For WebSockets (events, input): no read timeout. */
    val streaming: OkHttpClient = client.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(0, TimeUnit.SECONDS).build()

    fun getClient(): OkHttpClient = client

    /** A signed request to [device] (defaults to the active laptop). */
    fun buildRequest(path: String, device: Device? = null, builderBlock: Request.Builder.() -> Unit = {}): Request {
        val d = device ?: ActiveDeviceProvider.get() ?: throw IllegalStateException("No laptop selected")
        val b = Request.Builder().url(d.baseUrl + path).tag(Device::class.java, d)
        b.builderBlock()
        return b.build()
    }
}
