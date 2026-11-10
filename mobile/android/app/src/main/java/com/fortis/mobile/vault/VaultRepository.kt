package com.fortis.mobile.vault

import android.content.Context
import android.util.Base64
import com.fortis.mobile.data.Device
import com.fortis.mobile.data.DeviceRepository
import com.fortis.mobile.network.FortisApiClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import uniffi.fortis_bindings.decryptEntry
import uniffi.fortis_bindings.deriveWrapKey
import uniffi.fortis_bindings.encryptEntry
import uniffi.fortis_bindings.generateTotp
import uniffi.fortis_bindings.totpRemainingSecs
import uniffi.fortis_bindings.unwrapVaultKey
import java.util.UUID

class VaultError(message: String) : Exception(message)

/** A vault key checked against the master password, waiting for a user auth to be stored. */
class PendingSetup internal constructor(internal val vaultKey: ByteArray, internal val vaultId: String, internal val laptopUuid: String) {
    /** Wipe the key if setup is abandoned (e.g. the user cancels the prompt). */
    fun discard() = vaultKey.fill(0)
}

data class SyncResult(val pulled: Int, val pushed: Int, val conflicts: Int)

/**
 * The phone vault (docs/security §6.3, docs/api §2). All blocking — call from
 * a background dispatcher.
 */
class VaultRepository private constructor(private val context: Context) {
    private val store = VaultStore(context)

    enum class State { NoVaultLaptop, NotSetUp, Locked, Unlocked }

    fun vaultLaptop(): Device? = DeviceRepository.get(context).devices.value.find { it.isVaultLaptop }

    fun state(): State {
        val laptop = vaultLaptop() ?: return State.NoVaultLaptop
        if (store.meta(K_LAPTOP) != laptop.uuid || store.meta(K_WRAPPED) == null || !LocalKey.exists()) return State.NotSetUp
        return if (VaultSession.unlocked.value) State.Unlocked else State.Locked
    }

    // ---- setup / unlock -----------------------------------------------------

    /**
     * Fetch the wrapped vault key from the vault laptop and open it with the
     * master password (Argon2id + HKDF in Rust). The laptop vault must be unlocked.
     */
    fun verifyPassword(password: CharArray): PendingSetup {
        val laptop = vaultLaptop() ?: throw VaultError("Choose a vault laptop first.")
        val b = call(laptop, "/api/v1/sync/bootstrap") { get() }
        val meta = b.getJSONObject("meta")
        val vaultId = meta.getString("vault_id")
        val wrapped = Base64.decode(b.getString("wrapped_vault_key"), Base64.DEFAULT)
        val pw = String(password)
        password.fill('\u0000')
        val wrapKey = try { deriveWrapKey(pw, meta.toString()) } catch (e: Exception) { throw VaultError("Unexpected vault format from the laptop.") }
        try {
            val vk = try { unwrapVaultKey(wrapKey, wrapped, vaultId) } catch (e: Exception) { throw VaultError("Wrong master password.") }
            return PendingSetup(vk, vaultId, laptop.uuid)
        } finally {
            wrapKey.fill(0)
        }
    }

    /** Create the local key (before prompting the user). */
    fun prepareLocalKey() = LocalKey.create()

    /** After a successful user authentication: store the vault key wrapped by the local key and unlock. */
    fun finishSetup(p: PendingSetup) {
        if (store.meta(K_VAULT_ID) != p.vaultId || store.meta(K_LAPTOP) != p.laptopUuid) store.wipe()
        store.setMeta(K_WRAPPED, Base64.encodeToString(LocalKey.wrap(p.vaultKey), Base64.NO_WRAP))
        store.setMeta(K_VAULT_ID, p.vaultId)
        store.setMeta(K_LAPTOP, p.laptopUuid)
        if (store.meta(K_SEQ) == null) store.setMeta(K_SEQ, "0")
        VaultSession.set(p.vaultKey) // zeroes p.vaultKey
    }

    /** After a successful user authentication: unwrap the stored vault key. */
    fun unlockAfterAuth() {
        val blob = Base64.decode(store.meta(K_WRAPPED) ?: throw VaultError("Not set up"), Base64.DEFAULT)
        try {
            VaultSession.set(LocalKey.unwrap(blob))
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            // New fingerprints were enrolled: the master password is needed again.
            LocalKey.delete()
            throw VaultError("Your screen lock or fingerprints changed. Enter the master password again.")
        }
    }

    fun lock() = VaultSession.lock()

    /** Forget the phone vault entirely (keeps nothing; the laptop copy is untouched). */
    fun reset() {
        VaultSession.lock()
        store.wipe()
        LocalKey.delete()
    }

    // ---- entries ------------------------------------------------------------

    private fun decrypt(r: SyncRow): JSONObject? = VaultSession.withKey { k ->
        runCatching { JSONObject(decryptEntry(k, r.id, r.version.toULong(), r.data)) }.getOrNull()
    }

    /** All live entries, sorted by title. Rows that fail to decrypt are skipped and counted. */
    fun list(): Pair<List<JSONObject>, Int> {
        var bad = 0
        val out = store.all().mapNotNull { decrypt(it.row) ?: run { bad++; null } }
        return out.sortedBy { it.optString("title").lowercase() } to bad
    }

    fun get(id: String): JSONObject? = store.get(id)?.takeIf { !it.row.deleted }?.let { decrypt(it.row) }

    /**
     * Save an entry. [input] has `title`, `folder`, `entry_type`, `notes` (and
     * optionally `custom_fields`); id and timestamps are set here.
     */
    fun save(id: String?, input: JSONObject): String {
        val now = System.currentTimeMillis() / 1000
        val existingRow = id?.let { store.get(it) }
        val existing = existingRow?.let { decrypt(it.row) }
        val entryId = id ?: UUID.randomUUID().toString()
        val entry = JSONObject()
            .put("id", entryId)
            .put("folder", input.opt("folder").takeUnless { it == "" } ?: JSONObject.NULL)
            .put("title", input.getString("title"))
            .put("entry_type", input.getJSONObject("entry_type"))
            .put("custom_fields", input.optJSONArray("custom_fields") ?: existing?.optJSONArray("custom_fields") ?: JSONArray())
            .put("notes", input.optString("notes", ""))
            .put("created_at", existing?.optLong("created_at") ?: now)
            .put("updated_at", now)
        val (version, base) = SyncLogic.nextVersion(existingRow)
        val data = VaultSession.withKey { k ->
            try { encryptEntry(k, entryId, version.toULong(), entry.toString()) } catch (e: Exception) { throw VaultError("Invalid entry: ${e.message}") }
        }
        store.put(LocalRow(SyncRow(entryId, version, now, false, data), dirty = true, baseVersion = base))
        return entryId
    }

    fun delete(id: String) {
        val existing = store.get(id) ?: return
        val (version, base) = SyncLogic.nextVersion(existing)
        val now = System.currentTimeMillis() / 1000
        store.put(LocalRow(SyncRow(id, version, now, true, ByteArray(0)), dirty = true, baseVersion = base))
    }

    /** Current code and seconds left, or null if the entry has no TOTP. */
    fun totp(entry: JSONObject): Pair<String, Long>? {
        val inner = innerOf(entry)
        val cfg = inner?.optJSONObject("totp") ?: return null
        val now = (System.currentTimeMillis() / 1000).toULong()
        return runCatching { generateTotp(cfg.toString(), now) to totpRemainingSecs(cfg.optInt("period", 30).toUInt(), now).toLong() }.getOrNull()
    }

    // ---- sync ---------------------------------------------------------------

    fun sync(): SyncResult {
        val laptop = vaultLaptop() ?: throw VaultError("No vault laptop.")
        VaultSession.withKey { } // must be unlocked
        var pulled = 0
        var since = store.meta(K_SEQ)?.toLongOrNull() ?: 0
        while (true) {
            val r = call(laptop, "/api/v1/sync/pull?since=$since") { get() }
            val rows = r.getJSONArray("rows")
            for (i in 0 until rows.length()) {
                val s = parseRow(rows.getJSONObject(i))
                if (apply(s)) pulled++
            }
            since = r.getLong("seq")
            store.setMeta(K_SEQ, since.toString())
            if (rows.length() < 500) break
        }

        var pushed = 0
        var conflicts = 0
        repeat(3) {
            val dirty = store.dirty()
            if (dirty.isEmpty()) return SyncResult(pulled, pushed, conflicts)
            for (batch in dirty.chunked(500)) {
                val changes = changesJson(batch)
                val res = call(laptop, "/api/v1/sync/push") {
                    post(JSONObject().put("changes", changes).toString().toRequestBody("application/json".toMediaType()))
                }
                val accepted = res.getJSONArray("accepted")
                for (i in 0 until accepted.length()) {
                    val id = accepted.getString(i)
                    store.get(id)?.let { store.put(LocalRow(it.row, dirty = false, baseVersion = it.row.version)) }
                    pushed++
                }
                val confl = res.getJSONArray("conflicts")
                for (i in 0 until confl.length()) {
                    conflicts++
                    apply(parseRow(confl.getJSONObject(i)))
                }
            }
        }
        return SyncResult(pulled, pushed, conflicts)
    }

    /** The `changes` array for POST /sync/push (docs/api §2). */
    private fun changesJson(rows: List<LocalRow>): JSONArray = JSONArray().also { a ->
        rows.forEach { l ->
            a.put(JSONObject()
                .put("id", l.row.id).put("base_version", l.baseVersion).put("version", l.row.version)
                .put("updated_at", l.row.updatedAt).put("deleted", l.row.deleted)
                .put("encrypted_data", Base64.encodeToString(l.row.data, Base64.NO_WRAP)))
        }
    }

    /** Test hooks: exactly what sync() sends / applies. */
    internal fun pendingChanges(): JSONArray = changesJson(store.dirty())
    internal fun applyPulled(o: JSONObject): Boolean = apply(parseRow(o))

    /** Apply one row from the laptop; returns true if the local store changed. */
    private fun apply(s: SyncRow): Boolean {
        val local = store.get(s.id)
        return when (val r = SyncLogic.resolve(local, s)) {
            Resolution.TakeServer -> { store.put(LocalRow(s, dirty = false, baseVersion = s.version)); true }
            Resolution.Keep -> false
            is Resolution.Rebase -> {
                val l = local!!
                val newVersion = r.serverVersion + 1
                val data = if (l.row.deleted) ByteArray(0) else VaultSession.withKey { k ->
                    val json = decryptEntry(k, l.row.id, l.row.version.toULong(), l.row.data)
                    encryptEntry(k, l.row.id, newVersion.toULong(), json)
                }
                store.put(LocalRow(SyncRow(l.row.id, newVersion, l.row.updatedAt, l.row.deleted, data), dirty = true, baseVersion = r.serverVersion))
                true
            }
        }
    }

    private fun parseRow(o: JSONObject) = SyncRow(
        id = o.getString("id"),
        version = o.getLong("version"),
        updatedAt = o.getLong("updated_at"),
        deleted = o.getBoolean("deleted"),
        data = Base64.decode(o.optString("encrypted_data", ""), Base64.DEFAULT),
    )

    private fun call(laptop: Device, path: String, block: okhttp3.Request.Builder.() -> Unit): JSONObject {
        val req = FortisApiClient.buildRequest(path, laptop, block)
        val resp = try {
            FortisApiClient.getClient().newCall(req).execute()
        } catch (e: Exception) {
            throw VaultError("Can't reach ${laptop.name}. Is Tailscale on?")
        }
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            if (r.isSuccessful) return JSONObject(text)
            val code = runCatching { JSONObject(text).getJSONObject("error").getString("code") }.getOrDefault("")
            throw VaultError(when (code) {
                "vault_locked" -> "The vault on ${laptop.name} is locked. Unlock it there (fortis unlock), then try again."
                "vault_uninitialised" -> "${laptop.name} has no vault yet (fortis init)."
                "unauthorized" -> "${laptop.name} doesn't recognise this phone. Pair again."
                else -> "Sync failed (HTTP ${r.code}${if (code.isNotEmpty()) ", $code" else ""})."
            })
        }
    }

    companion object {
        private const val K_WRAPPED = "wrapped_vault_key_local"
        private const val K_VAULT_ID = "vault_id"
        private const val K_LAPTOP = "laptop_uuid"
        private const val K_SEQ = "last_seq"

        @Volatile private var instance: VaultRepository? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: VaultRepository(context.applicationContext).also { instance = it } }

        /** `{"Login": {...}}` → `{...}` */
        fun innerOf(entry: JSONObject): JSONObject? {
            val t = entry.optJSONObject("entry_type") ?: return null
            val k = t.keys().asSequence().firstOrNull() ?: return null
            return t.optJSONObject(k)
        }

        fun kindOf(entry: JSONObject): String = entry.optJSONObject("entry_type")?.keys()?.asSequence()?.firstOrNull() ?: "?"
    }
}
