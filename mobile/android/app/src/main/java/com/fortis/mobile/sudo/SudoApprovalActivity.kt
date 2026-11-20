package com.fortis.mobile.sudo

import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.fortis.mobile.data.DeviceRepository
import com.fortis.mobile.keys.DeviceKeys
import com.fortis.mobile.network.FortisApiClient
import com.fortis.mobile.ui.theme.FortisMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import uniffi.fortis_bindings.canonicalApproval

/**
 * Full-screen "Approve sudo?" prompt (docs/security §4.3 steps 6–8).
 * Approve and Deny both sign with the approval key, which only BiometricPrompt
 * can unlock (per use). Ignore signs nothing; the laptop times out to the password.
 */
class SudoApprovalActivity : FragmentActivity() {
    private var requestId by mutableStateOf<String?>(null)
    private var status by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) // no screenshots / recents thumbnail
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        setContent { FortisMobileTheme { Screen() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        requestId = intent.getStringExtra(EXTRA_REQUEST_ID); status = null; busy = false
    }

    @Composable
    private fun Screen() {
        val all by SudoRequests.pending.collectAsState()
        val p = requestId?.let { all[it] }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
        LaunchedEffect(p == null, status) { if (p == null && status == null) { delay(300); finish() } }

        com.fortis.mobile.ui.components.FrostBackground {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (p == null) {
                    Text(status ?: "This request is no longer waiting.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { finish() }) { Text("Close") }
                    return@Column
                }
                Text("Approve sudo?", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row2("User", p.user)
                        Row2("Laptop", if (p.host == p.laptopName) p.host else "${p.host} (paired as ${p.laptopName})")
                        Row2("Service", p.service)
                        Row2("Terminal", p.tty)
                    }
                }
                Spacer(Modifier.height(12.dp))
                val left = ((p.deadlineMillis - now) / 1000).coerceAtLeast(0)
                Text("Expires in ${left}s · only approve if you just ran sudo", style = MaterialTheme.typography.bodySmall)
                status?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { answer(p, "deny") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Deny") }
                    Button(onClick = { answer(p, "approve") }, enabled = !busy, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors()) { Text("Approve") }
                }
                TextButton(onClick = { finish() }, enabled = !busy) { Text("Ignore (use password)") }
            }
        }
    }

    @Composable
    private fun Row2(k: String, v: String) = Row(Modifier.fillMaxWidth()) {
        Text(k, Modifier.weight(0.35f), style = MaterialTheme.typography.labelLarge)
        Text(v, Modifier.weight(0.65f))
    }

    private fun answer(p: PendingSudo, decision: String) {
        val laptop = DeviceRepository.get(this).get(p.laptopUuid) ?: run { status = "This laptop is no longer paired."; return }
        val sig = try {
            DeviceKeys.approvalSignature(laptop.uuid)
        } catch (e: KeyPermanentlyInvalidatedException) {
            status = "Fingerprints changed since pairing. Forget and re-pair ${laptop.name} to approve again."
            return
        } catch (e: Exception) {
            status = "Approval key unavailable: ${e.message}"
            return
        }
        busy = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val s = result.cryptoObject?.signature ?: run { busy = false; return }
                val signature = runCatching {
                    s.update(canonicalApproval(p.requestJson, decision))
                    Base64.encodeToString(s.sign(), Base64.NO_WRAP)
                }.getOrElse { busy = false; status = "Signing failed: ${it.message}"; return }
                send(p, laptop, decision, signature)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { busy = false; if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_USER_CANCELED) status = errString.toString() }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (decision == "approve") "Approve sudo on ${p.host}" else "Deny sudo on ${p.host}")
            .setSubtitle("${p.user} · ${p.tty}")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(sig))
    }

    private fun send(p: PendingSudo, laptop: com.fortis.mobile.data.Device, decision: String, signature: String) {
        lifecycleScope.launch {
            val body = JSONObject().put("request_id", p.requestId).put("decision", decision).put("signature", signature)
                .toString().toRequestBody("application/json".toMediaType())
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    FortisApiClient.getClient().newCall(FortisApiClient.buildRequest("/api/v1/auth/respond", laptop) { post(body) }).execute().use { r ->
                        if (r.isSuccessful) null
                        else runCatching { JSONObject(r.body!!.string()).getJSONObject("error").getString("message") }.getOrDefault("HTTP ${r.code}")
                    }
                }.getOrElse { "Couldn't reach ${laptop.name}: ${it.message}" }
            }
            busy = false
            if (result == null) {
                status = if (decision == "approve") "Approved." else "Denied — the laptop will ask for the password."
                SudoRequests.cancel(this@SudoApprovalActivity, p.requestId)
                delay(900); finish()
            } else status = result
        }
    }

    companion object { const val EXTRA_REQUEST_ID = "request_id" }
}
