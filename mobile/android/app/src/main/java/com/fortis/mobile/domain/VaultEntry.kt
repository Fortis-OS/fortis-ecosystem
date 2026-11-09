package com.fortis.mobile.domain

import org.json.JSONObject

data class VaultEntry(
    val id: String,
    val title: String,
    val entryType: String,
    val username: String? = null,
    val totpSecret: String? = null,
    val content: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): VaultEntry {
            val id = json.optString("id")
            val title = json.optString("title")
            val entryTypeObj = json.optJSONObject("entry_type")
            
            var entryType = "Unknown"
            var username: String? = null
            var totpSecret: String? = null
            var content: String? = null

            if (entryTypeObj != null) {
                if (entryTypeObj.has("Login")) {
                    entryType = "Login"
                    val loginObj = entryTypeObj.getJSONObject("Login")
                    username = if (loginObj.isNull("username")) null else loginObj.optString("username", null)
                    totpSecret = if (loginObj.isNull("totp_secret")) null else loginObj.optString("totp_secret", null)
                } else if (entryTypeObj.has("SecureNote")) {
                    entryType = "SecureNote"
                    val noteObj = entryTypeObj.getJSONObject("SecureNote")
                    content = if (noteObj.isNull("content")) null else noteObj.optString("content", null)
                } else if (entryTypeObj.has("CreditCard")) {
                    entryType = "CreditCard"
                }
            }

            return VaultEntry(
                id = id,
                title = title,
                entryType = entryType,
                username = username,
                totpSecret = totpSecret,
                content = content
            )
        }
    }
}
