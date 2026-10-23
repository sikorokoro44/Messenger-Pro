package com.sikorokoro44.messengerpro.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryTest {

    private var now = 1_000L

    private fun seeded(): Triple<MessengerRepository, String, String> {
        val repo = MessengerRepository { now }
        repo.register("alice", "Alice", "password1")
        repo.register("bob", "Bob", "password2")
        val alice = repo.auth.findByUsername("alice")!!
        val bob = repo.auth.findByUsername("bob")!!
        assertTrue(repo.login("alice", "password1").ok)
        return Triple(repo, alice.id, bob.id)
    }

    @Test
    fun sendsBetweenAccountsAndBlocks() {
        val (repo, aliceId, bobId) = seeded()
        assertTrue(repo.addContact(bobId))
        val message = repo.send(bobId, "hello bob")
        assertNotNull(message)
        assertEquals(1, repo.receiveVisible(bobId).size)

        assertTrue(repo.login("bob", "password2").ok)
        repo.contacts.block(bobId, aliceId)
        assertTrue(repo.login("alice", "password1").ok)
        assertNull(repo.send(bobId, "should be blocked"))
    }

    @Test
    fun logsActivity() {
        val (repo, _, bobId) = seeded()
        repo.addContact(bobId)
        repo.send(bobId, "hi")
        val types = repo.activity.all(repo.activeAccount()!!.id).map { it.type }
        assertTrue(types.contains("LOGIN"))
        assertTrue(types.contains("CONTACT_ADDED"))
        assertTrue(types.contains("MESSAGE_SENT"))
    }

    @Test
    fun persistsAndHydrates() {
        val (repo, aliceId, bobId) = seeded()
        repo.addContact(bobId)
        repo.send(bobId, "persist me")
        repo.settingsEngine.setTheme(SettingsEngine.Theme.DARK)
        val saved = repo.persist()

        val restored = MessengerRepository { now }
        assertTrue(restored.hydrate(saved))
        assertEquals("alice", restored.activeAccount()?.username)
        assertEquals(aliceId, restored.activeAccount()?.id)
        assertEquals("persist me", restored.receiveVisible(bobId).single().body)
        assertEquals(SettingsEngine.Theme.DARK, restored.settingsEngine.settings().theme)
        assertEquals(1, restored.contacts.list(aliceId).size)
        assertTrue(restored.activity.all(aliceId).isNotEmpty())
    }

    @Test
    fun hydrateRejectsGarbage() {
        val repo = MessengerRepository { now }
        assertFalse(repo.hydrate(null))
        assertFalse(repo.hydrate("garbage"))
        assertNull(repo.activeAccount())
    }

    @Test
    fun logoutClearsActiveSession() {
        val (repo, aliceId, _) = seeded()
        repo.logout()
        assertNull(repo.activeAccount())
        assertFalse(repo.login("alice", "wrongpass").ok)
        assertTrue(repo.activity.all(aliceId).any { it.type == "LOGOUT" })
    }
}
