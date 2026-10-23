package com.sikorokoro44.messengerpro.engine

import com.sikorokoro44.messengerpro.model.Account
import com.sikorokoro44.messengerpro.model.ActivityEvent
import com.sikorokoro44.messengerpro.model.Contact
import com.sikorokoro44.messengerpro.model.Conversation
import com.sikorokoro44.messengerpro.model.DeliveryState
import com.sikorokoro44.messengerpro.model.Message
import com.sikorokoro44.messengerpro.model.Reaction

/**
 * Deterministic, checksummed serialisation of the whole local store. It is plain
 * text so it round-trips through SharedPreferences without any Android types and
 * can be asserted in JVM tests.
 */
object StateCodec {

    data class Snapshot(
        val accounts: List<Account> = emptyList(),
        val conversations: List<Conversation> = emptyList(),
        val messages: List<Message> = emptyList(),
        val contacts: List<Contact> = emptyList(),
        val events: List<ActivityEvent> = emptyList(),
        val settings: SettingsEngine.Settings = SettingsEngine.Settings(),
        val sessionToken: String? = null,
        val sessionAccountId: String? = null
    )

    private const val VERSION = "MP-STATE-1"
    private const val FS = '\u001F'
    private const val RS = '\u001D'
    private const val US = '\u001E'

    fun encode(snapshot: Snapshot): String {
        val body = buildString {
            append(section("accounts", snapshot.accounts.map { a ->
                listOf(a.id, a.username, a.displayName, a.salt, a.passwordHash, a.createdAt.toString(),
                    a.status, a.phone, a.email, a.bio)
            }))
            append(US)
            append(section("conversations", snapshot.conversations.map { c ->
                listOf(c.id, c.ownerId, c.participantId, c.pinned.toString(), c.archived.toString(),
                    c.muted.toString(), c.unread.toString(), c.folder, c.draft, c.isGroup.toString(), c.title)
            }))
            append(US)
            append(section("messages", snapshot.messages.map { m ->
                listOf(m.id, m.clientId, m.conversationId, m.senderId, m.body, m.createdAt.toString(),
                    m.state.name, m.replyToId, m.editedAt?.toString(), m.deleted.toString(),
                    m.expiresAt?.toString(), m.transcript,
                    m.reactions.joinToString(",") { it.emoji + "|" + it.senderId })
            }))
            append(US)
            append(section("contacts", snapshot.contacts.map { c ->
                listOf(c.id, c.ownerId, c.contactAccountId, c.nickname, c.note, c.favourite.toString(),
                    c.blocked.toString(), c.hidden.toString(), c.groupIds.joinToString(","), c.customRingtone)
            }))
            append(US)
            append(section("events", snapshot.events.map { e ->
                listOf(e.id, e.ownerId, e.type, e.detail, e.contactId, e.at.toString())
            }))
            append(US)
            val s = snapshot.settings
            append(section("settings", listOf(listOf(s.theme.name, s.accentColor.toString(), s.fontScale.toString(),
                s.language, s.readReceipts.toString(), s.lastSeenVisible.toString(), s.onlineVisible.toString(),
                s.messagePreviews.toString(), s.notificationsEnabled.toString(), s.aiEnabled.toString(),
                s.lowDataMode.toString()))))
            append(US)
            append(section("session", listOf(listOf(snapshot.sessionToken, snapshot.sessionAccountId))))
        }
        return "$VERSION$US${Hashing.sha256(body)}$US$body"
    }

    private fun section(name: String, rows: List<List<String?>>): String {
        val encoded = rows.joinToString(RS.toString()) { row -> row.joinToString(FS.toString()) { esc(it) } }
        return "$name$FS$encoded"
    }

    fun decode(text: String?): Snapshot {
        if (text.isNullOrBlank()) return Snapshot()
        val parts = text.split(US, limit = 3)
        if (parts.size != 3 || parts[0] != VERSION) return Snapshot()
        if (Hashing.sha256(parts[2]) != parts[1]) return Snapshot()
        val sections = parts[2].split(US).associate { raw ->
            val idx = raw.indexOf(FS)
            if (idx < 0) raw to "" else raw.substring(0, idx) to raw.substring(idx + 1)
        }
        fun rows(name: String): List<List<String?>> {
            val raw = sections[name] ?: return emptyList()
            if (raw.isEmpty()) return emptyList()
            return raw.split(RS).map { line -> line.split(FS).map { unesc(it) } }
        }
        fun List<String?>.at(i: Int): String? = if (i < size) this[i] else null

        val accounts = rows("accounts").map {
            Account(
                id = it.at(0).orEmpty(), username = it.at(1).orEmpty(), displayName = it.at(2).orEmpty(),
                salt = it.at(3).orEmpty(), passwordHash = it.at(4).orEmpty(), createdAt = it.at(5)?.toLongOrNull() ?: 0L,
                status = it.at(6) ?: "ACTIVE", phone = it.at(7), email = it.at(8), bio = it.at(9)
            )
        }
        val conversations = rows("conversations").map {
            Conversation(
                id = it.at(0).orEmpty(), ownerId = it.at(1).orEmpty(), participantId = it.at(2).orEmpty(),
                pinned = it.at(3)?.toBooleanStrictOrNull() ?: false, archived = it.at(4)?.toBooleanStrictOrNull() ?: false,
                muted = it.at(5)?.toBooleanStrictOrNull() ?: false, unread = it.at(6)?.toIntOrNull() ?: 0,
                folder = it.at(7), draft = it.at(8), isGroup = it.at(9)?.toBooleanStrictOrNull() ?: false, title = it.at(10)
            )
        }
        val messages = rows("messages").map {
            Message(
                id = it.at(0).orEmpty(), clientId = it.at(1).orEmpty(), conversationId = it.at(2).orEmpty(),
                senderId = it.at(3).orEmpty(), body = it.at(4).orEmpty(), createdAt = it.at(5)?.toLongOrNull() ?: 0L,
                state = runCatching { DeliveryState.valueOf(it.at(6) ?: "SENT") }.getOrDefault(DeliveryState.SENT),
                replyToId = it.at(7), editedAt = it.at(8)?.toLongOrNull(),
                deleted = it.at(9)?.toBooleanStrictOrNull() ?: false, expiresAt = it.at(10)?.toLongOrNull(),
                transcript = it.at(11),
                reactions = (it.at(12) ?: "").split(",").filter { r -> r.contains("|") }.map { r ->
                    val i = r.indexOf('|'); Reaction(r.substring(0, i), r.substring(i + 1))
                }
            )
        }
        val contacts = rows("contacts").map {
            Contact(
                id = it.at(0).orEmpty(), ownerId = it.at(1).orEmpty(), contactAccountId = it.at(2).orEmpty(),
                nickname = it.at(3), note = it.at(4), favourite = it.at(5)?.toBooleanStrictOrNull() ?: false,
                blocked = it.at(6)?.toBooleanStrictOrNull() ?: false, hidden = it.at(7)?.toBooleanStrictOrNull() ?: false,
                groupIds = (it.at(8) ?: "").split(",").filter { g -> g.isNotEmpty() }.toSet(), customRingtone = it.at(9)
            )
        }
        val events = rows("events").map {
            ActivityEvent(
                id = it.at(0).orEmpty(), ownerId = it.at(1).orEmpty(), type = it.at(2).orEmpty(),
                detail = it.at(3).orEmpty(), contactId = it.at(4), at = it.at(5)?.toLongOrNull() ?: 0L
            )
        }
        val settingsRow = rows("settings").firstOrNull()
        val defaults = SettingsEngine.Settings()
        val settings = if (settingsRow == null) defaults else SettingsEngine.Settings(
            theme = runCatching { SettingsEngine.Theme.valueOf(settingsRow.at(0) ?: "SYSTEM") }.getOrDefault(defaults.theme),
            accentColor = settingsRow.at(1)?.toIntOrNull() ?: defaults.accentColor,
            fontScale = settingsRow.at(2)?.toFloatOrNull() ?: defaults.fontScale,
            language = settingsRow.at(3) ?: defaults.language,
            readReceipts = settingsRow.at(4)?.toBooleanStrictOrNull() ?: defaults.readReceipts,
            lastSeenVisible = settingsRow.at(5)?.toBooleanStrictOrNull() ?: defaults.lastSeenVisible,
            onlineVisible = settingsRow.at(6)?.toBooleanStrictOrNull() ?: defaults.onlineVisible,
            messagePreviews = settingsRow.at(7)?.toBooleanStrictOrNull() ?: defaults.messagePreviews,
            notificationsEnabled = settingsRow.at(8)?.toBooleanStrictOrNull() ?: defaults.notificationsEnabled,
            aiEnabled = settingsRow.at(9)?.toBooleanStrictOrNull() ?: defaults.aiEnabled,
            lowDataMode = settingsRow.at(10)?.toBooleanStrictOrNull() ?: defaults.lowDataMode
        )
        val sessionRow = rows("session").firstOrNull()
        val sessionToken = sessionRow?.at(0)?.takeIf { it.isNotEmpty() }
        val sessionAccountId = sessionRow?.at(1)?.takeIf { it.isNotEmpty() }
        return Snapshot(accounts, conversations, messages, contacts, events, settings, sessionToken, sessionAccountId)
    }

    private fun esc(value: String?): String {
        if (value == null) return "\u0000"
        return value
            .replace("\\", "\\\\")
            .replace("\u0000", "\\0")
            .replace(FS.toString(), "\\f")
            .replace(RS.toString(), "\\r")
            .replace(US.toString(), "\\u")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    private fun unesc(value: String?): String? {
        if (value == null || value == "\u0000") return null
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> out.append('\\')
                    '0' -> out.append('\u0000')
                    'f' -> out.append(FS)
                    'r' -> out.append(RS)
                    'u' -> out.append(US)
                    'n' -> out.append('\n')
                    else -> out.append(value[i + 1])
                }
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }
}
