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
 * Miuix / HyperOS 系统应用主题（对齐 LSPosed 管理器真机截图）。
 * 材质绘制在 [Glass]，这里只负责 Material3 色板与形状，
 * 保证 Button / Switch / Checkbox / TextField 等系统组件自动跟随，
 * 不出现系统控件默认色与卡片叠加出的白色方块。
 * 真机采样：background #F7F7F7、surface #FFFFFF、primary #3485FF。
 */
private val LightColors = lightColorScheme(
    primary = Glass.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBFF),
    onPrimaryContainer = Color(0xFF0A3D78),
    secondary = Glass.Accent2,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8F0FD),
    onSecondaryContainer = Color(0xFF123A6B),
    tertiary = Glass.Accent,
    surface = Color.White,
    surfaceVariant = Color(0xFFF0F0F2),
    background = Glass.PageBg,
    onSurface = Glass.TextMain,
    onSurfaceVariant = Glass.TextSub,
    outline = Color(0xFFE5E5EA),
    error = Color(0xFFE5484D),
    errorContainer = Color(0xFFFFE4E6)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF82B0FF),
    onPrimary = Color(0xFF0A2547),
    primaryContainer = Color(0xFF22466F),
    onPrimaryContainer = Color(0xFFDCEBFF),
    secondary = Color(0xFF6FA0FF),
    onSecondary = Color(0xFF0A2547),
    secondaryContainer = Color(0xFF1C3A60),
    onSecondaryContainer = Color(0xFFDCEBFF),
    tertiary = Color(0xFF6FA0FF),
    surface = Glass.CardBgDark,
    surfaceVariant = Color(0xFF2E323B),
    background = Glass.PageBgDark,
    onSurface = Glass.TextMainDark,
    onSurfaceVariant = Glass.TextSubDark,
    outline = Color(0xFF3C414B),
    error = Color(0xFFFF8A8E),
    errorContainer = Color(0xFF5A2226)
)

/** Miuix 大圆角哲学：卡片 20dp、磁贴 16dp、按钮胶囊。 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
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
