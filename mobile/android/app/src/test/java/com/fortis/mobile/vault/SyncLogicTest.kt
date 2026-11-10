package com.fortis.mobile.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncLogicTest {
    private fun row(version: Long, updatedAt: Long, deleted: Boolean = false) = SyncRow("id", version, updatedAt, deleted, ByteArray(0))
    private fun local(version: Long, updatedAt: Long, dirty: Boolean, base: Long = version - 1) = LocalRow(row(version, updatedAt), dirty, base)

    @Test fun newRowFromLaptopIsTaken() = assertEquals(Resolution.TakeServer, SyncLogic.resolve(null, row(1, 10)))

    @Test fun cleanLocalTakesNewerServer() = assertEquals(Resolution.TakeServer, SyncLogic.resolve(local(1, 10, dirty = false), row(2, 20)))

    @Test fun cleanLocalTakesEchoOfSameVersion() = assertEquals(Resolution.TakeServer, SyncLogic.resolve(local(2, 10, dirty = false), row(2, 10)))

    @Test fun cleanLocalIgnoresOlderServer() = assertEquals(Resolution.Keep, SyncLogic.resolve(local(3, 10, dirty = false), row(2, 5)))

    @Test fun dirtyLocalNewerWinsAndRebases() =
        assertEquals(Resolution.Rebase(5), SyncLogic.resolve(local(2, 100, dirty = true, base = 1), row(5, 50)))

    @Test fun dirtyLocalOlderLoses() = assertEquals(Resolution.TakeServer, SyncLogic.resolve(local(2, 50, dirty = true, base = 1), row(5, 100)))

    @Test fun tieGoesToLaptop() = assertEquals(Resolution.TakeServer, SyncLogic.resolve(local(2, 70, dirty = true, base = 1), row(5, 70)))

    @Test fun serverDeletionNewerThanLocalEditWins() =
        assertEquals(Resolution.TakeServer, SyncLogic.resolve(local(2, 50, dirty = true, base = 1), row(3, 60, deleted = true)))

    @Test fun versions() {
        assertEquals(1L to 0L, SyncLogic.nextVersion(null))
        assertEquals(4L to 3L, SyncLogic.nextVersion(local(3, 1, dirty = false)))
        // Second local edit before syncing keeps the same base.
        assertEquals(4L to 3L, SyncLogic.nextVersion(local(4, 1, dirty = true, base = 3)))
    }
}
