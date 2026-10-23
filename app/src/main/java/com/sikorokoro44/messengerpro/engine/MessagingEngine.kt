package com.sikorokoro44.messengerpro.engine

import com.sikorokoro44.messengerpro.model.Conversation
import com.sikorokoro44.messengerpro.model.DeliveryState
import com.sikorokoro44.messengerpro.model.Message
import com.sikorokoro44.messengerpro.model.Reaction

/**
 * Conversation and message engine. Threads are shared between the two account
 * ids so a message sent by one is immediately visible to the other, which is the
 * behaviour a real server would provide. Everything is deterministic and can be
 * driven from unit tests.
 */
class MessagingEngine(private val clock: () -> Long = System::currentTimeMillis) {

    var connected: Boolean = true
        set(value) {
            field = value
            if (value) flushQueue()
        }

    private val messagesByThread = LinkedHashMap<String, MutableList<Message>>()
    private val conversations = LinkedHashMap<String, Conversation>()
    private val disappearingTtl = LinkedHashMap<String, Long>()
    private var sequence = 0L

    fun threadId(a: String, b: String): String = listOf(a, b).sorted().joinToString(":")

    private fun conversationKey(ownerId: String, participantId: String) = ownerId + "->" + participantId

    fun conversation(ownerId: String, participantId: String): Conversation =
        conversations[conversationKey(ownerId, participantId)] ?: Conversation(
            id = threadId(ownerId, participantId),
            ownerId = ownerId,
            participantId = participantId
        ).also { conversations[conversationKey(ownerId, participantId)] = it }

    fun conversations(ownerId: String): List<Conversation> =
        conversations.values.filter { it.ownerId == ownerId }.sortedByDescending { it.pinned }

    fun sendMessage(
        senderId: String,
        recipientId: String,
        body: String,
        clientId: String = "client-" + Hashing.randomHex(6),
        replyToId: String? = null
    ): Message {
        val thread = threadId(senderId, recipientId)
        val existing = messagesByThread[thread]?.firstOrNull { it.clientId == clientId }
        if (existing != null) return existing
        val now = clock()
        val expiresAt = disappearingTtl[thread]?.let { now + it }
        val initial = when {
            !connected -> DeliveryState.QUEUED
            else -> DeliveryState.DELIVERED
        }
        val message = Message(
            id = "msg-" + (++sequence) + "-" + Hashing.randomHex(4),
            clientId = clientId,
            conversationId = thread,
            senderId = senderId,
            body = body.trim(),
            createdAt = now,
            state = initial,
            replyToId = replyToId,
            expiresAt = expiresAt
        )
        messagesByThread.getOrPut(thread) { mutableListOf() }.add(message)
        bumpUnread(recipientId, senderId)
        return message
    }

    private fun bumpUnread(ownerId: String, participantId: String) {
        val key = conversationKey(ownerId, participantId)
        val current = conversations[key] ?: Conversation(threadId(ownerId, participantId), ownerId, participantId)
        conversations[key] = current.copy(unread = current.unread + 1)
    }

    fun messages(ownerId: String, participantId: String): List<Message> {
        val thread = threadId(ownerId, participantId)
        val now = clock()
        return (messagesByThread[thread] ?: emptyList())
            .filter { it.expiresAt == null || it.expiresAt > now }
            .sortedBy { it.createdAt }
    }

    fun flushQueue(): Int {
        var flushed = 0
        for (thread in messagesByThread.values) {
            for (i in thread.indices) {
                val m = thread[i]
                if (m.state == DeliveryState.QUEUED || m.state == DeliveryState.FAILED) {
                    thread[i] = m.copy(state = DeliveryState.DELIVERED)
                    flushed++
                }
            }
        }
        return flushed
    }

    fun markRead(ownerId: String, participantId: String): Int {
        val thread = threadId(ownerId, participantId)
        val list = messagesByThread[thread] ?: return 0
        var changed = 0
        for (i in list.indices) {
            val m = list[i]
            if (m.senderId != ownerId && m.state != DeliveryState.READ) {
                list[i] = m.copy(state = DeliveryState.READ)
                changed++
            }
        }
        val key = conversationKey(ownerId, participantId)
        conversations[key] = (conversations[key] ?: Conversation(thread, ownerId, participantId)).copy(unread = 0)
        return changed
    }

    fun editMessage(messageId: String, senderId: String, newBody: String): Boolean {
        for (list in messagesByThread.values) {
            val idx = list.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                val m = list[idx]
                if (m.senderId != senderId || m.deleted) return false
                list[idx] = m.copy(body = newBody.trim(), editedAt = clock())
                return true
            }
        }
        return false
    }

    fun deleteMessage(messageId: String, requesterId: String, forEveryone: Boolean = false): Boolean {
        for (list in messagesByThread.values) {
            val idx = list.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                val m = list[idx]
                if (forEveryone && m.senderId != requesterId) return false
                list[idx] = m.copy(deleted = true, body = "")
                return true
            }
        }
        return false
    }

    fun addReaction(messageId: String, emoji: String, senderId: String): Int {
        for (list in messagesByThread.values) {
            val idx = list.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                val m = list[idx]
                val reactions = m.reactions.filterNot { it.senderId == senderId } + Reaction(emoji, senderId)
                list[idx] = m.copy(reactions = reactions)
                return reactions.size
            }
        }
        return -1
    }

    fun removeReaction(messageId: String, senderId: String): Int {
        for (list in messagesByThread.values) {
            val idx = list.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                val m = list[idx]
                val reactions = m.reactions.filterNot { it.senderId == senderId }
                list[idx] = m.copy(reactions = reactions)
                return reactions.size
            }
        }
        return -1
    }

    fun search(ownerId: String, query: String): List<Message> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return messagesByThread.entries
            .filter { it.key.split(":").contains(ownerId) }
            .flatMap { it.value }
            .filter { !it.deleted && (it.body.lowercase().contains(q) || (it.transcript?.lowercase()?.contains(q) == true)) }
            .sortedByDescending { it.createdAt }
    }

    fun unreadCount(ownerId: String): Int =
        conversations.values.filter { it.ownerId == ownerId }.sumOf { it.unread }

    fun setDisappearing(ownerId: String, participantId: String, ttlMs: Long?) {
        val thread = threadId(ownerId, participantId)
        if (ttlMs == null) disappearingTtl.remove(thread) else disappearingTtl[thread] = ttlMs
    }

    fun expire(now: Long = clock()): Int {
        var removed = 0
        for (list in messagesByThread.values) {
            removed += list.count { it.expiresAt != null && it.expiresAt <= now }
            list.removeAll { it.expiresAt != null && it.expiresAt <= now }
        }
        return removed
    }

    fun saveDraft(ownerId: String, participantId: String, draft: String) {
        val key = conversationKey(ownerId, participantId)
        val c = conversations[key] ?: Conversation(threadId(ownerId, participantId), ownerId, participantId)
        conversations[key] = c.copy(draft = draft)
    }

    fun draft(ownerId: String, participantId: String): String? =
        conversations[conversationKey(ownerId, participantId)]?.draft

    fun pin(ownerId: String, participantId: String, pinned: Boolean) = updateConversation(ownerId, participantId) { it.copy(pinned = pinned) }
    fun archive(ownerId: String, participantId: String, archived: Boolean) = updateConversation(ownerId, participantId) { it.copy(archived = archived) }
    fun mute(ownerId: String, participantId: String, muted: Boolean) = updateConversation(ownerId, participantId) { it.copy(muted = muted) }
    fun setFolder(ownerId: String, participantId: String, folder: String?) = updateConversation(ownerId, participantId) { it.copy(folder = folder) }

    private fun updateConversation(ownerId: String, participantId: String, transform: (Conversation) -> Conversation) {
        val key = conversationKey(ownerId, participantId)
        val c = conversations[key] ?: Conversation(threadId(ownerId, participantId), ownerId, participantId)
        conversations[key] = transform(c)
    }
    fun exportMessages(): List<Message> = messagesByThread.values.flatten()

    fun importMessages(list: List<Message>) {
        messagesByThread.clear()
        for (m in list) messagesByThread.getOrPut(m.conversationId) { mutableListOf() }.add(m)
    }

    fun exportConversations(): List<Conversation> = conversations.values.toList()

    fun importConversations(list: List<Conversation>) {
        conversations.clear()
        for (c in list) conversations[c.ownerId + "->" + c.participantId] = c
    }
}
