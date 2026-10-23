package com.sikorokoro44.messengerpro.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsEngineTest {

    @Test
    fun defaultsAndClamping() {
        val settings = SettingsEngine()
        assertEquals(SettingsEngine.Theme.SYSTEM, settings.settings().theme)
        assertEquals(1.0f, settings.settings().fontScale, 0.0001f)
        settings.setFontScale(9f)
        assertEquals(1.6f, settings.settings().fontScale, 0.0001f)
        settings.setFontScale(0.1f)
        assertEquals(0.8f, settings.settings().fontScale, 0.0001f)
    }

    @Test
    fun togglesAllPreferences() {
        val settings = SettingsEngine()
        settings.setTheme(SettingsEngine.Theme.DARK)
        settings.setAccentColor(0xFF00FF00.toInt())
        settings.setLanguage("zu")
        settings.setReadReceipts(false)
        settings.setLastSeenVisible(false)
        settings.setOnlineVisible(false)
        settings.setMessagePreviews(false)
        settings.setNotificationsEnabled(false)
        settings.setAiEnabled(false)
        settings.setLowDataMode(true)
        val s = settings.settings()
        assertEquals(SettingsEngine.Theme.DARK, s.theme)
        assertEquals(0xFF00FF00.toInt(), s.accentColor)
        assertEquals("zu", s.language)
        assertFalse(s.readReceipts)
        assertFalse(s.lastSeenVisible)
        assertFalse(s.onlineVisible)
        assertFalse(s.messagePreviews)
        assertFalse(s.notificationsEnabled)
        assertFalse(s.aiEnabled)
        assertTrue(s.lowDataMode)
    }

    @Test
    fun exportAndImport() {
        val settings = SettingsEngine()
        settings.setTheme(SettingsEngine.Theme.LIGHT)
        settings.setAccentColor(123)
        settings.setFontScale(1.2f)
        settings.setReadReceipts(false)
        val exported = settings.export()

        val restored = SettingsEngine()
        restored.setTheme(SettingsEngine.Theme.DARK)
        restored.import(exported)
        assertEquals(SettingsEngine.Theme.LIGHT, restored.settings().theme)
        assertEquals(123, restored.settings().accentColor)
        assertEquals(1.2f, restored.settings().fontScale, 0.0001f)
        assertFalse(restored.settings().readReceipts)
    }
}
