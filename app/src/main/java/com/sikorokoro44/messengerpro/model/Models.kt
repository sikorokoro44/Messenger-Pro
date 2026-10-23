package com.sikorokoro44.messengerpro.model

enum class DeliveryState { QUEUED, SENDING, SENT, DELIVERED, READ, FAILED }

enum class PresenceState { ONLINE, OFFLINE }

data class Account(
    val id: String,
    val username: String,
    val displayName: String,
    val passwordHash: String,
    val salt: String,
    val createdAt: Long,
    val status: String = "ACTIVE",
    val phone: String? = null,
    val email: String? = null,
    val bio: String? = null
)

data class Session(
    val token: String,
    val accountId: String,
    val createdAt: Long,
    val revoked: Boolean = false
)

data class Contact(
    val id: String,
    val ownerId: String,
    val contactAccountId: String,
    val nickname: String? = null,
    val note: String? = null,
    val favourite: Boolean = false,
    val blocked: Boolean = false,
    val hidden: Boolean = false,
    val groupIds: Set<String> = emptySet(),
    val customRingtone: String? = null
)

data class Reaction(val emoji: String, val senderId: String)

data class Message(
    val id: String,
    val clientId: String,
    val conversationId: String,
    val senderId: String,
    val body: String,
    val createdAt: Long,
    val state: DeliveryState = DeliveryState.QUEUED,
    val replyToId: String? = null,
    val editedAt: Long? = null,
    val reactions: List<Reaction> = emptyList(),
    val deleted: Boolean = false,
    val expiresAt: Long? = null,
    val transcript: String? = null
)

data class Conversation(
    val id: String,
    val ownerId: String,
    val participantId: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val muted: Boolean = false,
    val unread: Int = 0,
    val folder: String? = null,
    val draft: String? = null,
    val isGroup: Boolean = false,
    val title: String? = null
)

data class ActivityEvent(
    val id: String,
    val ownerId: String,
    val type: String,
    val detail: String,
    val contactId: String? = null,
    val at: Long
)

data class Presence(
    val accountId: String,
    val state: PresenceState,
    val lastSeen: Long,
    val availability: String = "Available"
)
