package com.sikorokoro44.messengerpro.engine

import com.sikorokoro44.messengerpro.model.DeliveryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingEngineTest {

    private var now = 10_000L
    private fun engine() = MessagingEngine { now }

    @Test
    fun threadIdIsOrderIndependent() {
        val engine = engine()
        assertEquals(engine.threadId("b", "a"), engine.threadId("a", "b"))
        assertNotEquals(engine.threadId("a", "b"), engine.threadId("a", "c"))
    }

    @Test
    fun queuesWhileOfflineAndFlushes() {
        val engine = engine()
        engine.connected = false
        val queued = engine.sendMessage("a", "b", "hello")
        assertEquals(DeliveryState.QUEUED, queued.state)
        engine.connected = true
        assertEquals(1, engine.flushQueue())
        assertEquals(DeliveryState.DELIVERED, engine.messages("a", "b").single().state)
    }

    @Test
    fun deduplicatesByClientId() {
        val engine = engine()
        val first = engine.sendMessage("a", "b", "hello", clientId = "c1")
        val second = engine.sendMessage("a", "b", "hello", clientId = "c1")
        assertEquals(first.id, second.id)
        assertEquals(1, engine.messages("a", "b").size)
    }

    @Test
    fun sharedThreadSeesBothDirections() {
        val engine = engine()
        engine.sendMessage("a", "b", "hi")
        engine.sendMessage("b", "a", "hey")
        val forA = engine.messages("a", "b")
        val forB = engine.messages("b", "a")
        assertEquals(2, forA.size)
        assertEquals(forA.map { it.id }, forB.map { it.id })
    }

    @Test
    fun unreadAndMarkRead() {
        val engine = engine()
        engine.sendMessage("a", "b", "one")
        engine.sendMessage("a", "b", "two")
        assertEquals(2, engine.unreadCount("b"))
        assertEquals(2, engine.markRead("b", "a"))
        assertEquals(0, engine.unreadCount("b"))
        assertEquals(0, engine.markRead("b", "a"))
    }

    @Test
    fun onlySenderCanEditOrDeleteForEveryone() {
        val engine = engine()
        val message = engine.sendMessage("a", "b", "hi")
        assertFalse(engine.editMessage(message.id, "b", "hacked"))
        assertTrue(engine.editMessage(message.id, "a", "edited"))
        assertEquals("edited", engine.messages("a", "b").single().body)
        assertFalse(engine.deleteMessage(message.id, "b", forEveryone = true))
        assertTrue(engine.deleteMessage(message.id, "b"))
        assertTrue(engine.messages("a", "b").single().deleted)
    }

    @Test
    fun reactionsReplaceOwnAndRemove() {
        val engine = engine()
        val message = engine.sendMessage("a", "b", "hi")
        assertEquals(1, engine.addReaction(message.id, "👍", "b"))
        assertEquals(1, engine.addReaction(message.id, "❤️", "b"))
        assertEquals("❤️", engine.messages("a", "b").single().reactions.single().emoji)
        assertEquals(0, engine.removeReaction(message.id, "b"))
        assertEquals(-1, engine.addReaction("missing", "👍", "b"))
    }

    @Test
    fun searchMatchesBodyAndTranscript() {
        val engine = engine()
        engine.sendMessage("a", "b", "dinner at seven")
        val voice = engine.sendMessage("a", "b", "voice note")
        engine.exportMessages()
        val found = engine.search("a", "DINNER")
        assertEquals(1, found.size)
        assertTrue(engine.search("b", "seven").isNotEmpty())
        assertEquals(0, engine.search("a", "   ").size)
        assertNotNull(voice)
    }

    @Test
    fun disappearingMessagesExpire() {
        val engine = engine()
        engine.setDisappearing("a", "b", 1_000)
        engine.sendMessage("a", "b", "secret")
        now += 1_001
        assertEquals(0, engine.messages("a", "b").size)
        assertEquals(1, engine.expire())
    }

    @Test
    fun conversationPreferences() {
        val engine = engine()
        engine.saveDraft("a", "b", "unsent")
        assertEquals("unsent", engine.draft("a", "b"))
        engine.pin("a", "b", true)
        engine.mute("a", "b", true)
        engine.archive("a", "b", true)
        engine.setFolder("a", "b", "Work")
        val conversation = engine.conversation("a", "b")
        assertTrue(conversation.pinned)
        assertTrue(conversation.muted)
        assertTrue(conversation.archived)
        assertEquals("Work", conversation.folder)
        engine.sendMessage("a", "b", "x")
        assertTrue(engine.conversations("a").isNotEmpty())
    }

    @Test
    fun missingMessageReturnsNullOnNoopEdits() {
        val engine = engine()
        assertFalse(engine.editMessage("nope", "a", "x"))
        assertFalse(engine.deleteMessage("nope", "a"))
        assertNull(engine.draft("a", "z"))
    }
    @Test
    fun messagesAreChronologicalWithTimestamps() {
        val engine = engine()
        engine.sendMessage("a", "b", "first")
        now += 5_000
        engine.sendMessage("a", "b", "second")
        val messages = engine.messages("a", "b")
        assertEquals(listOf("first", "second"), messages.map { it.body })
        assertTrue(messages[1].createdAt > messages[0].createdAt)
    }

    @Test
    fun editTracksEditedTimestamp() {
        val engine = engine()
        val message = engine.sendMessage("a", "b", "before")
        now += 1_000
        assertTrue(engine.editMessage(message.id, "a", "after"))
        assertEquals(now, engine.messages("a", "b").single().editedAt)
        assertEquals("after", engine.messages("a", "b").single().body)
    }
}
