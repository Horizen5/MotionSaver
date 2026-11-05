package com.motionsaver.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F51B5),
    primaryContainer = Color(0xFFE3E7FF),
    secondary = Color(0xFF00695C)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C2FF),
    primaryContainer = Color(0xFF2A3666),
    secondary = Color(0xFF80CBC4)
)

@Composable
fun MotionSaverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}
