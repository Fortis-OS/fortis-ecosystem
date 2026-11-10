package com.fortis.mobile.service

import org.json.JSONObject
import uniffi.fortis_bindings.verifyAgentSignature

/**
 * Agent → phone frames are `{"msg": "<json string>", "agent_sig": "<b64 DER>"}`
 * (docs/api §4). The signature covers the exact bytes of `msg` and is checked
 * against the agent key pinned at pairing; anything else is dropped.
 */
object EventFrames {
    /** The verified message, or null if the frame is malformed or not signed by [agentPubkey]. */
    fun verify(agentPubkey: String, frame: String): JSONObject? {
        val o = runCatching { JSONObject(frame) }.getOrNull() ?: return null
        val msg = o.optString("msg").takeIf { it.isNotEmpty() } ?: return null
        val sig = o.optString("agent_sig").takeIf { it.isNotEmpty() } ?: return null
        if (!runCatching { verifyAgentSignature(agentPubkey, msg.toByteArray(Charsets.UTF_8), sig) }.getOrDefault(false)) return null
        return runCatching { JSONObject(msg) }.getOrNull()
    }

    /**
     * The exact ApprovalRequest the phone signs (security §4.4): only these
     * seven fields — the Rust side rejects unknown fields.
     */
    fun approvalRequestJson(sudoRequest: JSONObject): String = JSONObject()
        .put("request_id", sudoRequest.getString("request_id"))
        .put("nonce", sudoRequest.getString("nonce"))
        .put("issued_at", sudoRequest.getLong("issued_at"))
        .put("user", sudoRequest.getString("user"))
        .put("host", sudoRequest.getString("host"))
        .put("service", sudoRequest.getString("service"))
        .put("tty", sudoRequest.getString("tty"))
        .toString()
}
