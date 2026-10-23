package com.sikorokoro44.messengerpro.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryTest {

    @Test
    fun tracksExactlySixHundredFixturesInTwentyFiveCategories() {
        assertEquals(600, RegistryData.fixtures.size)
        assertEquals(25, RegistryData.fixtures.map { it.category }.distinct().size)
    }

    @Test
    fun idsAreSequentialAndUnique() {
        val ids = RegistryData.fixtures.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        RegistryData.fixtures.forEachIndexed { index, fixture ->
            assertEquals("MP-${(index + 1).toString().padStart(3, '0')}", fixture.id)
        }
    }

    @Test
    fun everyFixtureHasANameAndCategory() {
        RegistryData.fixtures.forEach {
            assertTrue(it.name.isNotBlank())
            assertTrue(it.category.isNotBlank())
        }
        assertTrue(RegistryData.fixtures.all { it.test != FixtureStatus.COMPLETE || it.implementation == FixtureStatus.COMPLETE })
    }
}
