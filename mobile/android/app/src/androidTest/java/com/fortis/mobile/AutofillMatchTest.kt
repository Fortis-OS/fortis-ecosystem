package com.fortis.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fortis.mobile.autofill.AutofillLogins
import com.fortis.mobile.autofill.LoginForm
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Phone autofill uses the shared Rust rule (security §3.6) through fortis-bindings. */
@RunWith(AndroidJUnit4::class)
class AutofillMatchTest {
    private fun login(vararg urls: String) = JSONObject().put("title", "t").put("entry_type",
        JSONObject().put("Login", JSONObject().put("username", "me").put("password", "pw").put("urls", JSONArray(urls.toList()))))

    private fun form(pkg: String, web: String?) = LoginForm(pkg, web, null, emptyList(), null, null)

    @Test fun webDomainRules() {
        val gh = login("https://github.com", "androidapp://com.github.android")
        assertTrue(AutofillLogins.matches(gh, form("com.android.chrome", "https://github.com")))
        assertTrue(AutofillLogins.matches(gh, form("com.android.chrome", "https://gist.github.com")))
        assertFalse(AutofillLogins.matches(gh, form("com.android.chrome", "https://github.com.evil.io")))
        assertFalse(AutofillLogins.matches(gh, form("com.android.chrome", "http://github.com")))
    }

    @Test fun webDomainWinsOverPackage() {
        // A browser showing evil.io can't get the GitHub app login by package name.
        val app = login("androidapp://com.android.chrome")
        assertFalse(AutofillLogins.matches(app, form("com.android.chrome", "https://evil.io")))
        assertTrue(AutofillLogins.matches(app, form("com.android.chrome", null)))
        assertFalse(AutofillLogins.matches(app, form("com.android.chrome.evil", null)))
    }

    @Test fun onlyLogins() {
        val note = JSONObject().put("title", "github.com").put("entry_type", JSONObject().put("SecureNote", JSONObject().put("content", "x")))
        assertFalse(AutofillLogins.matches(note, form("com.android.chrome", "https://github.com")))
    }

    @Test fun saveUrls() {
        assertEquals("https://github.com", AutofillLogins.saveUrl(form("com.android.chrome", "https://github.com")))
        assertEquals("androidapp://com.example", AutofillLogins.saveUrl(form("com.example", null)))
        assertNull(AutofillLogins.saveUrl(form("com.android.chrome", "http://plain.com")))
    }
}
