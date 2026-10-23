package com.sikorokoro44.messengerpro.engine

import com.sikorokoro44.messengerpro.model.Account
import com.sikorokoro44.messengerpro.model.DeliveryState
import com.sikorokoro44.messengerpro.model.Message
import com.sikorokoro44.messengerpro.model.Session

/**
 * Single entry point used by the UI. It wires the individual engines together so
 * that sending a message also enforces blocks, records activity and updates the
 * conversation list consistently.
 */
class MessengerRepository(
    private val clock: () -> Long = System::currentTimeMillis
) {
    val auth = AuthEngine(clock)
    val messaging = MessagingEngine(clock)
    val contacts = ContactEngine()
    val activity = ActivityEngine(clock)
    val presence = PresenceEngine(clock)
    val settingsEngine = SettingsEngine()

    var activeSession: Session? = null
        private set

    private val remoteNames = LinkedHashMap<String, String>()

    fun register(username: String, displayName: String, password: String): AuthEngine.Result<Account> {
        val result = auth.register(username, displayName, password)
        result.value?.let { activity.record(it.id, "ACCOUNT_CREATED", "Registered ${it.username}") }
        return result
    }

    fun login(username: String, password: String): AuthEngine.Result<Session> {
        val result = auth.login(username, password)
        result.value?.let { session ->
            activeSession = session
            auth.findById(session.accountId)?.let { account ->
                presence.update(account.id, com.sikorokoro44.messengerpro.model.PresenceState.ONLINE)
                activity.record(account.id, "LOGIN", "Signed in")
            }
        }
        return result
    }

    fun logout() {
        activeSession?.let { session ->
            activity.record(session.accountId, "LOGOUT", "Signed out")
            presence.update(session.accountId, com.sikorokoro44.messengerpro.model.PresenceState.OFFLINE)
            auth.logout(session.token)
        }
        activeSession = null
    }

    fun activeAccount(): Account? = activeSession?.let { auth.findById(it.accountId) }

    fun addContact(contactAccountId: String): Boolean {
        val me = activeAccount() ?: return false
        if (contacts.isBlocked(contactAccountId, me.id)) return false
        contacts.add(me.id, contactAccountId)
        activity.record(me.id, "CONTACT_ADDED", "Added $contactAccountId", contactAccountId)
        return true
    }

    fun send(recipientId: String, body: String, clientId: String = "client-" + Hashing.randomHex(6)): Message? {
        val me = activeAccount() ?: return null
        if (contacts.isBlocked(recipientId, me.id)) return null
        val message = messaging.sendMessage(me.id, recipientId, body, clientId)
        activity.record(me.id, "MESSAGE_SENT", body.take(80), recipientId)
        return message
    }

    fun receiveVisible(participantId: String): List<Message> {
        val me = activeAccount() ?: return emptyList()
        return messaging.messages(me.id, participantId)
    }

    fun snapshot(): StateCodec.Snapshot = StateCodec.Snapshot(
        accounts = auth.exportAccounts(),
        conversations = messaging.exportConversations(),
        messages = messaging.exportMessages(),
        contacts = contacts.exportContacts(),
        events = activity.exportEvents(),
        settings = settingsEngine.settings(),
        sessionToken = activeSession?.token,
        sessionAccountId = activeSession?.accountId,
        remoteNames = remoteNames.toMap()
    )

    fun restore(snapshot: StateCodec.Snapshot) {
        snapshot.accounts.forEach(auth::importAccount)
        messaging.importConversations(snapshot.conversations)
        messaging.importMessages(snapshot.messages)
        snapshot.contacts.forEach(contacts::importContact)
        activity.importEvents(snapshot.events)
        settingsEngine.replace(snapshot.settings)
        if (snapshot.sessionToken != null) {
            activeSession = Session(snapshot.sessionToken, snapshot.sessionAccountId ?: "", clock())
        }
        remoteNames.clear()
        remoteNames.putAll(snapshot.remoteNames)
    }

    fun rememberRemoteAccount(id: String, displayName: String) {
        if (id.isNotBlank()) remoteNames[id] = displayName
    }

    fun remoteName(id: String): String? = remoteNames[id]

    fun connectRemote(token: String, id: String, username: String, displayName: String) {
        auth.importAccount(
            Account(
                id = id,
                username = username,
                displayName = displayName,
                passwordHash = "remote",
                salt = "remote",
                createdAt = clock(),
                status = "ACTIVE"
            )
        )
        rememberRemoteAccount(id, displayName)
        activeSession = Session(token, id, clock())
        presence.update(id, com.sikorokoro44.messengerpro.model.PresenceState.ONLINE)
    }

    fun persist(): String = StateCodec.encode(snapshot())

    fun hydrate(serialised: String?): Boolean {
        val snapshot = StateCodec.decode(serialised)
        if (snapshot.accounts.isEmpty() && snapshot.messages.isEmpty() && snapshot.events.isEmpty()) return false
        restore(snapshot)
        return true
    }

    fun deliveryState(clientId: String): DeliveryState? =
        messaging.exportMessages().firstOrNull { it.clientId == clientId }?.state
}
