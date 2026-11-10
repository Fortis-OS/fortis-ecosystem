package com.fortis.mobile.autofill

import android.content.Context
import com.fortis.mobile.vault.VaultRepository
import org.json.JSONObject
import uniffi.fortis_bindings.autofillSaveUrl
import uniffi.fortis_bindings.loginMatches

/** Matching logins from the phone vault, using the shared Rust rule (security §3.6). */
object AutofillLogins {
    fun urlsOf(entry: JSONObject): List<String> {
        val login = entry.optJSONObject("entry_type")?.optJSONObject("Login") ?: return emptyList()
        val a = login.optJSONArray("urls") ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    fun matches(entry: JSONObject, form: LoginForm): Boolean =
        entry.optJSONObject("entry_type")?.has("Login") == true &&
            runCatching { loginMatches(urlsOf(entry), form.webUrl, form.packageName.takeIf { form.webUrl == null }) }.getOrDefault(false)

    /** Must be unlocked. */
    fun find(context: Context, form: LoginForm): List<JSONObject> =
        VaultRepository.get(context).list().first.filter { matches(it, form) }

    fun saveUrl(form: LoginForm): String? =
        runCatching { autofillSaveUrl(form.webUrl, form.packageName.takeIf { form.webUrl == null }) }.getOrNull()

    fun username(entry: JSONObject): String =
        entry.optJSONObject("entry_type")?.optJSONObject("Login")?.optString("username").orEmpty()

    fun password(entry: JSONObject): String =
        entry.optJSONObject("entry_type")?.optJSONObject("Login")?.optString("password").orEmpty()
}
