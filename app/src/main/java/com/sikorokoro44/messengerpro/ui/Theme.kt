package com.sikorokoro44.messengerpro.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.sikorokoro44.messengerpro.engine.SettingsEngine

private val Light = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF00695C),
    background = Color(0xFFF7F9FC),
    surface = Color.White
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DC2FF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF00497F),
    secondary = Color(0xFF6FD6C6),
    background = Color(0xFF101418),
    surface = Color(0xFF161B22)
)

@Composable
fun MessengerProTheme(theme: SettingsEngine.Theme, content: @Composable () -> Unit) {
    val dark = when (theme) {
        SettingsEngine.Theme.LIGHT -> false
        SettingsEngine.Theme.DARK -> true
        SettingsEngine.Theme.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
