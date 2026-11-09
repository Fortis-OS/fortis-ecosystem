package com.fortis.mobile.encrypt

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.security.Signature

class BiometricPromptActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val payloadHex = intent.getStringExtra("payload") ?: ""
        
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    finishWithResult(false, null)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    
                    val signatureObject = result.cryptoObject?.signature
                    if (signatureObject != null && payloadHex.isNotEmpty()) {
                        val payloadBytes = payloadHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        signatureObject.update(payloadBytes)
                        val sigBytes = signatureObject.sign()
                        val sigHex = sigBytes.joinToString("") { "%02x".format(it) }
                        
                        finishWithResult(true, sigHex)
                    } else {
                        finishWithResult(false, null)
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.d("Biometric", "Auth failed")
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sudo Authentication")
            .setSubtitle("Authorize Linux command execution")
            .setNegativeButtonText("Cancel")
            .build()

        try {
            val signature = FortisKeyManager.getSignatureObject()
            val cryptoObject = BiometricPrompt.CryptoObject(signature)
            biometricPrompt.authenticate(promptInfo, cryptoObject)
        } catch (e: Exception) {
            e.printStackTrace()
            finishWithResult(false, null)
        }
    }
    
    private fun finishWithResult(success: Boolean, signature: String?) {
        val resultIntent = Intent()
        resultIntent.putExtra("success", success)
        resultIntent.putExtra("signature", signature)
        setResult(RESULT_OK, resultIntent)
        finish()
    }
}
