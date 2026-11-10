package com.fortis.mobile

import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fortis.mobile.vault.VaultRepository
import com.fortis.mobile.vault.VaultRepository.Companion.innerOf
import com.fortis.mobile.vault.VaultSession
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.fortis_bindings.deriveWrapKey
import uniffi.fortis_bindings.unwrapVaultKey

/**
 * Round trip with a real fortis-core vault (assets/vault_fixture.json, made by
 * fortis-core's `phone_fixture` example): open it with the master password,
 * read entries, then edit/add/delete with the phone code. The resulting push
 * is logged under FORTIS_FIXTURE and checked on the host with the agent's own
 * push rules (`phone_fixture push`).
 */
@RunWith(AndroidJUnit4::class)
class VaultInteropTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = VaultRepository.get(ctx)

    @After fun cleanup() = repo.reset()

    @Test
    fun openEditAndProduceAgentCompatibleChanges() {
        repo.reset()
        val fixture = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("vault_fixture.json").reader().readText())
        val boot = fixture.getJSONObject("bootstrap")
        val meta = boot.getJSONObject("meta")
        val wrapped = Base64.decode(boot.getString("wrapped_vault_key"), Base64.DEFAULT)

        // Wrong password must fail; the right one opens the vault key.
        try {
            unwrapVaultKey(deriveWrapKey("wrong password!!", meta.toString()), wrapped, meta.getString("vault_id"))
            fail("wrong password accepted")
        } catch (e: Exception) { /* expected */ }
        val vk = unwrapVaultKey(deriveWrapKey("fixture master password", meta.toString()), wrapped, meta.getString("vault_id"))
        VaultSession.set(vk)

        val rows = fixture.getJSONObject("pull").getJSONArray("rows")
        for (i in 0 until rows.length()) assertTrue(repo.applyPulled(rows.getJSONObject(i)))
        val (entries, bad) = repo.list()
        assertEquals(0, bad)
        assertEquals(listOf("GitHub", "Wifi"), entries.map { it.getString("title") })

        val github = entries[0]
        assertEquals("hunter2", innerOf(github)!!.getString("password"))
        val code = repo.totp(github)!!
        assertEquals(6, code.first.length)

        // Edit, add and delete exactly as the UI does.
        val login = innerOf(github)!!.put("password", "changed-on-phone")
        repo.save(github.getString("id"), JSONObject().put("title", "GitHub (phone)").put("folder", "Work").put("notes", "edited")
            .put("entry_type", JSONObject().put("Login", login)))
        repo.save(null, JSONObject().put("title", "Phone note").put("folder", "").put("notes", "")
            .put("entry_type", JSONObject().put("SecureNote", JSONObject().put("content", "made on the phone"))))
        repo.delete(entries[1].getString("id"))

        val changes = repo.pendingChanges()
        assertEquals(3, changes.length())
        Log.i("FORTIS_FIXTURE", changes.toString())
    }
}
