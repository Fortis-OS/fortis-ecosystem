package com.fortis.mobile.network

import androidx.fragment.app.FragmentActivity
import com.fortis.mobile.data.BrowserLockState
import com.fortis.mobile.data.Device
import com.fortis.mobile.keys.ApprovalSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import uniffi.fortis_bindings.canonicalSession

/**
 * Turn Fortis Lock off from the phone (security §7.8): a fingerprint-confirmed session of kind
 * `browser_unlock` (security §5.6). Confirming it unlocks at once; nothing stays open.
 */
object BrowserUnlock {
    class Error(message: String) : Exception(message)

    private val JSON = "application/json".toMediaType()

    private fun call(laptop: Device, path: String, body: JSONObject): JSONObject {
        val req = FortisApiClient.buildRequest(path, laptop) { post(body.toString().toRequestBody(JSON)) }
        FortisApiClient.getClient().newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (!r.isSuccessful) throw Error(o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() } ?: "HTTP ${r.code}")
            return o
        }
    }

    /** Shows a fingerprint prompt; returns the new (unlocked) state. */
    suspend fun unlock(activity: FragmentActivity, laptop: Device): BrowserLockState {
        val s = withContext(Dispatchers.IO) { call(laptop, "/api/v1/sessions", JSONObject().put("kind", "browser_unlock")) }
        val id = s.getString("session_id")
        require(id.matches(Regex("[0-9a-f]{32}"))) { "bad session id" }
        val bytes = canonicalSession(id, s.getString("nonce"), s.getLong("issued_at").toULong(), "browser_unlock")
        val sig = try {
            ApprovalSigner.sign(activity, laptop.uuid, "Unlock browser on ${laptop.name}", "Show the locked sites again", bytes)
        } catch (e: ApprovalSigner.Failure) {
            throw Error(e.message ?: "Cancelled")
        }
        val r = withContext(Dispatchers.IO) { call(laptop, "/api/v1/sessions/$id/confirm", JSONObject().put("signature", sig)) }
        return BrowserLockState.parse(r.optJSONObject("browser_lock") ?: JSONObject())
    }
}
