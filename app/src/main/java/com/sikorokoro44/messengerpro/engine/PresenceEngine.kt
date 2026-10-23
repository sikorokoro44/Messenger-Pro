package com.sikorokoro44.messengerpro.engine

import com.sikorokoro44.messengerpro.model.Presence
import com.sikorokoro44.messengerpro.model.PresenceState

/**
 * Presence tracking with watchers, quiet hours, duplicate suppression and
 * privacy controls. It models the events a server would push to a client.
 */
class PresenceEngine(private val clock: () -> Long = System::currentTimeMillis) {

    data class Alert(val watcherId: String, val accountId: String, val state: PresenceState, val at: Long)

    private val presence = LinkedHashMap<String, Presence>()
    private val watchers = LinkedHashMap<String, MutableSet<String>>()
    private val alerts = mutableListOf<Alert>()
    private val lastAlert = LinkedHashMap<String, Long>()
    private val hiddenLastSeen = LinkedHashSet<String>()
    private val invisible = LinkedHashSet<String>()

    var minAlertIntervalMs: Long = 5_000
    var quietFromHour: Int? = null
    var quietToHour: Int? = null

    fun update(accountId: String, state: PresenceState, at: Long = clock()) {
        val previous = presence[accountId]
        presence[accountId] = Presence(accountId, state, at, previous?.availability ?: "Available")
        if (previous?.state == state) return
        val watcherSet = watchers[accountId] ?: return
        for (watcher in watcherSet) {
            maybeAlert(watcher, accountId, state, at)
        }
    }

    fun watch(watcherId: String, accountId: String) {
        watchers.getOrPut(accountId) { mutableSetOf() }.add(watcherId)
    }

    fun unwatch(watcherId: String, accountId: String) {
        watchers[accountId]?.remove(watcherId)
    }

    fun setAvailability(accountId: String, availability: String) {
        val current = presence[accountId] ?: Presence(accountId, PresenceState.OFFLINE, clock())
        presence[accountId] = current.copy(availability = availability)
    }

    fun get(accountId: String): Presence? {
        val p = presence[accountId] ?: return null
        if (accountId in invisible) return p.copy(state = PresenceState.OFFLINE)
        return p
    }

    fun setLastSeenHidden(accountId: String, hidden: Boolean) {
        if (hidden) hiddenLastSeen.add(accountId) else hiddenLastSeen.remove(accountId)
    }

    fun setInvisible(accountId: String, invisible: Boolean) {
        if (invisible) this.invisible.add(accountId) else this.invisible.remove(accountId)
    }

    fun canSeeLastSeen(viewerId: String, accountId: String): Boolean = accountId !in hiddenLastSeen

    fun setQuietHours(fromHour: Int?, toHour: Int?) {
        quietFromHour = fromHour
        quietToHour = toHour
    }

    private fun inQuietHours(at: Long): Boolean {
        val from = quietFromHour ?: return false
        val to = quietToHour ?: return false
        val hour = ((at / 3_600_000L) % 24).toInt()
        return if (from <= to) hour in from until to else hour >= from || hour < to
    }

    private fun maybeAlert(watcherId: String, accountId: String, state: PresenceState, at: Long) {
        if (inQuietHours(at)) return
        val key = watcherId + "->" + accountId
        val previous = lastAlert[key]
        if (previous != null && at - previous < minAlertIntervalMs) return
        lastAlert[key] = at
        alerts.add(Alert(watcherId, accountId, state, at))
    }

    fun alerts(watcherId: String): List<Alert> = alerts.filter { it.watcherId == watcherId }.sortedByDescending { it.at }

    fun filterAlerts(watcherId: String, accountId: String? = null, from: Long? = null, to: Long? = null): List<Alert> =
        alerts(watcherId).filter {
            (accountId == null || it.accountId == accountId) &&
                (from == null || it.at >= from) &&
                (to == null || it.at <= to)
        }

    fun clearAlerts(watcherId: String) {
        alerts.removeAll { it.watcherId == watcherId }
    }

    fun watchersOf(accountId: String): Set<String> = watchers[accountId] ?: emptySet()
}
