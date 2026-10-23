package com.sikorokoro44.messengerpro.engine

/** User preferences: theme, appearance, privacy, notifications and language. */
class SettingsEngine {

    enum class Theme { LIGHT, DARK, SYSTEM }

    data class Settings(
        val theme: Theme = Theme.SYSTEM,
        val accentColor: Int = 0xFF1565C0.toInt(),
        val fontScale: Float = 1.0f,
        val language: String = "en",
        val readReceipts: Boolean = true,
        val lastSeenVisible: Boolean = true,
        val onlineVisible: Boolean = true,
        val messagePreviews: Boolean = true,
        val notificationsEnabled: Boolean = true,
        val aiEnabled: Boolean = true,
        val lowDataMode: Boolean = false
    )

    private var current = Settings()

    fun settings(): Settings = current

    fun replace(settings: Settings) { current = settings }

    fun setTheme(theme: Theme) { current = current.copy(theme = theme) }
    fun setAccentColor(color: Int) { current = current.copy(accentColor = color) }
    fun setFontScale(scale: Float) { current = current.copy(fontScale = scale.coerceIn(0.8f, 1.6f)) }
    fun setLanguage(language: String) { current = current.copy(language = language) }
    fun setReadReceipts(enabled: Boolean) { current = current.copy(readReceipts = enabled) }
    fun setLastSeenVisible(visible: Boolean) { current = current.copy(lastSeenVisible = visible) }
    fun setOnlineVisible(visible: Boolean) { current = current.copy(onlineVisible = visible) }
    fun setMessagePreviews(enabled: Boolean) { current = current.copy(messagePreviews = enabled) }
    fun setNotificationsEnabled(enabled: Boolean) { current = current.copy(notificationsEnabled = enabled) }
    fun setAiEnabled(enabled: Boolean) { current = current.copy(aiEnabled = enabled) }
    fun setLowDataMode(enabled: Boolean) { current = current.copy(lowDataMode = enabled) }

    fun export(): String = listOf(
        "theme=${current.theme}",
        "accent=${current.accentColor}",
        "fontScale=${current.fontScale}",
        "language=${current.language}",
        "readReceipts=${current.readReceipts}",
        "lastSeen=${current.lastSeenVisible}",
        "online=${current.onlineVisible}"
    ).joinToString("\n")

    fun import(data: String) {
        val map = data.lines().mapNotNull {
            val idx = it.indexOf('=')
            if (idx < 0) null else it.substring(0, idx) to it.substring(idx + 1)
        }.toMap()
        map["theme"]?.let { runCatching { Theme.valueOf(it) }.getOrNull()?.let(::setTheme) }
        map["accent"]?.toIntOrNull()?.let(::setAccentColor)
        map["fontScale"]?.toFloatOrNull()?.let(::setFontScale)
        map["language"]?.let(::setLanguage)
        map["readReceipts"]?.toBooleanStrictOrNull()?.let(::setReadReceipts)
    }
}
