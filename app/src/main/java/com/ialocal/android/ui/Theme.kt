package com.ialocal.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val localColors = darkColorScheme(
    primary = Color(0xFF8EE0BF),
    onPrimary = Color(0xFF082C20),
    primaryContainer = Color(0xFF1A392F),
    onPrimaryContainer = Color(0xFFB5F0D8),
    background = Color(0xFF101514),
    onBackground = Color(0xFFEDF3F0),
    surface = Color(0xFF141B19),
    onSurface = Color(0xFFEDF3F0),
    surfaceVariant = Color(0xFF222F2B),
    surfaceContainer = Color(0xFF19231F),
    surfaceContainerHigh = Color(0xFF23312B),
    onSurfaceVariant = Color(0xFFAFBFB8),
    outline = Color(0xFF6E847A),
    outlineVariant = Color(0xFF364641),
    error = Color(0xFFFFB4AB)
)

@Composable
fun LocalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = localColors, content = content)
}
