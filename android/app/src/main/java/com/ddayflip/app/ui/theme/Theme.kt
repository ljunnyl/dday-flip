package com.ddayflip.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 앱 비주얼 테마 3종 (야간 수유등 다크 모드 포함)
 */
enum class AppVisualTheme(val title: String, val emoji: String) {
    WARM_COTTON("웜 코튼", "☁️"),
    MINIMAL_WHITE("미니멀 화이트", "🤍"),
    DARK_ROOM("수유등 다크", "🌙")
}

private val WarmCottonColorScheme = lightColorScheme(
    primary = Color(0xFFE06D53),
    onPrimary = Color.White,
    secondary = Color(0xFF2E7D32),
    tertiary = Color(0xFFD97706),
    background = Color(0xFFFAF7F2),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF3EFEA),
    onBackground = Color(0xFF1F2421),
    onSurface = Color(0xFF1F2421),
    onSurfaceVariant = Color(0xFF5C5852)
)

private val MinimalWhiteColorScheme = lightColorScheme(
    primary = Color(0xFF1E293B),
    onPrimary = Color.White,
    secondary = Color(0xFFE06D53),
    tertiary = Color(0xFF475569),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569)
)

private val DarkRoomColorScheme = darkColorScheme(
    primary = Color(0xFFF4A261),
    onPrimary = Color(0xFF0F172A),
    secondary = Color(0xFF81C784),
    tertiary = Color(0xFFE06D53),
    background = Color(0xFF0F172A),
    surface = Color(0xFF1E293B),
    surfaceVariant = Color(0xFF1E293B),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFFCBD5E1)
)

@Composable
fun DdayFlipTheme(
    visualTheme: AppVisualTheme = AppVisualTheme.WARM_COTTON,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when (visualTheme) {
        AppVisualTheme.DARK_ROOM -> DarkRoomColorScheme
        AppVisualTheme.MINIMAL_WHITE -> MinimalWhiteColorScheme
        AppVisualTheme.WARM_COTTON -> WarmCottonColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
