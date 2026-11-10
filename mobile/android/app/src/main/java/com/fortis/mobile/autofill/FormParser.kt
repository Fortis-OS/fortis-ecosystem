package com.fortis.mobile.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId

/**
 * Finds login fields in a screen (security §6.5): autofill hints, HTML
 * `autocomplete` / `type`, and input type. A username is also the last text
 * field directly before the first password field. No guessing from labels.
 */
data class LoginForm(
    val packageName: String,
    /** "https://<webDomain>" when the form is in a browser, else null. */
    val webUrl: String?,
    val username: AutofillId?,
    val passwords: List<AutofillId>,
    val usernameValue: String?,
    val passwordValue: String?,
) {
    val fillIds: List<AutofillId> get() = listOfNotNull(username) + passwords
    val isEmpty: Boolean get() = username == null && passwords.isEmpty()
}

object FormParser {
    private enum class Kind { Username, Password, Text, Other }

    private class Field(val id: AutofillId, val kind: Kind, val value: String?)

    fun parse(structure: AssistStructure): LoginForm {
        val fields = mutableListOf<Field>()
        var webDomain: String? = null
        var webScheme: String? = null
        fun walk(n: AssistStructure.ViewNode) {
            if (webDomain == null && !n.webDomain.isNullOrEmpty()) {
                webDomain = n.webDomain
                webScheme = n.webScheme
            }
            val id = n.autofillId
            if (id != null && n.visibility == View.VISIBLE && n.autofillType == View.AUTOFILL_TYPE_TEXT) {
                val k = kindOf(n)
                if (k != Kind.Other) fields += Field(id, k, n.autofillValue?.takeIf { it.isText }?.textValue?.toString())
            }
            for (i in 0 until n.childCount) walk(n.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode)

        val passwords = fields.filter { it.kind == Kind.Password }
        val user = fields.firstOrNull { it.kind == Kind.Username }
            ?: passwords.firstOrNull()?.let { first ->
                // The text field right before the first password field.
                fields.subList(0, fields.indexOf(first)).lastOrNull { it.kind == Kind.Text }
            }
        val pkg = structure.activityComponent?.packageName.orEmpty()
        val url = webDomain?.let { d -> "${(webScheme ?: "https").lowercase()}://$d" }
        return LoginForm(pkg, url, user?.id, passwords.map { it.id }, user?.value, passwords.firstOrNull()?.value)
    }

    private fun kindOf(n: AssistStructure.ViewNode): Kind {
        val hints = n.autofillHints.orEmpty().map { it.lowercase() }
        if (hints.any { it.contains("password") }) return Kind.Password
        if (hints.any { it == View.AUTOFILL_HINT_USERNAME.lowercase() || it == View.AUTOFILL_HINT_EMAIL_ADDRESS.lowercase() || it == "email" || it == "username" }) return Kind.Username

        n.htmlInfo?.let { h ->
            if (!h.tag.equals("input", ignoreCase = true)) return Kind.Other
            val attrs = h.attributes.orEmpty().associate { it.first.lowercase() to (it.second ?: "").lowercase() }
            val type = attrs["type"] ?: "text"
            val ac = attrs["autocomplete"].orEmpty()
            return when {
                type == "password" || ac.contains("current-password") || ac.contains("new-password") -> Kind.Password
                type == "hidden" || type == "submit" || type == "checkbox" || type == "radio" || type == "button" -> Kind.Other
                ac.contains("username") || ac.contains("email") || type == "email" -> Kind.Username
                type == "text" || type == "tel" -> Kind.Text
                else -> Kind.Other
            }
        }

        val t = n.inputType
        if (t and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
            val v = t and InputType.TYPE_MASK_VARIATION
            return when (v) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> Kind.Password
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> Kind.Username
                else -> Kind.Text
            }
        }
        return Kind.Other
    }
}
