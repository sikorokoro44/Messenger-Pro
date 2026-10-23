package com.sikorokoro44.messengerpro.engine

import com.sikorokoro44.messengerpro.model.Contact

/** Contact list, groups, favourites, blocking and discovery. */
class ContactEngine {

    private val contacts = LinkedHashMap<String, Contact>()
    private val groups = LinkedHashMap<String, MutableSet<String>>()
    private val groupNames = LinkedHashMap<String, String>()

    private fun key(ownerId: String, contactAccountId: String) = ownerId + "->" + contactAccountId

    fun add(ownerId: String, contactAccountId: String): Contact {
        if (ownerId == contactAccountId) throw IllegalArgumentException("Cannot add yourself")
        val existing = contacts[key(ownerId, contactAccountId)]
        if (existing != null) return existing
        val contact = Contact(
            id = "contact-" + Hashing.randomHex(6),
            ownerId = ownerId,
            contactAccountId = contactAccountId
        )
        contacts[key(ownerId, contactAccountId)] = contact
        return contact
    }

    fun remove(ownerId: String, contactAccountId: String) {
        contacts.remove(key(ownerId, contactAccountId))
    }

    fun get(ownerId: String, contactAccountId: String): Contact? = contacts[key(ownerId, contactAccountId)]

    fun list(ownerId: String, includeHidden: Boolean = false): List<Contact> =
        contacts.values.filter { it.ownerId == ownerId && (includeHidden || !it.hidden) }
            .sortedWith(compareByDescending<Contact> { it.favourite }.thenBy { it.nickname ?: it.contactAccountId })

    fun search(ownerId: String, term: String): List<Contact> {
        val q = term.lowercase()
        return list(ownerId, includeHidden = true).filter {
            it.contactAccountId.lowercase().contains(q) || (it.nickname?.lowercase()?.contains(q) == true)
        }
    }

    fun setFavourite(ownerId: String, contactAccountId: String, favourite: Boolean) =
        update(ownerId, contactAccountId) { it.copy(favourite = favourite) }

    fun setNickname(ownerId: String, contactAccountId: String, nickname: String?) =
        update(ownerId, contactAccountId) { it.copy(nickname = nickname?.trim()?.takeIf { n -> n.isNotEmpty() }) }

    fun setNote(ownerId: String, contactAccountId: String, note: String?) =
        update(ownerId, contactAccountId) { it.copy(note = note) }

    fun setRingtone(ownerId: String, contactAccountId: String, ringtone: String?) =
        update(ownerId, contactAccountId) { it.copy(customRingtone = ringtone) }

    fun block(ownerId: String, contactAccountId: String) = update(ownerId, contactAccountId) { it.copy(blocked = true) }
    fun unblock(ownerId: String, contactAccountId: String) = update(ownerId, contactAccountId) { it.copy(blocked = false) }
    fun hide(ownerId: String, contactAccountId: String) = update(ownerId, contactAccountId) { it.copy(hidden = true) }
    fun unhide(ownerId: String, contactAccountId: String) = update(ownerId, contactAccountId) { it.copy(hidden = false) }

    fun isBlocked(ownerId: String, contactAccountId: String): Boolean =
        contacts[key(ownerId, contactAccountId)]?.blocked == true

    fun blocked(ownerId: String): List<Contact> = contacts.values.filter { it.ownerId == ownerId && it.blocked }

    fun createGroup(ownerId: String, name: String): String {
        val id = "group-" + Hashing.randomHex(6)
        groupNames[id] = name
        groups[id] = mutableSetOf()
        return id
    }

    fun renameGroup(ownerId: String, groupId: String, name: String) {
        if (groupNames.containsKey(groupId)) groupNames[groupId] = name
    }

    fun groupName(groupId: String): String? = groupNames[groupId]

    fun addToGroup(ownerId: String, groupId: String, contactAccountId: String) {
        groups.getOrPut(groupId) { mutableSetOf() }.add(contactAccountId)
        update(ownerId, contactAccountId) { it.copy(groupIds = it.groupIds + groupId) }
    }

    fun removeFromGroup(ownerId: String, groupId: String, contactAccountId: String) {
        groups[groupId]?.remove(contactAccountId)
        update(ownerId, contactAccountId) { it.copy(groupIds = it.groupIds - groupId) }
    }

    fun members(groupId: String): Set<String> = groups[groupId] ?: emptySet()

    private fun update(ownerId: String, contactAccountId: String, transform: (Contact) -> Contact) {
        val existing = contacts[key(ownerId, contactAccountId)] ?: add(ownerId, contactAccountId)
        contacts[key(ownerId, contactAccountId)] = transform(existing)
    }
    fun exportContacts(): List<Contact> = contacts.values.toList()

    fun importContact(contact: Contact) {
        contacts[contact.ownerId + "->" + contact.contactAccountId] = contact
    }
}
