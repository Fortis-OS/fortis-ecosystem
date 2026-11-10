package com.fortis.mobile

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fortis.mobile.data.Device
import com.fortis.mobile.keys.DeviceKeys
import com.fortis.mobile.service.EventFrames
import com.fortis.mobile.sudo.SudoRequests
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.fortis_bindings.canonicalApproval
import uniffi.fortis_bindings.canonicalSession
import uniffi.fortis_bindings.verifyAgentSignature
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

/**
 * The phone half of the sudo flow against a fixture made by fortis-core
 * (`phone_fixture sudo-frame`): agent frame verification, the exact bytes the
 * phone signs, and a Keystore signature over them that the Rust verifier accepts.
 */
@RunWith(AndroidJUnit4::class)
class SudoInteropTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val fx = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("sudo_fixture.json").bufferedReader().readText())
    private val pub = fx.getString("agent_pubkey")
    private val alias = "fortis.sudo-test.request"

    @After
    fun cleanup() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { if (it.containsAlias(alias)) it.deleteEntry(alias) }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun framesFromTheAgentVerifyOthersAreDropped() {
        val m = EventFrames.verify(pub, fx.getString("frame"))
        assertNotNull(m)
        assertEquals("sudo_request", m!!.getString("type"))
        assertNull("signed by another key", EventFrames.verify(pub, fx.getString("forged")))
        assertNull("msg changed after signing", EventFrames.verify(pub, fx.getString("tampered")))
        assertNull(EventFrames.verify(pub, "not json"))
        assertNull(EventFrames.verify(pub, """{"msg":"{}"}"""))
    }

    @Test
    fun phoneSignsExactlyWhatPamExpects() {
        val m = EventFrames.verify(pub, fx.getString("frame"))!!
        val req = EventFrames.approvalRequestJson(m) // strips type + expires_at
        assertEquals(fx.getString("approve_hex"), hex(canonicalApproval(req, "approve")))
        assertEquals(fx.getString("deny_hex"), hex(canonicalApproval(req, "deny")))

        // Sign with a Keystore P-256 key (same algorithm as the approval key, minus the
        // biometric gate, which an emulator test can't pass) and verify in Rust.
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
        val bytes = canonicalApproval(req, "approve")
        val sig = DeviceKeys.signRequest("sudo-test", bytes)
        val phonePub = DeviceKeys.publicKeyBase64(alias)
        assertTrue(verifyAgentSignature(phonePub, bytes, sig))
        // An approve signature can't be passed off as a deny (or vice versa).
        assertTrue(!verifyAgentSignature(phonePub, canonicalApproval(req, "deny"), sig))
    }

    @Test
    fun requestsAreTrackedAndCancelled() {
        val m = EventFrames.verify(pub, fx.getString("frame"))!!
        val laptop = Device(uuid = "sudo-test", name = "laptop", ipAddress = "100.64.0.2", port = 8023,
            deviceId = "d", agentPubkey = pub)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { SudoRequests.show(ctx, laptop, m) }
        val p = SudoRequests.pending.value[m.getString("request_id")]
        assertNotNull(p)
        assertEquals("pts/3", p!!.tty)
        assertTrue(p.deadlineMillis - System.currentTimeMillis() in 25_000..30_000)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { SudoRequests.cancel(ctx, p.requestId) }
        assertNull(SudoRequests.pending.value[p.requestId])
    }

    @Test
    fun inputSessionBytesMatchTheAgent() {
        val s = fx.getJSONObject("session")
        val b = canonicalSession(s.getString("session_id"), s.getString("nonce"), s.getLong("issued_at").toULong(), s.getString("kind"))
        assertEquals(s.getString("hex"), hex(b))
    }
}
