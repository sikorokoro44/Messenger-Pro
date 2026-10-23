package com.sikorokoro44.messengerpro.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupEngineTest {

    @Test
    fun roundTripsPayload() {
        val backup = BackupEngine()
        val created = backup.create("{\"messages\":3}", createdAt = 42)
        val text = backup.serialise(created)
        val parsed = backup.parse(text)
        assertTrue(parsed.ok)
        assertEquals(created.payload, parsed.backup!!.payload)
        assertEquals(created.checksum, parsed.backup!!.checksum)
        assertEquals(42L, parsed.backup!!.createdAt)
        assertEquals(created.bytes, backup.size(parsed.backup!!))
    }

    @Test
    fun rejectsCorruption() {
        val backup = BackupEngine()
        val text = backup.serialise(backup.create("hello"))
        assertFalse(backup.parse("garbage").ok)
        assertFalse(backup.parse(text.replace("MESSENGERPRO-BACKUP", "OTHER")).ok)
        assertFalse(backup.parse(text.replace("version=1", "version=9")).ok)
        assertFalse(backup.parse(text.replace("hello", "hellp")).ok)
    }

    @Test
    fun checksumChangesWithPayload() {
        val backup = BackupEngine()
        assertNotEquals(backup.create("a").checksum, backup.create("b").checksum)
        assertEquals(backup.create("a").checksum, backup.create("a").checksum)
    }
}
