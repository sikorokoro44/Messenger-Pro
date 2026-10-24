package com.sikorokoro44.messengerpro.remote

data class RemoteResponse(val status: Int, val body: String)

interface RemoteTransport {
    fun request(method: String, url: String, token: String?, body: String?): RemoteResponse
}

class RemoteException(val status: Int, message: String) : Exception(message)

data class RemoteAccount(val id: String, val username: String, val displayName: String)

data class RemoteSession(
    val token: String,
    val account: RemoteAccount,
    val refreshToken: String? = null,
    val expiresAt: Long = 0L
)

data class RemoteReaction(val emoji: String, val senderId: String, val at: Long)

data class RemoteMessage(
    val id: String,
    val clientId: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String,
    val body: String,
    val createdAt: Long,
    val state: String,
    val replyToId: String?,
    val editedAt: Long?,
    val reactions: List<RemoteReaction>,
    val deleted: Boolean,
    val expiresAt: Long?,
    val transcript: String?,
    val seq: Long
)

data class RemoteEvent(val seq: Long, val type: String, val payload: Map<String, Any?>)

data class SyncPage(val events: List<RemoteEvent>, val cursor: Long)

data class RemoteThread(
    val peerId: String,
    val unread: Int,
    val lastMessage: RemoteMessage?,
    val updatedAt: Long,
    val favourite: Boolean,
    val blocked: Boolean
)

data class RemotePresence(val accountId: String, val state: String, val lastSeen: Long, val availability: String)

data class RemoteContact(
    val contactAccountId: String,
    val nickname: String?,
    val favourite: Boolean,
    val blocked: Boolean,
    val updatedAt: Long
)

data class MessagePage(val messages: List<RemoteMessage>, val cursor: Long)

class RemoteApi(baseUrl: String, private val transport: RemoteTransport) {
    private val root: String = normalize(baseUrl)

    companion object {
        const val DEFAULT_SERVER_URL =
            "https://wsmrfcxtjkuhdayezczk.supabase.co/functions/v1/messenger-api"

        fun normalize(raw: String): String {
            var value = raw.trim()
            require(value.isNotEmpty()) { "Server URL is required" }
            if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://$value"
            return value.trimEnd('/')
        }
    }

    fun register(
        username: String,
        displayName: String,
        password: String,
        phone: String? = null,
        email: String? = null
    ): RemoteSession {
        val payload = linkedMapOf<String, Any?>(
            "username" to username,
            "displayName" to displayName,
            "password" to password
        )
        if (phone != null) payload["phone"] = phone
        if (email != null) payload["email"] = email
        return session(Json.obj(call("POST", "/v1/accounts", null, payload)))
    }

    fun login(username: String, password: String): RemoteSession =
        session(Json.obj(call("POST", "/v1/sessions", null, mapOf("username" to username, "password" to password))))

    fun refresh(refreshToken: String): RemoteSession =
        session(Json.obj(call("POST", "/v1/sessions/refresh", null, mapOf("refreshToken" to refreshToken))))

    fun logout(token: String) {
        call("DELETE", "/v1/sessions", token, null)
    }

    fun me(token: String): RemoteAccount = account(Json.obj(Json.obj(call("GET", "/v1/me", token, null))["account"]))

    fun account(token: String, accountId: String): RemoteAccount =
        account(Json.obj(Json.obj(call("GET", "/v1/accounts/$accountId", token, null))["account"]))

    fun lookup(token: String, username: String): RemoteAccount? {
        val response = transport.request("GET", root + "/v1/accounts?username=" + java.net.URLEncoder.encode(username, "UTF-8"), token, null)
        if (response.status == 404) return null
        val parsed = if (response.body.isBlank()) null else Json.parse(response.body)
        if (response.status !in 200..299) {
            val error = Json.obj(Json.obj(parsed)["error"])
            throw RemoteException(response.status, Json.str(error["message"]) ?: "Lookup failed")
        }
        return account(Json.obj(Json.obj(parsed)["account"]))
    }

    fun contacts(token: String): List<RemoteContact> =
        Json.arr(Json.obj(call("GET", "/v1/contacts", token, null))["contacts"]).map(::contact)

    fun upsertContact(
        token: String,
        accountId: String,
        favourite: Boolean? = null,
        blocked: Boolean? = null,
        nickname: String? = null
    ) {
        val payload = linkedMapOf<String, Any?>()
        if (favourite != null) payload["favourite"] = favourite
        if (blocked != null) payload["blocked"] = blocked
        if (nickname != null) payload["nickname"] = nickname
        call("PUT", "/v1/contacts/$accountId", token, payload)
    }

    fun send(token: String, to: String, body: String, clientId: String, replyToId: String? = null): RemoteMessage {
        val payload = linkedMapOf<String, Any?>(
            "to" to to,
            "body" to body,
            "clientId" to clientId
        )
        if (replyToId != null) payload["replyToId"] = replyToId
        return message(Json.obj(Json.obj(call("POST", "/v1/messages", token, payload))["message"]))
    }

    fun messages(token: String, peerId: String, since: Long = 0, limit: Int = 200): MessagePage {
        val result = Json.obj(call("GET", "/v1/messages/$peerId?since=$since&limit=$limit", token, null))
        val messages = Json.arr(result["messages"]).map { message(Json.obj(it)) }
        return MessagePage(messages, Json.num(result["cursor"]) ?: since)
    }

    fun markRead(token: String, messageId: String): RemoteMessage =
        message(Json.obj(Json.obj(call("POST", "/v1/messages/$messageId/read", token, null))["message"]))

    fun edit(token: String, messageId: String, body: String): RemoteMessage =
        message(Json.obj(Json.obj(call("PATCH", "/v1/messages/$messageId", token, mapOf("body" to body)))["message"]))

    fun delete(token: String, messageId: String, forEveryone: Boolean = true) {
        call("DELETE", "/v1/messages/$messageId?forEveryone=${if (forEveryone) 1 else 0}", token, null)
    }

    fun react(token: String, messageId: String, emoji: String): RemoteMessage =
        message(Json.obj(Json.obj(call("POST", "/v1/messages/$messageId/reactions", token, mapOf("emoji" to emoji)))["message"]))

    fun unreact(token: String, messageId: String): RemoteMessage =
        message(Json.obj(Json.obj(call("DELETE", "/v1/messages/$messageId/reactions", token, null))["message"]))

    fun deleteContact(token: String, accountId: String) {
        call("DELETE", "/v1/contacts/$accountId", token, null)
    }

    fun threads(token: String): List<RemoteThread> =
        Json.arr(Json.obj(call("GET", "/v1/threads", token, null))["threads"]).map(::thread)

    fun presence(token: String, state: String, availability: String = "Available"): RemotePresence =
        presenceValue(Json.obj(Json.obj(call("POST", "/v1/presence", token, mapOf("state" to state, "availability" to availability)))["presence"]))

    fun presence(token: String, accountId: String): RemotePresence? {
        val result = Json.obj(call("GET", "/v1/presence/$accountId", token, null))
        return if (result["presence"] == null) null else presenceValue(Json.obj(result["presence"]))
    }

    fun sync(token: String, since: Long = 0): SyncPage {
        val result = Json.obj(call("GET", "/v1/sync?since=$since", token, null))
        val events = Json.arr(result["events"]).map {
            val event = Json.obj(it)
            RemoteEvent(
                seq = Json.num(event["seq"]) ?: 0,
                type = Json.str(event["type"]) ?: "unknown",
                payload = Json.obj(event["payload"])
            )
        }
        return SyncPage(events, Json.num(result["cursor"]) ?: since)
    }

    private fun call(method: String, path: String, token: String?, payload: Map<String, Any?>?): Any? {
        val response = transport.request(
            method,
            root + path,
            token,
            payload?.let(Json::stringify)
        )
        val parsed = if (response.body.isBlank()) null else try {
            Json.parse(response.body)
        } catch (error: IllegalArgumentException) {
            null
        }
        if (response.status !in 200..299) {
            val error = Json.obj(Json.obj(parsed)["error"])
            val message = Json.str(error["message"])
                ?: response.body.take(200).ifBlank { "Request failed with status ${response.status}" }
            throw RemoteException(response.status, message)
        }
        return parsed
    }

    private fun session(value: Map<String, Any?>): RemoteSession =
        RemoteSession(
            token = Json.str(value["token"]) ?: throw IllegalStateException("Server did not return a token"),
            account = account(Json.obj(value["account"])),
            refreshToken = Json.str(value["refreshToken"]),
            expiresAt = Json.num(value["expiresAt"]) ?: 0L
        )

    private fun account(value: Map<String, Any?>): RemoteAccount =
        RemoteAccount(
            id = Json.str(value["id"]) ?: throw IllegalStateException("Account id missing"),
            username = Json.str(value["username"]) ?: "",
            displayName = Json.str(value["displayName"]) ?: Json.str(value["username"]) ?: ""
        )

    private fun contact(value: Any?): RemoteContact {
        val item = Json.obj(value)
        return RemoteContact(
            contactAccountId = Json.str(item["contactAccountId"]) ?: "",
            nickname = Json.str(item["nickname"]),
            favourite = Json.bool(item["favourite"]) ?: false,
            blocked = Json.bool(item["blocked"]) ?: false,
            updatedAt = Json.num(item["updatedAt"]) ?: 0
        )
    }

    private fun message(value: Map<String, Any?>): RemoteMessage =
        RemoteMessage(
            id = Json.str(value["id"]) ?: throw IllegalStateException("Message id missing"),
            clientId = Json.str(value["clientId"]) ?: "",
            conversationId = Json.str(value["conversationId"]) ?: "",
            senderId = Json.str(value["senderId"]) ?: "",
            recipientId = Json.str(value["recipientId"]) ?: "",
            body = Json.str(value["body"]) ?: "",
            createdAt = Json.num(value["createdAt"]) ?: 0,
            state = Json.str(value["state"]) ?: "SENT",
            replyToId = Json.str(value["replyToId"]),
            editedAt = Json.num(value["editedAt"]),
            reactions = Json.arr(value["reactions"]).map {
                val reaction = Json.obj(it)
                RemoteReaction(
                    emoji = Json.str(reaction["emoji"]) ?: "",
                    senderId = Json.str(reaction["senderId"]) ?: "",
                    at = Json.num(reaction["at"]) ?: 0
                )
            },
            deleted = Json.bool(value["deleted"]) ?: false,
            expiresAt = Json.num(value["expiresAt"]),
            transcript = Json.str(value["transcript"]),
            seq = Json.num(value["seq"]) ?: 0
        )

    private fun thread(value: Any?): RemoteThread {
        val item = Json.obj(value)
        return RemoteThread(
            peerId = Json.str(item["peerId"]) ?: "",
            unread = (Json.num(item["unread"]) ?: 0).toInt(),
            lastMessage = item["lastMessage"]?.let { message(Json.obj(it)) },
            updatedAt = Json.num(item["updatedAt"]) ?: 0,
            favourite = Json.bool(item["favourite"]) ?: false,
            blocked = Json.bool(item["blocked"]) ?: false
        )
    }

    private fun presenceValue(value: Map<String, Any?>): RemotePresence =
        RemotePresence(
            accountId = Json.str(value["accountId"]) ?: "",
            state = Json.str(value["state"]) ?: "OFFLINE",
            lastSeen = Json.num(value["lastSeen"]) ?: 0,
            availability = Json.str(value["availability"]) ?: ""
        )
}
