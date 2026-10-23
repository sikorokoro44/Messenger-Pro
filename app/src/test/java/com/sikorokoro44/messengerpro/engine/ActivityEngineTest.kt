package com.sikorokoro44.messengerpro.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityEngineTest {

    private var now = 1_000_000L

    @Test
    fun recordsFiltersAndSearches() {
        val activity = ActivityEngine { now }
        activity.record("a", "LOGIN", "Signed in")
        activity.record("a", "MESSAGE_SENT", "Hello Bob", "bob")
        activity.record("b", "LOGIN", "Other user")
        assertEquals(2, activity.all("a").size)
        assertEquals(1, activity.filter("a", type = "LOGIN").size)
        assertEquals(1, activity.filter("a", contactId = "bob").size)
        assertEquals(1, activity.search("a", "hello").size)
        assertEquals(1, activity.search("a", "login").size)
    }

    @Test
    fun reportsTimeWindows() {
        val activity = ActivityEngine { now }
        activity.record("a", "LOGIN", "now")
        now += 2 * 86_400_000L
        activity.record("a", "MESSAGE_SENT", "later")
        assertEquals(2, activity.last30Days("a"))
        assertEquals(1, activity.last24Hours("a"))
        assertEquals(2, activity.last7Days("a"))
        assertEquals(1, activity.dailySummary("a")["MESSAGE_SENT"])
        assertEquals(1, activity.weeklySummary("a")["LOGIN"])
        assertEquals(1, activity.monthlySummary("a")["LOGIN"])
    }

    @Test
    fun exportsAndClears() {
        val activity = ActivityEngine { now }
        activity.record("a", "LOGIN", "Signed in")
        assertTrue(activity.export("a").contains("LOGIN"))
        now += 2_000
        activity.record("a", "LOGOUT", "Signed out")
        assertEquals(1, activity.clearOlderThan("a", now - 1_000))
        assertEquals(1, activity.all("a").size)
    }
}
