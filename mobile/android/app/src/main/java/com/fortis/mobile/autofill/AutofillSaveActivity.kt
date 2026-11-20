package com.fortis.mobile.autofill

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.fortis.mobile.ui.theme.FortisMobileTheme
import com.fortis.mobile.vault.UserAuth
import com.fortis.mobile.vault.VaultRepository
import com.fortis.mobile.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** "Save to Fortis?" after a sign-in (security §6.5). Unlock, write, then sync to the vault laptop. */
class AutofillSaveActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val s = PendingSaves.take(intent.getStringExtra(EXTRA_TOKEN)) ?: return finish()
        val url = AutofillLogins.saveUrl(s.form) ?: return finish()
        val where = s.form.webUrl?.substringAfter("://")
            ?: runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(s.form.packageName, 0)).toString() }.getOrDefault(s.form.packageName)

        setContent {
            FortisMobileTheme {
                var status by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                com.fortis.mobile.ui.components.FrostBackground {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Save to Fortis?", style = MaterialTheme.typography.headlineSmall)
                        Text("Site/app: $where")
                        Text("Username: ${s.username.ifEmpty { "(none)" }}")
                        Text("Password: ${"•".repeat(s.password.length.coerceAtMost(12))}")
                        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { finish() }, enabled = !busy) { Text("Not now") }
                            Button(enabled = !busy, onClick = {
                                busy = true
                                lifecycleScope.launch {
                                    if (UserAuth.authenticate(this@AutofillSaveActivity, "Save to Fortis", where) != null) { busy = false; return@launch }
                                    status = withContext(Dispatchers.IO) { runCatching { save(s, url) }.getOrElse { "Couldn't save: ${it.message}" } }
                                    delay(1200); finish()
                                }
                            }) { Text("Save") }
                        }
                    }
                }
            }
        }
    }

    /** Create, or update the password of the login for the same target + username. */
    private fun save(s: PendingSave, url: String): String {
        val repo = VaultRepository.get(this)
        repo.unlockAfterAuth()
        try {
            val existing = AutofillLogins.find(this, s.form).firstOrNull { AutofillLogins.username(it) == s.username }
            val msg = if (existing != null) {
                if (AutofillLogins.password(existing) == s.password) return "Already saved."
                val input = JSONObject(existing.toString())
                input.getJSONObject("entry_type").getJSONObject("Login").put("password", s.password)
                repo.save(existing.getString("id"), input)
                "Password updated."
            } else {
                val title = s.form.webUrl?.substringAfter("://") ?: runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(s.form.packageName, 0)).toString()
                }.getOrDefault(s.form.packageName)
                val login = JSONObject().put("username", s.username).put("password", s.password)
                    .put("urls", JSONArray().put(url)).put("totp", JSONObject.NULL)
                repo.save(null, JSONObject().put("title", title).put("folder", JSONObject.NULL)
                    .put("entry_type", JSONObject().put("Login", login)).put("notes", ""))
                "Saved."
            }
            val synced = runCatching { repo.sync() }.isSuccess
            return if (synced) "$msg Synced to your laptop." else "$msg It will sync to your laptop next time."
        } finally {
            VaultSession.lock()
        }
    }

    companion object { const val EXTRA_TOKEN = "token" }
}
