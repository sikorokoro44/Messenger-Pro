package com.sikorokoro44.messengerpro.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthEngineTest {

    private var now = 1_000L
    private fun engine() = AuthEngine { now }

    @Test
    fun rejectsInvalidRegistration() {
        val auth = engine()
        assertFalse(auth.register("ab", "A", "password1").ok)
        assertFalse(auth.register("bad name", "A", "password1").ok)
        assertFalse(auth.register("good.name", "A", "short").ok)
        assertFalse(auth.register("good.name", " ", "password1").ok)
    }

    @Test
    fun registersAndFindsAccount() {
        val auth = engine()
        val result = auth.register("alice", "Alice", "password1")
        assertTrue(result.ok)
        assertNotNull(result.value)
        assertEquals(1, auth.accountCount())
        assertTrue(auth.usernameAvailable("bob"))
        assertFalse(auth.usernameAvailable("ALICE"))
        assertEquals("alice", auth.findByUsername("ALICE")?.username)
    }

    @Test
    fun hashesPasswordWithSalt() {
        val auth = engine()
        val a = auth.register("alice", "Alice", "password1").value!!
        val b = auth.register("bob", "Bob", "password1").value!!
        assertTrue(a.passwordHash.isNotBlank())
        assertTrue(a.salt.isNotBlank())
        assertFalse(a.passwordHash == "password1")
        assertTrue(a.salt != b.salt)
        assertTrue(a.passwordHash != b.passwordHash)
    }

    @Test
    fun locksAfterFiveFailures() {
        val auth = engine()
        auth.register("alice", "Alice", "password1")
        repeat(5) { assertFalse(auth.login("alice", "wrongpass").ok) }
        val locked = auth.login("alice", "password1")
        assertFalse(locked.ok)
        assertTrue(locked.error!!.contains("Too many"))
        now += 61_000
        assertTrue(auth.login("alice", "password1").ok)
    }

    @Test
    fun logoutRevokesSession() {
        val auth = engine()
        auth.register("alice", "Alice", "password1")
        val session = auth.login("alice", "password1").value!!
        assertNotNull(auth.session(session.token))
        assertTrue(auth.logout(session.token))
        assertNull(auth.session(session.token))
    }

    @Test
    fun changePasswordRevokesSessionsAndWorks() {
        val auth = engine()
        auth.register("alice", "Alice", "password1")
        val account = auth.findByUsername("alice")!!
        val session = auth.login("alice", "password1").value!!
        assertFalse(auth.changePassword(account.id, "wrong", "password2").ok)
        assertTrue(auth.changePassword(account.id, "password1", "password2").ok)
        assertNull(auth.session(session.token))
        assertFalse(auth.login("alice", "password1").ok)
        assertTrue(auth.login("alice", "password2").ok)
    }

    @Test
    fun resetPasswordUsesVerificationCode() {
        val auth = engine()
        auth.register("alice", "Alice", "password1")
        val account = auth.findByUsername("alice")!!
        val code = auth.beginVerification(account.id).value!!
        assertFalse(auth.verify(account.id, "definitely-wrong").ok)
        assertTrue(auth.verify(account.id, code.code).ok)
        val code2 = auth.beginVerification(account.id).value!!
        assertTrue(auth.resetPassword(account.id, code2.code, "password2").ok)
        assertTrue(auth.login("alice", "password2").ok)
        now += 10 * 60_000
        val code3 = auth.beginVerification(account.id).value!!
        now += 6 * 60_000
        assertFalse(auth.verify(account.id, code3.code).ok)
    }

    @Test
    fun deletionRequiresToken() {
        val auth = engine()
        auth.register("alice", "Alice", "password1")
        val account = auth.findByUsername("alice")!!
        val token = auth.requestDeletion(account.id).value!!
        assertFalse(auth.confirmDeletion(account.id, "nope").ok)
        assertTrue(auth.confirmDeletion(account.id, token).ok)
        assertEquals(0, auth.accountCount())
    }

    @Test
    fun exportsAccountData() {
        val auth = engine()
        auth.register("alice", "Alice", "password1")
        val account = auth.findByUsername("alice")!!
        val export = auth.exportAccountData(account.id).value!!
        assertTrue(export.contains("username=alice"))
        assertTrue(export.contains("displayName=Alice"))
    }
    @Test
    fun preventsDuplicateRegistration() {
        val auth = engine()
        assertTrue(auth.register("alice", "Alice", "password1").ok)
        val duplicate = auth.register("ALICE", "Other", "password2")
        assertFalse(duplicate.ok)
        assertTrue(duplicate.error!!.contains("already exists"))
        assertEquals(1, auth.accountCount())
    }
}
