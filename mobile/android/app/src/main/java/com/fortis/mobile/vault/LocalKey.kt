package com.fortis.mobile.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone's local wrap key (docs/security §6.2): AES-256-GCM in the Android
 * Keystore, usable for 5 minutes after a strong biometric or the device
 * credential. It wraps the vault key so the master password is needed only once.
 */
object LocalKey {
    private const val ALIAS = "fortis.vault.local-wrap"
    const val VALIDITY_SECS = 300
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists(): Boolean = ks().containsAlias(ALIAS)

    fun delete() {
        if (exists()) ks().deleteEntry(ALIAS)
    }

    fun create() {
        delete()
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setUserAuthenticationParameters(VALIDITY_SECS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            b.setUserAuthenticationValidityDurationSeconds(VALIDITY_SECS)
        }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(b.build()) }.generateKey()
    }

    private fun key(): SecretKey = ks().getKey(ALIAS, null) as SecretKey? ?: throw IllegalStateException("local key missing")

    /** iv ‖ ciphertext. Needs a recent user authentication. */
    fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    /** Needs a recent user authentication. Throws KeyPermanentlyInvalidatedException after new biometric enrolment. */
    fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "bad blob" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_LEN))
        return c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }
}
