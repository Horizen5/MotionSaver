package com.motionsaver.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * HyperOS / MIUI「生命感美学」主题：大圆角、通透蓝紫主色、字重字号跳跃。
 * 玻璃质感的实际绘制在 [Glass] 里，这里只负责 Material3 色板与形状，
 * 保证 Button / Switch / TextField 等系统组件自动跟随新风格。
 */
private val LightColors = lightColorScheme(
    primary = Glass.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E8FF),
    onPrimaryContainer = Color(0xFF202A63),
    secondary = Glass.Accent2,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEE6FF),
    onSecondaryContainer = Color(0xFF35235F),
    tertiary = Glass.AccentMint,
    surface = Color.White,
    surfaceVariant = Color(0xFFEFF1FB),
    background = Color(0xFFF4F6FE),
    onSurface = Color(0xFF1C2340),
    onSurfaceVariant = Color(0xFF64708F),
    outline = Color(0xFFC9CFE6),
    error = Color(0xFFE5484D),
    errorContainer = Color(0xFFFFE4E6)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AA8FF),
    onPrimary = Color(0xFF141A3A),
    primaryContainer = Color(0xFF2C3670),
    onPrimaryContainer = Color(0xFFE0E6FF),
    secondary = Color(0xFFC0A4FF),
    onSecondary = Color(0xFF2A1A4A),
    secondaryContainer = Color(0xFF3A2C63),
    tertiary = Color(0xFF6FDCCB),
    surface = Color(0xFF171B29),
    surfaceVariant = Color(0xFF222840),
    background = Color(0xFF10131C),
    onSurface = Color(0xFFF2F4FF),
    onSurfaceVariant = Color(0xFFAAB4D2),
    outline = Color(0xFF39415C),
    error = Color(0xFFFF8A8E),
    errorContainer = Color(0xFF5A2226)
)

/** 大圆角哲学：卡片 24-28dp、按钮胶囊、磁贴 20dp。 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val AppTypography = Typography(
    headlineMedium = Typography().headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = Typography().titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Typography().titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Typography().labelSmall.copy(letterSpacing = 0.2.sp)
)

@Composable
fun MotionSaverTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content
    )
}
