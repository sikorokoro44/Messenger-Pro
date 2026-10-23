package com.sikorokoro44.messengerpro.engine

import com.sikorokoro44.messengerpro.model.ActivityEvent

/** Append-only activity log with filters, summaries and export. */
class ActivityEngine(private val clock: () -> Long = System::currentTimeMillis) {

    private val events = mutableListOf<ActivityEvent>()

    fun record(ownerId: String, type: String, detail: String, contactId: String? = null): ActivityEvent {
        val event = ActivityEvent(
            id = "evt-" + Hashing.randomHex(6),
            ownerId = ownerId,
            type = type,
            detail = detail,
            contactId = contactId,
            at = clock()
        )
        events.add(event)
        return event
    }

    fun all(ownerId: String): List<ActivityEvent> = events.filter { it.ownerId == ownerId }.sortedByDescending { it.at }

    fun since(ownerId: String, sinceMs: Long): List<ActivityEvent> = all(ownerId).filter { it.at >= sinceMs }

    fun last24Hours(ownerId: String, now: Long = clock()) = since(ownerId, now - 24 * 3_600_000L)
    fun last7Days(ownerId: String, now: Long = clock()) = since(ownerId, now - 7 * 86_400_000L)
    fun last30Days(ownerId: String, now: Long = clock()) = since(ownerId, now - 30 * 86_400_000L)

    fun filter(ownerId: String, type: String? = null, contactId: String? = null): List<ActivityEvent> =
        all(ownerId).filter { (type == null || it.type == type) && (contactId == null || it.contactId == contactId) }

    fun search(ownerId: String, term: String): List<ActivityEvent> {
        val q = term.lowercase()
        return all(ownerId).filter { it.detail.lowercase().contains(q) || it.type.lowercase().contains(q) }
    }

    fun summary(ownerId: String, sinceMs: Long): Map<String, Int> =
        since(ownerId, sinceMs).groupingBy { it.type }.eachCount()

    fun dailySummary(ownerId: String, now: Long = clock()) = summary(ownerId, now - 86_400_000L)
    fun weeklySummary(ownerId: String, now: Long = clock()) = summary(ownerId, now - 7 * 86_400_000L)
    fun monthlySummary(ownerId: String, now: Long = clock()) = summary(ownerId, now - 30 * 86_400_000L)

    fun export(ownerId: String): String =
        all(ownerId).joinToString("\n") { "${it.at}\t${it.type}\t${it.contactId ?: "-"}\t${it.detail}" }

    fun clearOlderThan(ownerId: String, cutoff: Long): Int {
        val before = events.size
        events.removeAll { it.ownerId == ownerId && it.at < cutoff }
        return before - events.size
    }
    fun exportEvents(): List<ActivityEvent> = events.toList()

    fun importEvents(list: List<ActivityEvent>) {
        events.clear()
        events.addAll(list)
    }
}
