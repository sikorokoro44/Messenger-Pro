package com.sikorokoro44.messengerpro.remote

import com.sikorokoro44.messengerpro.engine.MessagingEngine
import com.sikorokoro44.messengerpro.model.DeliveryState
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteSyncEngineTest {
    private fun remote(
        id: String = "msg-1",
        clientId: String = "c1",
        state: String = "SENT",
        body: String = "hello"
    ) = RemoteMessage(
        id = id,
        clientId = clientId,
        conversationId = "acct-1:acct-2",
        senderId = "acct-1",
        recipientId = "acct-2",
        body = body,
        createdAt = 10,
        state = state,
        replyToId = null,
        editedAt = null,
        reactions = emptyList(),
        deleted = false,
        expiresAt = null,
        transcript = null,
        seq = 1
    )

    @Test
    fun mergesRemoteMessagesWithoutDuplicatingClientId() {
        val messaging = MessagingEngine { 100 }
        val sync = RemoteSyncEngine(messaging)
        sync.apply(remote())
        sync.apply(remote(id = "msg-2", state = "DELIVERED"))
        val messages = messaging.messages("acct-1", "acct-2")
        assertEquals(1, messages.size)
        assertEquals("msg-2", messages[0].id)
        assertEquals(DeliveryState.DELIVERED, messages[0].state)
    }

    @Test
    fun appliesEventPayloads() {
        val messaging = MessagingEngine { 100 }
        val sync = RemoteSyncEngine(messaging)
        val event = RemoteEvent(
            seq = 3,
            type = "message.updated",
            payload = mapOf(
                "id" to "msg-9",
                "clientId" to "c9",
                "conversationId" to "acct-1:acct-2",
                "senderId" to "acct-1",
                "recipientId" to "acct-2",
                "body" to "edited",
                "createdAt" to 10L,
                "state" to "READ",
                "reactions" to listOf(mapOf("emoji" to "ok", "senderId" to "acct-2"))
            )
        )
        assertEquals(1, sync.applyEvents(listOf(event, RemoteEvent(2, "presence.changed", mapOf("accountId" to "acct-2")))))
        val message = messaging.messages("acct-1", "acct-2").single()
        assertEquals("edited", message.body)
        assertEquals(DeliveryState.READ, message.state)
        assertEquals("ok", message.reactions.single().emoji)
    }

    @Test
    fun mergesThreadUnreadCounts() {
        val messaging = MessagingEngine { 100 }
        val sync = RemoteSyncEngine(messaging)
        sync.mergeThreads(
            listOf(RemoteThread("acct-2", 4, remote(state = "SENT"), 10, true, false)),
            "acct-1"
        )
        assertEquals(4, messaging.conversation("acct-1", "acct-2").unread)
    }
}
