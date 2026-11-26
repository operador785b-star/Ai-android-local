package com.ialocal.android.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ialocal.android.R
import com.ialocal.android.chat.AppearanceMode
import com.ialocal.android.chat.AppearanceSettings

internal val Violet = Color(0xFF8B5CF6)
internal val DeepViolet = Color(0xFF6D28D9)
internal val ElectricBlue = Color(0xFF3B82F6)
internal val BrightBlue = Color(0xFF60A5FA)

internal data class GlassColors(
    val dark: Boolean,
    val canvas: Color,
    val ink: Color,
    val softInk: Color,
    val accent: Color,
    val sheet: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val edge: Color,
    val divider: Color
)

private val midnight = GlassColors(
    dark = true, canvas = Color(0xFF080B18), ink = Color(0xFFF5F3FF),
    softInk = Color(0xFFB1B6D0), accent = Color(0xFFBCA7FF),
    sheet = Color(0xEE10172D), glassTop = Color(0xA32A304D),
    glassBottom = Color(0xB3151C34), edge = Color(0xFFB7C5FF), divider = Color(0x2EA3AFDF)
)
private val daylight = GlassColors(
    dark = false, canvas = Color(0xFFF2F4FF), ink = Color(0xFF1C2141),
    softInk = Color(0xFF525B7C), accent = Color(0xFF6138C6),
    sheet = Color(0xF0F7F6FF), glassTop = Color(0xE8FFFFFF),
    glassBottom = Color(0xA9F0F0FF), edge = Color.White, divider = Color(0x324D63AD)
)

internal val LocalGlass = staticCompositionLocalOf { midnight }
internal val LocalReduceMotion = staticCompositionLocalOf { false }

@OptIn(ExperimentalTextApi::class)
private val Manrope = FontFamily(
    Font(R.font.manrope, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.manrope, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.manrope, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.manrope, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.manrope, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800)))
)

private val baseType = Typography()
private val localType = with(baseType) {
    Typography(
        displaySmall = displaySmall.copy(
            fontFamily = Manrope, fontSize = 40.sp, lineHeight = 46.sp,
            letterSpacing = (-1.65).sp, fontWeight = FontWeight.ExtraBold
        ),
        headlineMedium = headlineMedium.copy(fontFamily = Manrope, letterSpacing = (-0.8).sp, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontFamily = Manrope, letterSpacing = (-0.5).sp, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = Manrope, letterSpacing = (-0.45).sp, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontFamily = Manrope, letterSpacing = (-0.20).sp, fontWeight = FontWeight.Bold),
        titleSmall = titleSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold),
        bodyLarge = bodyLarge.copy(fontFamily = Manrope, fontSize = 16.sp, lineHeight = 25.sp),
        bodyMedium = bodyMedium.copy(fontFamily = Manrope, lineHeight = 21.sp),
        bodySmall = bodySmall.copy(fontFamily = Manrope, lineHeight = 17.sp),
        labelLarge = labelLarge.copy(fontFamily = Manrope, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        labelSmall = labelSmall.copy(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp)
    )
}

@Composable
fun LocalTheme(appearance: AppearanceSettings, content: @Composable () -> Unit) {
    val dark = when (appearance.mode) {
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
    }
    val glass = if (dark) midnight else daylight
    val colors = if (dark) darkColorScheme(
        primary = glass.accent, onPrimary = Color(0xFF211345), primaryContainer = Color(0xFF302254),
        onPrimaryContainer = Color(0xFFE8DBFF), background = glass.canvas, onBackground = glass.ink,
        surface = Color(0xFF171C34), onSurface = glass.ink, surfaceVariant = Color(0xFF222A44),
        onSurfaceVariant = glass.softInk, surfaceContainer = Color(0xFF171E37),
        outline = Color(0xFF7882AB), outlineVariant = glass.divider, error = Color(0xFFFFB4B9)
    ) else lightColorScheme(
        primary = glass.accent, onPrimary = Color.White, primaryContainer = Color(0xFFE8DEFF),
        onPrimaryContainer = Color(0xFF2B195D), background = glass.canvas, onBackground = glass.ink,
        surface = Color(0xFFFDFBFF), onSurface = glass.ink, surfaceVariant = Color(0xFFE6EAFC),
        onSurfaceVariant = glass.softInk, surfaceContainer = Color(0xFFEFF0FD),
        outline = Color(0xFF7580A7), outlineVariant = glass.divider, error = Color(0xFF9E2541)
    )
    CompositionLocalProvider(
        LocalGlass provides glass,
        LocalReduceMotion provides (appearance.reduceMotion || !ValueAnimator.areAnimatorsEnabled())
    ) {
        MaterialTheme(colorScheme = colors, typography = localType, content = content)
    }
}
