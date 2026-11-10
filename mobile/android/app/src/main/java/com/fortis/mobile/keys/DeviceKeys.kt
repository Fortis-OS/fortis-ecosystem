package com.fortis.mobile.keys

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Per-pairing P-256 keys in the Android Keystore (docs/security §6.2).
 *
 * - request key: signs every request; usable while the phone is unlocked.
 * - approval key: signs sudo approvals and session confirmations; needs a
 *   strong biometric for every single use and is invalidated when new
 *   fingerprints are enrolled.
 *
 * Keys are non-exportable; StrongBox is used when the phone has it.
 */
object DeviceKeys {
    private const val STORE = "AndroidKeyStore"

    fun requestAlias(pairingId: String) = "fortis.$pairingId.request"
    fun approvalAlias(pairingId: String) = "fortis.$pairingId.approval"

    private fun keyStore(): KeyStore = KeyStore.getInstance(STORE).apply { load(null) }

    private fun spec(alias: String, biometric: Boolean, strongBox: Boolean): KeyGenParameterSpec {
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (biometric) {
            b.setUserAuthenticationRequired(true)
            b.setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 0 = every use needs its own BiometricPrompt + CryptoObject.
                b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
            // API 28–29: no validity duration set ⇒ per-use auth (security §6.1).
        }
        if (strongBox) b.setIsStrongBoxBacked(true)
        return b.build()
    }

    private fun generate(alias: String, biometric: Boolean) {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE)
        try {
            gen.initialize(spec(alias, biometric, strongBox = true))
            gen.generateKeyPair()
        } catch (e: StrongBoxUnavailableException) {
            gen.initialize(spec(alias, biometric, strongBox = false))
            gen.generateKeyPair()
        } catch (e: java.security.ProviderException) {
            // Some devices report StrongBox problems as ProviderException.
            gen.initialize(spec(alias, biometric, strongBox = false))
            gen.generateKeyPair()
        }
    }

    /** Create both keys for a new pairing. */
    fun create(pairingId: String) {
        delete(pairingId)
        generate(requestAlias(pairingId), biometric = false)
        generate(approvalAlias(pairingId), biometric = true)
    }

    fun delete(pairingId: String) {
        val ks = keyStore()
        for (a in listOf(requestAlias(pairingId), approvalAlias(pairingId))) {
            if (ks.containsAlias(a)) ks.deleteEntry(a)
        }
    }

    fun exists(pairingId: String): Boolean = keyStore().containsAlias(requestAlias(pairingId))

    /** Uncompressed SEC1 (0x04 ‖ X ‖ Y), base64 — the format the agent expects. */
    fun publicKeyBase64(alias: String): String {
        val pub = keyStore().getCertificate(alias).publicKey as ECPublicKey
        return Base64.encodeToString(sec1(pub), Base64.NO_WRAP)
    }

    fun sec1(pub: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + fixed32(pub.w.affineX) + fixed32(pub.w.affineY)

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size) // drop sign byte
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun privateKey(alias: String): PrivateKey =
        keyStore().getKey(alias, null) as PrivateKey? ?: throw IllegalStateException("missing key $alias")

    /** Sign with the request key (no user prompt). Returns base64 DER. */
    fun signRequest(pairingId: String, message: ByteArray): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(privateKey(requestAlias(pairingId)))
        s.update(message)
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }

    /**
     * A Signature initialised with the approval key, to be unlocked by
     * BiometricPrompt(CryptoObject). Throws KeyPermanentlyInvalidatedException
     * if fingerprints changed since pairing.
     */
    fun approvalSignature(pairingId: String): Signature =
        Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey(approvalAlias(pairingId))) }
}
