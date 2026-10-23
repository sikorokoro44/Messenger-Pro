package com.sikorokoro44.messengerpro.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteApiTest {
    private val account = """{"id":"acct-1","username":"alice","displayName":"Alice"}"""
    private val message = """
        {"id":"msg-1","clientId":"c1","conversationId":"acct-1:acct-2","senderId":"acct-1",
         "recipientId":"acct-2","body":"hello","createdAt":10,"state":"SENT","replyToId":null,
         "editedAt":null,"reactions":[{"emoji":"ok","senderId":"acct-2","at":11}],"deleted":false,
         "expiresAt":null,"transcript":null,"seq":4}
    """.trimIndent()

    @Test
    fun normalizesServerUrl() {
        assertEquals("https://example.com", RemoteApi.normalize(" example.com/ "))
        assertEquals("http://127.0.0.1:8080", RemoteApi.normalize("http://127.0.0.1:8080/"))
        try {
            RemoteApi.normalize("   ")
            fail("expected failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("required"))
        }
    }

    @Test
    fun registersAndParsesSession() {
        val transport = FakeTransport()
        transport.enqueue(201, """{"token":"tok","account":$account}""")
        val api = RemoteApi("https://server.test", transport)
        val session = api.register("alice", "Alice", "password1", phone = "+1", email = "a@b.c")
        assertEquals("tok", session.token)
        assertEquals("acct-1", session.account.id)
        assertEquals("POST", transport.calls[0].method)
        assertEquals("https://server.test/v1/accounts", transport.calls[0].url)
        assertTrue(transport.calls[0].body!!.contains("\"phone\":\"+1\""))
    }

    @Test
    fun surfacesServerErrorMessages() {
        val transport = FakeTransport()
        transport.enqueue(401, """{"error":{"code":"invalid_credentials","message":"Invalid username or password"}}""")
        try {
            RemoteApi("https://server.test", transport).login("alice", "bad")
            fail("expected RemoteException")
        } catch (error: RemoteException) {
            assertEquals(401, error.status)
            assertEquals("Invalid username or password", error.message)
        }
    }

    @Test
    fun sendsIdempotentMessagesAndParsesThem() {
        val transport = FakeTransport()
        transport.enqueue(201, """{"message":$message}""")
        val api = RemoteApi("server.test", transport)
        val sent = api.send("tok", "acct-2", "hello", "c1")
        assertEquals("msg-1", sent.id)
        assertEquals("SENT", sent.state)
        assertEquals(1, sent.reactions.size)
        assertEquals("Bearer tok", transport.calls[0].token)
        assertTrue(transport.calls[0].url.startsWith("https://server.test/v1/messages"))
        assertTrue(transport.calls[0].body!!.contains("\"clientId\":\"c1\""))
    }

    @Test
    fun readsThreadPageAndSync() {
        val transport = FakeTransport()
        transport.enqueue(200, """{"messages":[$message],"cursor":9}""")
        transport.enqueue(200, """{"events":[{"seq":3,"type":"message.read","payload":{"messageId":"msg-1"}}],"cursor":3}""")
        val api = RemoteApi("https://server.test", transport)
        val page = api.messages("tok", "acct-2", since = 2)
        assertEquals(1, page.messages.size)
        assertEquals(9L, page.cursor)
        val sync = api.sync("tok", 0)
        assertEquals("message.read", sync.events[0].type)
        assertEquals(3L, sync.cursor)
    }

    @Test
    fun parsesContactsThreadsAndPresence() {
        val transport = FakeTransport()
        transport.enqueue(200, """{"contacts":[{"contactAccountId":"acct-2","nickname":"B","favourite":true,"blocked":false,"updatedAt":5}]}""")
        transport.enqueue(200, """{"threads":[{"peerId":"acct-2","unread":2,"lastMessage":$message,"updatedAt":10,"favourite":true,"blocked":false}]}""")
        transport.enqueue(200, """{"presence":{"accountId":"acct-2","state":"ONLINE","lastSeen":7,"availability":"Busy"}}""")
        val api = RemoteApi("https://server.test", transport)
        assertEquals("acct-2", api.contacts("tok")[0].contactAccountId)
        val thread = api.threads("tok")[0]
        assertEquals(2, thread.unread)
        assertEquals("msg-1", thread.lastMessage!!.id)
        assertEquals("Busy", api.presence("tok", "acct-2")!!.availability)
    }

    @Test
    fun lookupReturnsNullOnNotFound() {
        val transport = FakeTransport()
        transport.enqueue(404, """{"error":{"code":"no_such_account","message":"No account with that username"}}""")
        org.junit.Assert.assertNull(RemoteApi("https://server.test", transport).lookup("tok", "nobody"))
        assertTrue(transport.calls[0].url.contains("username=nobody"))
    }

    @Test
    fun accountLookupCarriesDisplayName() {
        val transport = FakeTransport()
        transport.enqueue(200, """{"account":$account}""")
        val found = RemoteApi("https://server.test", transport).account("tok", "acct-1")
        assertEquals("Alice", found.displayName)
    }
}
