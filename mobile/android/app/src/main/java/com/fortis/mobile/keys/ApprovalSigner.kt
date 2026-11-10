package com.fortis.mobile.keys

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Signs [message] with a pairing's approval key, which only a fresh fingerprint unlocks (security §6.2). */
object ApprovalSigner {
    class Failure(message: String) : Exception(message)

    /** Base64 DER signature, or throws [Failure] with a message for the user. */
    suspend fun sign(activity: FragmentActivity, pairingId: String, title: String, subtitle: String, message: ByteArray): String {
        val sig = try {
            DeviceKeys.approvalSignature(pairingId)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw Failure("Fingerprints changed since pairing. Forget and re-pair this laptop.")
        } catch (e: Exception) {
            throw Failure("Approval key unavailable: ${e.message}")
        }
        val unlocked = suspendCancellableCoroutine<java.security.Signature?> { cont ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(result.cryptoObject?.signature)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (cont.isActive) cont.resume(null)
                }
            })
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle)
                    .setAllowedAuthenticators(BIOMETRIC_STRONG).setNegativeButtonText("Cancel").build(),
                BiometricPrompt.CryptoObject(sig),
            )
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        } ?: throw Failure("Cancelled")
        unlocked.update(message)
        return Base64.encodeToString(unlocked.sign(), Base64.NO_WRAP)
    }
}
