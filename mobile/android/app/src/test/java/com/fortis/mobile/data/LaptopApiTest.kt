package com.fortis.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaptopApiTest {
    @Test fun mediaParsesAgentResponse() {
        val m = MediaState.parse(
            """{"title":"Song","artist":"A","album":"B","position_us":5000000,"length_us":200000000,"status":"Playing","player":"spotify"}"""
        )!!
        assertEquals("Song", m.title)
        assertEquals("spotify", m.player)
        assertEquals(5_000_000L, m.positionUs)
        assertEquals(200_000_000L, m.lengthUs)
        assertTrue(m.isPlaying)
    }

    @Test fun browserLockParsesAgentResponse() {
        val s = BrowserLockState.parse("""{"locked":true,"locked_at":1731110400,"sites":["web.whatsapp.com","gmail.com"],"has_password":true}""")
        assertTrue(s.locked)
        assertEquals(listOf("web.whatsapp.com", "gmail.com"), s.sites)
        assertTrue(s.hasPassword)
        assertEquals(1731110400L, s.lockedAt)
        val off = BrowserLockState.parse("""{"locked":false,"locked_at":null,"sites":[],"has_password":false}""")
        assertFalse(off.locked)
        assertNull(off.lockedAt)
        assertTrue(off.sites.isEmpty())
    }

    @Test fun mediaWithNoTitleIsNothingPlaying() =
        assertNull(MediaState.parse("""{"title":"","status":"Stopped"}"""))

    @Test fun mediaToleratesNullPosition() =
        assertEquals(0L, MediaState.parse("""{"title":"x","position_us":null,"length_us":null}""")!!.positionUs)

    @Test fun listingSortsFoldersFirstThenByName() {
        val l = Listing.parse(
            """{"path":"/home/u","entries":[
                {"name":"b.txt","is_dir":false,"size":3},
                {"name":"Zeta","is_dir":true,"size":0},
                {"name":"a.txt","is_dir":false,"size":1},
                {"name":"alpha","is_dir":true,"size":0}]}"""
        )
        assertEquals(listOf("alpha", "Zeta", "a.txt", "b.txt"), l.entries.map { it.name })
        assertEquals("/home/u", l.path)
    }

    @Test fun listingPaths() {
        val home = Listing("/home/u", emptyList())
        assertEquals("/home/u/x", home.childPath(RemoteEntry("x", false, 0)))
        assertEquals("/home", home.parent)
        assertEquals("/", Listing("/home", emptyList()).parent)
        assertNull(Listing("/", emptyList()).parent)
        assertNull(Listing("", emptyList()).parent)
        assertEquals("/x", Listing("/", emptyList()).childPath(RemoteEntry("x", true, 0)))
    }

    @Test fun errorMessageUsesTheAgentsText() {
        assertEquals("vault is locked", LaptopApi.errorMessage(423, """{"error":{"code":"locked","message":"vault is locked"}}"""))
        assertEquals("The laptop answered HTTP 502", LaptopApi.errorMessage(502, "<html>bad gateway</html>"))
        assertEquals("The laptop answered HTTP 500", LaptopApi.errorMessage(500, ""))
    }

    @Test fun readinessThresholds() {
        val ok = Readiness(batteryPercent = 21, freeGb = 6, tailscale = true, sshd = true)
        assertTrue(ok.ready)
        assertFalse(ok.copy(batteryPercent = 20).batteryOk)
        assertFalse(ok.copy(freeGb = 5).storageOk)
        assertFalse(ok.copy(sshd = false).ready)
    }
}
