package com.sikorokoro44.messengerpro.engine

import com.sikorokoro44.messengerpro.model.Account
import com.sikorokoro44.messengerpro.model.ActivityEvent
import com.sikorokoro44.messengerpro.model.Contact
import com.sikorokoro44.messengerpro.model.Conversation
import com.sikorokoro44.messengerpro.model.DeliveryState
import com.sikorokoro44.messengerpro.model.Message
import com.sikorokoro44.messengerpro.model.Reaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateCodecTest {

    private fun snapshot() = StateCodec.Snapshot(
        accounts = listOf(
            Account(
                id = "acct-1", username = "alice", displayName = "Alice A", passwordHash = "hash",
                salt = "salt", createdAt = 5, status = "ACTIVE", phone = "+123", email = "a@b.c", bio = "hi\nthere"
            )
        ),
        conversations = listOf(
            Conversation(
                id = "t1", ownerId = "acct-1", participantId = "acct-2", pinned = true, archived = false,
                muted = true, unread = 3, folder = "Work", draft = "later", isGroup = false, title = "Alice & Bob"
            )
        ),
        messages = listOf(
            Message(
                id = "m1", clientId = "c1", conversationId = "t1", senderId = "acct-1", body = "line1\nline2",
                createdAt = 9, state = DeliveryState.READ, replyToId = "m0", editedAt = 10,
                reactions = listOf(Reaction("👍", "acct-2")), deleted = false, expiresAt = 99, transcript = "spoken"
            )
        ),
        contacts = listOf(
            Contact(
                id = "k1", ownerId = "acct-1", contactAccountId = "acct-2", nickname = "Bobby", note = "n",
                favourite = true, blocked = false, hidden = false, groupIds = setOf("g1", "g2"), customRingtone = "ring"
            )
        ),
        events = listOf(ActivityEvent("e1", "acct-1", "MESSAGE_SENT", "hello", "acct-2", 11)),
        settings = SettingsEngine.Settings(
            theme = SettingsEngine.Theme.DARK, accentColor = 7, fontScale = 1.3f, language = "zu",
            readReceipts = false, lastSeenVisible = false, onlineVisible = false, messagePreviews = false,
            notificationsEnabled = false, aiEnabled = false, lowDataMode = true
        ),
        sessionToken = "token-1",
        sessionAccountId = "acct-1"
    )

    @Test
    fun roundTripsEverything() {
        val decoded = StateCodec.decode(StateCodec.encode(snapshot()))
        assertEquals(1, decoded.accounts.size)
        assertEquals("a@b.c", decoded.accounts.single().email)
        assertEquals("hi\nthere", decoded.accounts.single().bio)
        assertEquals(1, decoded.conversations.size)
        assertTrue(decoded.conversations.single().pinned)
        assertEquals(3, decoded.conversations.single().unread)
        assertEquals(1, decoded.messages.size)
        assertEquals("line1\nline2", decoded.messages.single().body)
        assertEquals(DeliveryState.READ, decoded.messages.single().state)
        assertEquals("👍", decoded.messages.single().reactions.single().emoji)
        assertEquals(99L, decoded.messages.single().expiresAt)
        assertEquals(setOf("g1", "g2"), decoded.contacts.single().groupIds)
        assertEquals(1, decoded.events.size)
        assertEquals(SettingsEngine.Theme.DARK, decoded.settings.theme)
        assertTrue(decoded.settings.lowDataMode)
        assertEquals("token-1", decoded.sessionToken)
        assertEquals("acct-1", decoded.sessionAccountId)
    }

    @Test
    fun blankAndTamperedReturnEmpty() {
        assertTrue(StateCodec.decode(null).accounts.isEmpty())
        assertTrue(StateCodec.decode("").messages.isEmpty())
        val encoded = StateCodec.encode(snapshot())
        val tampered = encoded.replace("Alice A", "Mallory")
        assertTrue(StateCodec.decode(tampered).accounts.isEmpty())
        assertNull(StateCodec.decode(tampered).sessionToken)
    }
}
