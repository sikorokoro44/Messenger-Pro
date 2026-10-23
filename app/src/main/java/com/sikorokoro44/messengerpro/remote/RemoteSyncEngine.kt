package com.sikorokoro44.messengerpro.remote

import com.sikorokoro44.messengerpro.engine.MessagingEngine
import com.sikorokoro44.messengerpro.model.DeliveryState
import com.sikorokoro44.messengerpro.model.Message
import com.sikorokoro44.messengerpro.model.Reaction

/**
 * Translates the server wire format into the local message store. It is pure
 * Kotlin so the merge and event-replay rules are unit tested without Android.
 */
class RemoteSyncEngine(private val messaging: MessagingEngine) {

    fun toLocal(remote: RemoteMessage): Message = Message(
        id = remote.id,
        clientId = remote.clientId,
        conversationId = remote.conversationId,
        senderId = remote.senderId,
        body = remote.body,
        createdAt = remote.createdAt,
        state = state(remote.state),
        replyToId = remote.replyToId,
        editedAt = remote.editedAt,
        reactions = remote.reactions.map { Reaction(it.emoji, it.senderId) },
        deleted = remote.deleted,
        expiresAt = remote.expiresAt,
        transcript = remote.transcript
    )

    fun apply(remote: RemoteMessage): Message = messaging.upsertRemote(toLocal(remote))

    fun applyAll(remotes: List<RemoteMessage>): Int {
        remotes.forEach(::apply)
        return remotes.size
    }

    fun applyEvent(event: RemoteEvent): Message? {
        if (!event.type.startsWith("message.")) return null
        if (event.payload["id"] == null && event.payload["messageId"] == null) return null
        if (event.payload["id"] == null) return null
        return apply(parse(event.payload))
    }

    fun applyEvents(events: List<RemoteEvent>): Int {
        var applied = 0
        for (event in events) if (applyEvent(event) != null) applied++
        return applied
    }

    fun mergeThreads(threads: List<RemoteThread>, meId: String): Int {
        var merged = 0
        for (thread in threads) {
            messaging.setUnread(meId, thread.peerId, thread.unread)
            thread.lastMessage?.let {
                apply(it)
                merged++
            }
        }
        return merged
    }

    private fun parse(payload: Map<String, Any?>): RemoteMessage = RemoteMessage(
        id = Json.str(payload["id"]) ?: "",
        clientId = Json.str(payload["clientId"]) ?: "",
        conversationId = Json.str(payload["conversationId"]) ?: "",
        senderId = Json.str(payload["senderId"]) ?: "",
        recipientId = Json.str(payload["recipientId"]) ?: "",
        body = Json.str(payload["body"]) ?: "",
        createdAt = Json.num(payload["createdAt"]) ?: 0,
        state = Json.str(payload["state"]) ?: "SENT",
        replyToId = Json.str(payload["replyToId"]),
        editedAt = Json.num(payload["editedAt"]),
        reactions = Json.arr(payload["reactions"]).map {
            val value = Json.obj(it)
            RemoteReaction(
                emoji = Json.str(value["emoji"]) ?: "",
                senderId = Json.str(value["senderId"]) ?: "",
                at = Json.num(value["at"]) ?: 0
            )
        },
        deleted = Json.bool(payload["deleted"]) ?: false,
        expiresAt = Json.num(payload["expiresAt"]),
        transcript = Json.str(payload["transcript"]),
        seq = Json.num(payload["seq"]) ?: 0
    )

    private fun state(value: String): DeliveryState = when (value.uppercase()) {
        "QUEUED" -> DeliveryState.QUEUED
        "SENDING" -> DeliveryState.SENDING
        "SENT" -> DeliveryState.SENT
        "DELIVERED" -> DeliveryState.DELIVERED
        "READ" -> DeliveryState.READ
        "FAILED" -> DeliveryState.FAILED
        else -> DeliveryState.SENT
    }
}
