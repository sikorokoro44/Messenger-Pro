package com.sikorokoro44.messengerpro.engine

import com.sikorokoro44.messengerpro.model.PresenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceEngineTest {

    private var now = 0L

    @Test
    fun alertsWatchersOnChange() {
        val presence = PresenceEngine { now }
        presence.minAlertIntervalMs = 0
        presence.watch("watcher", "alice")
        presence.update("alice", PresenceState.ONLINE)
        presence.update("alice", PresenceState.OFFLINE)
        val alerts = presence.alerts("watcher")
        assertEquals(2, alerts.size)
        assertTrue(alerts.any { it.state == PresenceState.OFFLINE })
        assertTrue(presence.watchersOf("alice").contains("watcher"))
        presence.unwatch("watcher", "alice")
        assertFalse(presence.watchersOf("alice").contains("watcher"))
    }

    @Test
    fun suppressesDuplicateAndRapidAlerts() {
        val presence = PresenceEngine { now }
        presence.watch("w", "alice")
        presence.update("alice", PresenceState.ONLINE)
        presence.update("alice", PresenceState.ONLINE)
        assertEquals(1, presence.alerts("w").size)
        now = 1_000
        presence.update("alice", PresenceState.OFFLINE)
        assertEquals(1, presence.alerts("w").size)
        now = 10_000
        presence.update("alice", PresenceState.ONLINE)
        assertEquals(2, presence.alerts("w").size)
    }

    @Test
    fun quietHoursBlockAlerts() {
        val presence = PresenceEngine { now }
        presence.watch("w", "alice")
        presence.setQuietHours(22, 7)
        now = 23 * 3_600_000L
        presence.update("alice", PresenceState.ONLINE)
        assertEquals(0, presence.alerts("w").size)
        now = 12 * 3_600_000L
        presence.update("alice", PresenceState.OFFLINE)
        assertEquals(1, presence.alerts("w").size)
    }

    @Test
    fun privacyControls() {
        val presence = PresenceEngine { now }
        presence.update("alice", PresenceState.ONLINE)
        presence.setInvisible("alice", true)
        assertEquals(PresenceState.OFFLINE, presence.get("alice")!!.state)
        presence.setInvisible("alice", false)
        assertEquals(PresenceState.ONLINE, presence.get("alice")!!.state)
        presence.setLastSeenHidden("alice", true)
        assertFalse(presence.canSeeLastSeen("bob", "alice"))
        presence.setLastSeenHidden("alice", false)
        assertTrue(presence.canSeeLastSeen("bob", "alice"))
        presence.setAvailability("alice", "Busy")
        assertEquals("Busy", presence.get("alice")!!.availability)
        assertNull(presence.get("nobody"))
    }

    @Test
    fun filtersAndClearsAlerts() {
        val presence = PresenceEngine { now }
        presence.watch("w", "alice")
        presence.watch("w", "bob")
        presence.update("alice", PresenceState.ONLINE)
        presence.update("bob", PresenceState.ONLINE)
        assertEquals(1, presence.filterAlerts("w", accountId = "alice").size)
        assertEquals(2, presence.filterAlerts("w", from = 0).size)
        presence.clearAlerts("w")
        assertEquals(0, presence.alerts("w").size)
    }
}
