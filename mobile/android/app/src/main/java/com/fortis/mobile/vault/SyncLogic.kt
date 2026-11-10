package com.fortis.mobile.vault

/** One encrypted row as exchanged with the agent (docs/api §2). */
class SyncRow(
    val id: String,
    val version: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val data: ByteArray,
)

/** A row in the phone's store; [dirty] = local change not yet accepted by the laptop. */
class LocalRow(val row: SyncRow, val dirty: Boolean, val baseVersion: Long)

/** What to do with a row that came from the laptop (pull, or a push conflict). */
sealed interface Resolution {
    /** Take the laptop's copy (overwriting any local edit). */
    data object TakeServer : Resolution

    /** Keep what we have (it's already current). */
    data object Keep : Resolution

    /**
     * Our local edit is newer: re-encrypt it as `serverVersion + 1` and push
     * again with `base_version = serverVersion`.
     */
    data class Rebase(val serverVersion: Long) : Resolution
}

/**
 * Pure merge rules, identical in spirit to the agent's (fortis-core db.rs):
 * the copy with the larger `updated_at` wins; on a tie the laptop wins.
 */
object SyncLogic {
    fun resolve(local: LocalRow?, server: SyncRow): Resolution = when {
        local == null -> Resolution.TakeServer
        !local.dirty -> if (server.version >= local.row.version) Resolution.TakeServer else Resolution.Keep
        server.updatedAt >= local.row.updatedAt -> Resolution.TakeServer
        else -> Resolution.Rebase(server.version)
    }

    /** Version/base for a new local edit of [existing] (null = new entry). */
    fun nextVersion(existing: LocalRow?): Pair<Long, Long> {
        // A dirty row keeps its base (the laptop hasn't seen the earlier local edit).
        val base = when {
            existing == null -> 0L
            existing.dirty -> existing.baseVersion
            else -> existing.row.version
        }
        return (base + 1) to base
    }
}
