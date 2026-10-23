package com.sikorokoro44.messengerpro.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sikorokoro44.messengerpro.engine.FixtureStatus
import com.sikorokoro44.messengerpro.engine.Hashing
import com.sikorokoro44.messengerpro.engine.MessengerRepository
import com.sikorokoro44.messengerpro.engine.RegistryData
import com.sikorokoro44.messengerpro.engine.SettingsEngine
import com.sikorokoro44.messengerpro.model.Account
import com.sikorokoro44.messengerpro.model.DeliveryState
import com.sikorokoro44.messengerpro.model.Message
import com.sikorokoro44.messengerpro.remote.HttpRemoteTransport
import com.sikorokoro44.messengerpro.remote.Json
import com.sikorokoro44.messengerpro.remote.RemoteApi
import com.sikorokoro44.messengerpro.remote.RemoteEvent
import com.sikorokoro44.messengerpro.remote.RemoteSyncEngine

class MessengerController(private val prefs: SharedPreferences) {

    val repo = MessengerRepository()
    private val remoteSync = RemoteSyncEngine(repo.messaging)

    var version by mutableIntStateOf(0)
        private set

    var remoteStatus by mutableStateOf<String?>(null)
        private set

    private var api: RemoteApi? = null
    private var remoteToken: String? = prefs.getString(KEY_TOKEN, null)
    private var remoteRefreshToken: String? = prefs.getString(KEY_REFRESH, null)
    private var remoteExpiresAt: Long = prefs.getLong(KEY_EXPIRES, 0L)
    private var remoteUrl: String? = prefs.getString(KEY_URL, null)
    private var syncCursor: Long = prefs.getLong(KEY_CURSOR, 0L)
    private var lastPresenceAt: Long = 0L

    init {
        repo.hydrate(prefs.getString(KEY_STATE, null))
        if (remoteUrl != null && remoteToken != null) {
            api = RemoteApi(remoteUrl!!, HttpRemoteTransport())
            remoteStatus = "Connected to $remoteUrl"
        }
    }

    private fun persist(): Int {
        prefs.edit().putString(KEY_STATE, repo.persist()).apply()
        version += 1
        return version
    }

    fun register(username: String, displayName: String, password: String): String? {
        val result = repo.register(username, displayName, password)
        if (!result.ok) return result.error
        val login = repo.login(username, password)
        persist()
        return login.error
    }

    fun login(username: String, password: String): String? {
        val result = repo.login(username, password)
        persist()
        return result.error
    }

    fun serverUrl(): String? = remoteUrl

    fun isRemote(): Boolean = remoteToken != null

    fun connectServer(
        url: String,
        createAccount: Boolean,
        username: String,
        displayName: String,
        password: String
    ): String? {
        return try {
            val normalized = RemoteApi.normalize(url)
            val client = RemoteApi(normalized, HttpRemoteTransport())
            val session = if (createAccount) {
                client.register(username, displayName.ifBlank { username }, password)
            } else {
                client.login(username, password)
            }
            api = client
            remoteUrl = normalized
            remoteToken = session.token
            remoteRefreshToken = session.refreshToken
            remoteExpiresAt = session.expiresAt
            syncCursor = 0
            repo.connectRemote(session.token, session.account.id, session.account.username, session.account.displayName)
            prefs.edit()
                .putString(KEY_URL, normalized)
                .putString(KEY_TOKEN, session.token)
                .putString(KEY_REFRESH, session.refreshToken)
                .putLong(KEY_EXPIRES, session.expiresAt)
                .putLong(KEY_CURSOR, 0L)
                .apply()
            val syncError = refreshRemote()
            persist()
            syncError
        } catch (error: Exception) {
            remoteStatus = error.message ?: "Could not reach the server"
            persist()
            remoteStatus
        }
    }

    fun disconnectServer() {
        val token = remoteToken
        if (token != null) api?.let { client -> runCatching { client.logout(token) } }
        clearRemote()
        repo.logout()
        persist()
    }

    private fun clearRemote() {
        remoteToken = null
        remoteRefreshToken = null
        remoteExpiresAt = 0L
        remoteUrl = null
        api = null
        syncCursor = 0
        lastPresenceAt = 0
        remoteStatus = null
        prefs.edit()
            .remove(KEY_URL).remove(KEY_TOKEN).remove(KEY_REFRESH)
            .remove(KEY_EXPIRES).remove(KEY_CURSOR).apply()
    }

    private fun currentToken(): String? {
        val token = remoteToken ?: return null
        val refreshToken = remoteRefreshToken ?: return token
        val client = api ?: return token
        if (remoteExpiresAt <= 0L || remoteExpiresAt - System.currentTimeMillis() > REFRESH_WINDOW_MS) {
            return token
        }
        return runCatching { client.refresh(refreshToken) }
            .onSuccess { session ->
                remoteToken = session.token
                remoteRefreshToken = session.refreshToken ?: refreshToken
                remoteExpiresAt = session.expiresAt
                repo.connectRemote(
                    session.token,
                    session.account.id,
                    session.account.username,
                    session.account.displayName
                )
                prefs.edit()
                    .putString(KEY_TOKEN, session.token)
                    .putString(KEY_REFRESH, session.refreshToken)
                    .putLong(KEY_EXPIRES, session.expiresAt)
                    .apply()
            }
            .onFailure { remoteStatus = it.message ?: "Could not refresh the session" }
            .getOrNull()
            ?.token
            ?: token
    }

    fun refreshRemote(): String? {
        val token = currentToken() ?: return null
        val client = api ?: return "Server is not configured"
        val me = repo.activeAccount() ?: return "Not signed in"
        return try {
            var chats = 0
            for (thread in client.threads(token)) {
                if (thread.peerId == me.id) continue
                client.account(token, thread.peerId).let { repo.rememberRemoteAccount(it.id, it.displayName) }
                client.messages(token, thread.peerId).messages.forEach(remoteSync::apply)
                repo.messaging.setUnread(me.id, thread.peerId, thread.unread)
                chats++
            }
            for (contact in client.contacts(token)) {
                if (contact.contactAccountId == me.id) continue
                if (repo.contacts.get(me.id, contact.contactAccountId) == null) {
                    repo.contacts.add(me.id, contact.contactAccountId)
                }
                runCatching { client.account(token, contact.contactAccountId) }
                    .getOrNull()
                    ?.let { repo.rememberRemoteAccount(it.id, it.displayName) }
                repo.contacts.setFavourite(me.id, contact.contactAccountId, contact.favourite)
                if (contact.blocked) repo.contacts.block(me.id, contact.contactAccountId)
            }
            val page = client.sync(token, syncCursor)
            remoteSync.applyEvents(page.events)
            resolveNames(client, token, page.events, me.id)
            syncCursor = page.cursor
            prefs.edit().putLong(KEY_CURSOR, syncCursor).apply()
            remoteStatus = "Connected - $chats chats, ${page.events.size} new events"
            persist()
            null
        } catch (error: Exception) {
            remoteStatus = error.message ?: "Sync failed"
            persist()
            remoteStatus
        }
    }

    fun pollRemote() {
        val token = currentToken() ?: return
        val client = api ?: return
        val me = repo.activeAccount() ?: return
        var changed = false
        try {
            for (pending in repo.messaging.pending().filter { it.senderId == me.id }) {
                val peer = peerOf(pending)
                if (peer.isBlank()) continue
                runCatching { client.send(token, peer, pending.body, pending.clientId) }
                    .onSuccess { sent ->
                        remoteSync.apply(sent)
                        changed = true
                    }
            }
            val page = client.sync(token, syncCursor)
            if (page.events.isNotEmpty()) {
                remoteSync.applyEvents(page.events)
                resolveNames(client, token, page.events, me.id)
                changed = true
            }
            syncCursor = page.cursor
            prefs.edit().putLong(KEY_CURSOR, syncCursor).apply()
            val now = System.currentTimeMillis()
            if (now - lastPresenceAt > 30_000) {
                runCatching { client.presence(token, "ONLINE") }
                lastPresenceAt = now
            }
            remoteStatus = "Connected - last sync ${page.events.size} events"
        } catch (error: Exception) {
            remoteStatus = error.message ?: "Sync failed"
            changed = true
        }
        if (changed) persist()
    }

    private fun resolveNames(client: RemoteApi, token: String, events: List<RemoteEvent>, meId: String) {
        val ids = LinkedHashSet<String>()
        for (event in events) {
            if (!event.type.startsWith("message.")) continue
            val sender = Json.str(event.payload["senderId"])
            val recipient = Json.str(event.payload["recipientId"])
            if (sender != null && sender != meId) ids += sender
            if (recipient != null && recipient != meId) ids += recipient
        }
        for (id in ids) {
            runCatching { client.account(token, id) }
                .getOrNull()
                ?.let { repo.rememberRemoteAccount(it.id, it.displayName) }
        }
    }

    fun logout() {
        val token = remoteToken
        if (token != null) api?.let { client -> runCatching { client.logout(token) } }
        clearRemote()
        repo.logout()
        persist()
    }

    fun addContact(username: String): String? {
        val client = api
        val token = currentToken()
        if (client != null && token != null) {
            return try {
                val found = client.lookup(token, username) ?: return "No account with that username"
                if (found.id == repo.activeAccount()?.id) return "You cannot add yourself"
                client.upsertContact(token, found.id)
                repo.rememberRemoteAccount(found.id, found.displayName)
                if (!repo.addContact(found.id)) return "That account is unavailable"
                persist()
                null
            } catch (error: Exception) {
                error.message ?: "Could not add that contact"
            }
        }
        val account = repo.auth.findByUsername(username) ?: return "No account with that username"
        if (!repo.addContact(account.id)) return "That account is unavailable"
        persist()
        return null
    }

    fun send(recipientId: String, body: String) {
        if (body.isBlank()) return
        val client = api
        val token = currentToken()
        val clientId = "client-" + Hashing.randomHex(6)
        if (client != null && token != null) {
            try {
                val sent = client.send(token, recipientId, body.trim(), clientId)
                remoteSync.apply(sent)
                repo.activeAccount()?.let { repo.activity.record(it.id, "MESSAGE_SENT", body.take(80), recipientId) }
                remoteStatus = null
            } catch (error: Exception) {
                repo.activeAccount()?.let { me ->
                    repo.messaging.enqueue(me.id, recipientId, body, clientId)
                    repo.activity.record(me.id, "MESSAGE_QUEUED", body.take(80), recipientId)
                }
                remoteStatus = error.message ?: "Message queued for retry"
            }
        } else {
            repo.send(recipientId, body, clientId)
        }
        persist()
    }

    fun markRead(participantId: String) {
        val me = repo.activeAccount() ?: return
        val unread = repo.messaging.messages(me.id, participantId)
            .filter { it.senderId == participantId && it.state != DeliveryState.READ }
        repo.messaging.markRead(me.id, participantId)
        val client = api
        val token = currentToken()
        if (client != null && token != null) {
            for (message in unread) {
                runCatching { client.markRead(token, message.id) }
                    .onSuccess(remoteSync::apply)
            }
        }
        persist()
    }

    fun toggleFavourite(contactAccountId: String) {
        val me = repo.activeAccount() ?: return
        val contact = repo.contacts.get(me.id, contactAccountId) ?: repo.contacts.add(me.id, contactAccountId)
        val next = !contact.favourite
        repo.contacts.setFavourite(me.id, contactAccountId, next)
        val client = api
        val token = currentToken()
        if (client != null && token != null) {
            runCatching { client.upsertContact(token, contactAccountId, favourite = next) }
                .onFailure { remoteStatus = it.message }
        }
        persist()
    }

    fun toggleBlock(contactAccountId: String) {
        val me = repo.activeAccount() ?: return
        val contact = repo.contacts.get(me.id, contactAccountId) ?: repo.contacts.add(me.id, contactAccountId)
        val next = !contact.blocked
        if (next) repo.contacts.block(me.id, contactAccountId) else repo.contacts.unblock(me.id, contactAccountId)
        val client = api
        val token = currentToken()
        if (client != null && token != null) {
            runCatching { client.upsertContact(token, contactAccountId, blocked = next) }
                .onFailure { remoteStatus = it.message }
        }
        persist()
    }

    fun removeContact(contactAccountId: String) {
        val me = repo.activeAccount() ?: return
        repo.contacts.remove(me.id, contactAccountId)
        val client = api
        val token = currentToken()
        if (client != null && token != null) {
            runCatching { client.deleteContact(token, contactAccountId) }
                .onFailure { remoteStatus = it.message }
        }
        persist()
    }

    fun setTheme(theme: SettingsEngine.Theme) {
        repo.settingsEngine.setTheme(theme)
        persist()
    }

    fun toggleSetting(name: String) {
        val s = repo.settingsEngine.settings()
        when (name) {
            "receipts" -> repo.settingsEngine.setReadReceipts(!s.readReceipts)
            "notifications" -> repo.settingsEngine.setNotificationsEnabled(!s.notificationsEnabled)
            "previews" -> repo.settingsEngine.setMessagePreviews(!s.messagePreviews)
            "lastSeen" -> repo.settingsEngine.setLastSeenVisible(!s.lastSeenVisible)
            "online" -> repo.settingsEngine.setOnlineVisible(!s.onlineVisible)
            "ai" -> repo.settingsEngine.setAiEnabled(!s.aiEnabled)
        }
        persist()
    }

    fun account(): Account? = repo.activeAccount()

    fun theme(): SettingsEngine.Theme = repo.settingsEngine.settings().theme

    fun fixtureCounts(): Map<FixtureStatus, Int> =
        FixtureStatus.values().associateWith { status -> RegistryData.fixtures.count { it.implementation == status } }

    fun categoryCounts(): List<Pair<String, Int>> =
        RegistryData.fixtures.groupingBy { it.category }.eachCount().toList().sortedBy { it.first }

    private fun peerOf(message: Message): String {
        val ids = message.conversationId.split(":")
        return ids.firstOrNull { it != message.senderId } ?: ids.lastOrNull().orEmpty()
    }

    companion object {
        private const val KEY_STATE = "messenger_state_v1"
        private const val KEY_URL = "server_url"
        private const val KEY_TOKEN = "server_token"
        private const val KEY_REFRESH = "server_refresh_token"
        private const val KEY_EXPIRES = "server_expires_at"
        private const val KEY_CURSOR = "server_cursor"
        private const val REFRESH_WINDOW_MS = 120_000L
    }
}
