package com.sikorokoro44.messengerpro.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactEngineTest {

    @Test
    fun cannotAddSelf() {
        val contacts = ContactEngine()
        try {
            contacts.add("a", "a")
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("yourself"))
        }
    }

    @Test
    fun addIsIdempotentAndListsSorted() {
        val contacts = ContactEngine()
        val first = contacts.add("a", "z")
        val again = contacts.add("a", "z")
        assertEquals(first.id, again.id)
        contacts.add("a", "b")
        contacts.setFavourite("a", "b", true)
        assertEquals(listOf("b", "z"), contacts.list("a").map { it.contactAccountId })
        assertTrue(contacts.list("b").isEmpty())
    }

    @Test
    fun nicknameAndSearch() {
        val contacts = ContactEngine()
        contacts.add("a", "bob")
        contacts.setNickname("a", "bob", "  Bobby  ")
        assertEquals("Bobby", contacts.get("a", "bob")!!.nickname)
        assertEquals(1, contacts.search("a", "bobb").size)
        assertEquals(1, contacts.search("a", "BOB").size)
        contacts.setNickname("a", "bob", "   ")
        assertNull(contacts.get("a", "bob")!!.nickname)
    }

    @Test
    fun blockAndHide() {
        val contacts = ContactEngine()
        contacts.add("a", "b")
        contacts.block("a", "b")
        assertTrue(contacts.isBlocked("a", "b"))
        assertFalse(contacts.list("a").any { it.contactAccountId == "b" } && !contacts.list("a").first().blocked)
        assertEquals(1, contacts.blocked("a").size)
        contacts.unblock("a", "b")
        assertFalse(contacts.isBlocked("a", "b"))
        contacts.hide("a", "b")
        assertFalse(contacts.list("a").any { it.contactAccountId == "b" })
        assertTrue(contacts.list("a", includeHidden = true).any { it.contactAccountId == "b" })
        contacts.unhide("a", "b")
        assertTrue(contacts.list("a").any { it.contactAccountId == "b" })
    }

    @Test
    fun groupsAndRingtone() {
        val contacts = ContactEngine()
        contacts.add("a", "b")
        val group = contacts.createGroup("a", "Family")
        contacts.renameGroup("a", group, "Close family")
        assertEquals("Close family", contacts.groupName(group))
        contacts.addToGroup("a", group, "b")
        assertTrue(contacts.members(group).contains("b"))
        assertTrue(contacts.get("a", "b")!!.groupIds.contains(group))
        contacts.setRingtone("a", "b", "chime")
        assertEquals("chime", contacts.get("a", "b")!!.customRingtone)
        contacts.removeFromGroup("a", group, "b")
        assertFalse(contacts.members(group).contains("b"))
        contacts.remove("a", "b")
        assertNull(contacts.get("a", "b"))
    }
}
