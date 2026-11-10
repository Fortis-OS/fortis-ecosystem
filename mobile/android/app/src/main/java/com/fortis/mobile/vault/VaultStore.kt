package com.fortis.mobile.vault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local copy of the vault (docs/security §6.3). Rows are stored exactly as the
 * agent stores them — each `data` blob is XChaCha20-Poly1305 bound to
 * (id, version) — so this database needs no extra encryption.
 *
 * dirty = 1 marks a local change not yet accepted by the laptop; its
 * `version` is `base_version + 1`.
 */
class VaultStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "vault.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entries (
                id TEXT PRIMARY KEY,
                version INTEGER NOT NULL,
                base_version INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0,
                dirty INTEGER NOT NULL DEFAULT 0,
                data BLOB NOT NULL
            )"""
        )
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun all(includeDeleted: Boolean = false): List<LocalRow> =
        readableDatabase.rawQuery(
            "SELECT id, version, base_version, updated_at, deleted, dirty, data FROM entries" + if (includeDeleted) "" else " WHERE deleted = 0",
            null,
        ).use { c ->
            val out = mutableListOf<LocalRow>()
            while (c.moveToNext()) out.add(c.toRow())
            out
        }

    fun get(id: String): LocalRow? =
        readableDatabase.rawQuery("SELECT id, version, base_version, updated_at, deleted, dirty, data FROM entries WHERE id = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.toRow() else null }

    fun dirty(): List<LocalRow> =
        readableDatabase.rawQuery("SELECT id, version, base_version, updated_at, deleted, dirty, data FROM entries WHERE dirty = 1", null).use { c ->
            val out = mutableListOf<LocalRow>()
            while (c.moveToNext()) out.add(c.toRow())
            out
        }

    fun put(r: LocalRow) {
        val v = ContentValues().apply {
            put("id", r.row.id); put("version", r.row.version); put("base_version", r.baseVersion)
            put("updated_at", r.row.updatedAt); put("deleted", if (r.row.deleted) 1 else 0)
            put("dirty", if (r.dirty) 1 else 0); put("data", r.row.data)
        }
        writableDatabase.insertWithOnConflict("entries", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Forget everything (switching vault laptop or vault). */
    fun wipe() {
        writableDatabase.execSQL("DELETE FROM entries")
        writableDatabase.execSQL("DELETE FROM meta")
    }

    private fun android.database.Cursor.toRow() = LocalRow(
        row = SyncRow(getString(0), getLong(1), getLong(3), getInt(4) != 0, getBlob(6)),
        dirty = getInt(5) != 0,
        baseVersion = getLong(2),
    )
}
