package com.fortis.mobile.autofill

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import com.fortis.mobile.ui.components.ErrorLine
import com.fortis.mobile.ui.components.FortisActionButton
import com.fortis.mobile.ui.components.FortisCaption
import com.fortis.mobile.ui.components.FortisCard
import com.fortis.mobile.ui.theme.Frost
import com.fortis.mobile.ui.theme.FrostMono
import com.fortis.mobile.ui.theme.FrostSans
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
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("Save to Fortis?", fontFamily = FrostSans, fontSize = 28.sp, color = Frost.text)
                        Spacer(Modifier.height(16.dp))
                        FortisCard {
                            FortisCaption("Site or app")
                            Text(where, fontFamily = FrostMono, fontSize = 15.sp, color = Frost.text, modifier = Modifier.padding(bottom = 10.dp))
                            FortisCaption("Username")
                            Text(s.username.ifEmpty { "(none)" }, fontFamily = FrostMono, fontSize = 15.sp, color = Frost.text, modifier = Modifier.padding(bottom = 10.dp))
                            FortisCaption("Password")
                            Text("•".repeat(s.password.length.coerceAtMost(12)), fontFamily = FrostMono, fontSize = 15.sp, color = Frost.text)
                        }
                        status?.let {
                            if (it.startsWith("Couldn't")) ErrorLine(it, Modifier.padding(top = 10.dp))
                            else Text(it, fontFamily = FrostSans, color = Frost.ok, modifier = Modifier.padding(top = 10.dp))
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FortisActionButton(text = "Not now", onClick = { finish() }, enabled = !busy, modifier = Modifier.weight(1f))
                            FortisActionButton(text = "Save", isPrimary = true, isLoading = busy, enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                                busy = true
                                lifecycleScope.launch {
                                    if (UserAuth.authenticate(this@AutofillSaveActivity, "Save to Fortis", where) != null) { busy = false; return@launch }
                                    status = withContext(Dispatchers.IO) { runCatching { save(s, url) }.getOrElse { "Couldn't save: ${it.message}" } }
                                    delay(1200); finish()
                                }
                            })
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
