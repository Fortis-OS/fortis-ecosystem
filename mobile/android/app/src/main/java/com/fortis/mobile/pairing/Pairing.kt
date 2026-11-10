package com.fortis.mobile.pairing

import android.net.Uri
import android.os.Build
import com.fortis.mobile.data.Device
import com.fortis.mobile.keys.DeviceKeys
import com.fortis.mobile.network.FortisApiClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** What the laptop's `fortis pair` QR code contains (docs/security §5.4). */
data class PairingInfo(val host: String, val port: Int, val code: String, val agentFingerprint: String?)

data class PairingResult(val device: Device, val requestKeyFp: String, val approvalKeyFp: String, val agentFp: String)

class PairingException(message: String) : Exception(message)

object Pairing {
    private val plain = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

    /** Parse `fortis://pair?host=…&port=…&code=…&agent_fp=…`. */
    fun parseUri(text: String): PairingInfo? {
        val u = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
        if (u.scheme != "fortis" || u.host != "pair") return null
        val host = u.getQueryParameter("host")?.takeIf { isTailnetIp(it) } ?: return null
        val code = u.getQueryParameter("code")?.takeIf { it.isNotBlank() } ?: return null
        val port = u.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: 8023
        val fp = u.getQueryParameter("agent_fp")?.takeIf { it.matches(Regex("[0-9a-f]{16}")) }
        return PairingInfo(host, port, code, fp)
    }

    /** 100.64.0.0/10 — the agent only listens on its Tailscale address. */
    fun isTailnetIp(s: String): Boolean {
        val p = s.split('.').mapNotNull { it.toIntOrNull() }
        return p.size == 4 && p.all { it in 0..255 } && p[0] == 100 && (p[1] and 0xC0) == 64
    }

    /**
     * Generate this pairing's keys, send their public halves with the code,
     * and pin the agent key. Blocking — call off the main thread.
     */
    fun pair(info: PairingInfo, makeVaultLaptop: Boolean): PairingResult {
        val pairingId = UUID.randomUUID().toString()
        try {
            DeviceKeys.create(pairingId)
        } catch (e: Exception) {
            DeviceKeys.delete(pairingId)
            // The approval key needs a strong biometric (security §6.2).
            throw PairingException("Set up a screen lock and at least one fingerprint on this phone first, then pair again.")
        }
        try {
            val reqPub = DeviceKeys.publicKeyBase64(DeviceKeys.requestAlias(pairingId))
            val apprPub = DeviceKeys.publicKeyBase64(DeviceKeys.approvalAlias(pairingId))
            val body = JSONObject()
                .put("code", info.code)
                .put("device_name", (Build.MODEL ?: "Android phone").take(64))
                .put("request_pubkey", reqPub)
                .put("approval_pubkey", apprPub)
                .toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("http://${info.host}:${info.port}/api/v1/pair").post(body).build()
            val json = plain.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                when (r.code) {
                    200 -> JSONObject(text)
                    401 -> throw PairingException("The code is wrong or expired. Run `fortis pair` again.")
                    429 -> throw PairingException("Too many wrong codes. Run `fortis pair` again.")
                    else -> throw PairingException("Pairing failed (HTTP ${r.code}).")
                }
            }
            val agentPub = json.getString("agent_pubkey")
            val agentFp = uniffi.fortis_bindings.keyFingerprint(agentPub)
            if (info.agentFingerprint != null && info.agentFingerprint != agentFp) {
                throw PairingException("The laptop's key doesn't match the QR code. Pairing stopped — someone may be impersonating it.")
            }
            val device = Device(
                uuid = pairingId,
                name = json.optString("agent_name", "Laptop").take(64),
                ipAddress = info.host,
                port = info.port,
                deviceId = json.getString("device_id"),
                agentPubkey = agentPub,
                isVaultLaptop = makeVaultLaptop,
                lastSeen = System.currentTimeMillis(),
            )
            return PairingResult(device, uniffi.fortis_bindings.keyFingerprint(reqPub), uniffi.fortis_bindings.keyFingerprint(apprPub), agentFp)
        } catch (e: Exception) {
            DeviceKeys.delete(pairingId)
            if (e is PairingException) throw e
            throw PairingException("Can't reach ${info.host}:${info.port}. Is Tailscale on and fortis-agent running? (${e.message})")
        }
    }

    /** Fetch /capabilities with a signed request (also proves the keys work). */
    fun capabilities(device: Device): String =
        FortisApiClient.getClient().newCall(FortisApiClient.buildRequest("/api/v1/capabilities", device) { get() }).execute().use { r ->
            if (!r.isSuccessful) throw PairingException("Signed request failed (HTTP ${r.code}).")
            r.body?.string() ?: "{}"
        }
}
