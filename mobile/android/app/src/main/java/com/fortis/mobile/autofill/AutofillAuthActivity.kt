package com.fortis.mobile.autofill

import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.fortis.mobile.R
import com.fortis.mobile.ui.theme.FortisMobileTheme
import com.fortis.mobile.vault.UserAuth
import com.fortis.mobile.vault.VaultRepository
import com.fortis.mobile.vault.VaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Unlock, then hand Android the matching logins as datasets (security §6.5). */
class AutofillAuthActivity : FragmentActivity() {
    private var message by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(RESULT_CANCELED)
        val form = PendingForms.peek(intent.getStringExtra(EXTRA_FORM)) ?: return finish()
        val where = form.webUrl?.substringAfter("://") ?: appLabel(form.packageName)
        setContent {
            FortisMobileTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(message ?: "Unlocking Fortis for $where…", style = MaterialTheme.typography.titleMedium)
                        if (message != null) { Spacer(Modifier.height(16.dp)); TextButton(onClick = { finish() }) { Text("Close") } }
                    }
                }
            }
        }
        lifecycleScope.launch {
            val err = UserAuth.authenticate(this@AutofillAuthActivity, "Fill with Fortis", where)
            if (err != null) return@launch finish()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val repo = VaultRepository.get(this@AutofillAuthActivity)
                    repo.unlockAfterAuth()
                    try { AutofillLogins.find(this@AutofillAuthActivity, form) } finally { VaultSession.lock() }
                }
            }
            val matches = result.getOrElse { message = it.message ?: "Couldn't open the vault"; return@launch }
            if (matches.isEmpty()) {
                message = "No login saved for $where.\nSave one by signing in normally — Fortis will offer to save it."
                return@launch
            }
            val response = FillResponse.Builder()
            for (e in matches) {
                val p = RemoteViews(packageName, R.layout.autofill_item).apply {
                    setTextViewText(R.id.autofill_title, e.optString("title"))
                    setTextViewText(R.id.autofill_subtitle, AutofillLogins.username(e))
                }
                val d = Dataset.Builder(p)
                form.username?.let { d.setValue(it, AutofillValue.forText(AutofillLogins.username(e))) }
                form.passwords.forEach { d.setValue(it, AutofillValue.forText(AutofillLogins.password(e))) }
                response.addDataset(d.build())
            }
            setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response.build()))
            finish()
        }
    }

    private fun appLabel(pkg: String) =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    companion object { const val EXTRA_FORM = "form" }
}
