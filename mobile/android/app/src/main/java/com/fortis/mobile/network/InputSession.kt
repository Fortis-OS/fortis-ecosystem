package com.fortis.mobile.network

import androidx.fragment.app.FragmentActivity
import com.fortis.mobile.data.Device
import com.fortis.mobile.keys.ApprovalSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import uniffi.fortis_bindings.canonicalSession

/** Fingerprint-confirmed session for network remote input (security §5.6). */
object InputSession {
    class Error(message: String) : Exception(message)

    private val JSON = "application/json".toMediaType()

    private fun call(laptop: Device, path: String, method: String, body: JSONObject?): JSONObject {
        val req = FortisApiClient.buildRequest(path, laptop) {
            method(method, body?.toString()?.toRequestBody(JSON))
        }
        FortisApiClient.getClient().newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (!r.isSuccessful) throw Error(o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() } ?: "HTTP ${r.code}")
            return o
        }
    }

    /** Create + confirm; returns the session id. Shows a fingerprint prompt. */
    suspend fun start(activity: FragmentActivity, laptop: Device): String {
        val s = withContext(Dispatchers.IO) { call(laptop, "/api/v1/sessions", "POST", JSONObject().put("kind", "input")) }
        val id = s.getString("session_id")
        require(id.matches(Regex("[0-9a-f]{32}"))) { "bad session id" }
        val bytes = canonicalSession(id, s.getString("nonce"), s.getLong("issued_at").toULong(), "input")
        val sig = try {
            ApprovalSigner.sign(activity, laptop.uuid, "Control ${laptop.name}", "Keyboard and mouse, up to 30 min", bytes)
        } catch (e: ApprovalSigner.Failure) {
            throw Error(e.message ?: "Cancelled")
        }
        withContext(Dispatchers.IO) { call(laptop, "/api/v1/sessions/$id/confirm", "POST", JSONObject().put("signature", sig)) }
        return id
    }

    suspend fun end(laptop: Device, id: String) = withContext(Dispatchers.IO) {
        runCatching { call(laptop, "/api/v1/sessions/$id", "DELETE", null) }
    }
}
