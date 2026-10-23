package com.sikorokoro44.messengerpro.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.sikorokoro44.messengerpro.engine.FixtureStatus
import com.sikorokoro44.messengerpro.engine.MessengerRepository
import com.sikorokoro44.messengerpro.engine.RegistryData
import com.sikorokoro44.messengerpro.engine.SettingsEngine
import com.sikorokoro44.messengerpro.model.Account

class MessengerController(private val prefs: SharedPreferences) {

    val repo = MessengerRepository()

    var version by mutableIntStateOf(0)
        private set

    init {
        repo.hydrate(prefs.getString(KEY_STATE, null))
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

    fun logout() {
        repo.logout()
        persist()
    }

    fun addContact(username: String): String? {
        val account = repo.auth.findByUsername(username) ?: return "No account with that username"
        if (!repo.addContact(account.id)) return "That account is unavailable"
        persist()
        return null
    }

    fun send(recipientId: String, body: String) {
        if (body.isBlank()) return
        repo.send(recipientId, body)
        persist()
    }

    fun markRead(participantId: String) {
        val me = repo.activeAccount() ?: return
        repo.messaging.markRead(me.id, participantId)
        persist()
    }

    fun toggleFavourite(contactAccountId: String) {
        val me = repo.activeAccount() ?: return
        val contact = repo.contacts.get(me.id, contactAccountId) ?: repo.contacts.add(me.id, contactAccountId)
        repo.contacts.setFavourite(me.id, contactAccountId, !contact.favourite)
        persist()
    }

    fun toggleBlock(contactAccountId: String) {
        val me = repo.activeAccount() ?: return
        val contact = repo.contacts.get(me.id, contactAccountId) ?: repo.contacts.add(me.id, contactAccountId)
        if (contact.blocked) repo.contacts.unblock(me.id, contactAccountId)
        else repo.contacts.block(me.id, contactAccountId)
        persist()
    }

    fun removeContact(contactAccountId: String) {
        val me = repo.activeAccount() ?: return
        repo.contacts.remove(me.id, contactAccountId)
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

    companion object {
        private const val KEY_STATE = "messenger_state_v1"
    }
}
