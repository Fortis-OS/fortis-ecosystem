package com.fortis.mobile.encrypt

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature

object FortisKeyManager {
    private const val KEY_ALIAS = "fortis_biometric_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    fun generateKeyIfNotExists() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE
            )

            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("ed25519"))
                .setDigests(KeyProperties.DIGEST_NONE)
                .setUserAuthenticationRequired(true) // Require biometrics
                .build()

            keyPairGenerator.initialize(parameterSpec)
            keyPairGenerator.generateKeyPair()
        }
    }

    fun getPublicKeyHex(): String {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
            ?: return ""
        
        // Convert to raw bytes if needed, but for simplicity returning encoded
        return publicKey.encoded.joinToString("") { "%02x".format(it) }
    }

    fun getSignatureObject(): Signature {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey
        
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(privateKey)
        return signature
    }
}
