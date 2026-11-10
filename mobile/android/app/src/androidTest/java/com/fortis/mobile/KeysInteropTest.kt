package com.fortis.mobile

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fortis.mobile.keys.DeviceKeys
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.fortis_bindings.canonicalRequest
import uniffi.fortis_bindings.keyFingerprint
import uniffi.fortis_bindings.verifyAgentSignature
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

/**
 * Android Keystore signatures must verify with the Rust p256 code the agent
 * uses (fortis-bindings `verify_agent_signature` = fortis_core::sign::PublicKey::verify).
 */
@RunWith(AndroidJUnit4::class)
class KeysInteropTest {
    private val alias = "fortis.interop-test.request"

    @After
    fun cleanup() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.let { if (it.containsAlias(alias)) it.deleteEntry(alias) }
    }

    private fun requestKeyLikeDeviceKeys() {
        // Same parameters DeviceKeys uses for the request key (no user auth).
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        gen.generateKeyPair()
    }

    @Test
    fun keystoreSignatureVerifiesInRust() {
        requestKeyLikeDeviceKeys()
        val pub = DeviceKeys.publicKeyBase64(alias)
        assertEquals(65, Base64.decode(pub, Base64.NO_WRAP).size) // uncompressed SEC1
        assertEquals(16, keyFingerprint(pub).length)

        val body = """{"text":"héllo"}""".toByteArray()
        val msg = canonicalRequest("POST", "/api/v1/clipboard?x=a%2Fb", 1731110400uL, "00112233445566778899aabbccddeeff", body)
        val sig = java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as java.security.PrivateKey)
            update(msg); Base64.encodeToString(sign(), Base64.NO_WRAP)
        }
        assertTrue("Keystore signature must verify in Rust", verifyAgentSignature(pub, msg, sig))

        val tampered = canonicalRequest("POST", "/api/v1/clipboard?x=a%2Fb", 1731110400uL, "00112233445566778899aabbccddeeff", "{}".toByteArray())
        assertFalse(verifyAgentSignature(pub, tampered, sig))
    }

    @Test
    fun signRequestHelperProducesVerifiableSignatures() {
        // DeviceKeys.signRequest looks up "fortis.<id>.request".
        requestKeyLikeDeviceKeys()
        val msg = canonicalRequest("GET", "/api/v1/telemetry", 1uL, "aa", ByteArray(0))
        val sig = DeviceKeys.signRequest("interop-test", msg)
        assertTrue(verifyAgentSignature(DeviceKeys.publicKeyBase64(alias), msg, sig))
    }
}
