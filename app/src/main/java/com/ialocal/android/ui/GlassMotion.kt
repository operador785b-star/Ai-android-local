package com.ialocal.android.ui

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

internal object GlassMotion {
    val glide = CubicBezierEasing(0.20f, 0.76f, 0.24f, 1f)
    const val pageEnter = 310
    const val pageExit = 205

    fun reveal(progress: Float, group: Int): Float {
        val start = group.coerceIn(0, 5) * 0.075f
        return glide.transform(((progress - start) / (1f - start)).coerceIn(0f, 1f))
    }
}

@Composable
internal fun Modifier.drawerBackdrop(progress: () -> Float, settledOpen: Boolean, busy: Boolean): Modifier {
    val reduced = LocalReduceMotion.current
    val hardware = LocalView.current.isHardwareAccelerated
    val blurRadius = with(LocalDensity.current) { 7.dp.toPx() }
    // A single fixed filter after the drawer settles, skipped during streaming or transfers.
    val effect = remember(blurRadius) { BlurEffect(blurRadius, blurRadius, TileMode.Clamp) }
    return graphicsLayer {
        val level = if (reduced) 0f else progress()
        scaleX = 1f - level * 0.015f
        scaleY = scaleX
        translationX = 8.dp.toPx() * level
        renderEffect = if (settledOpen && !busy && Build.VERSION.SDK_INT >= 31 && hardware) effect else null
    }
}

@Composable
internal fun revealProgress(): () -> Float {
    val reduced = LocalReduceMotion.current
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ready = true }
    val amount = animateFloatAsState(
        if (ready || reduced) 1f else 0f,
        if (reduced) snap() else tween(375, easing = GlassMotion.glide),
        label = "entrada de controles"
    )
    return { amount.value }
}

internal fun Modifier.glassReveal(progress: () -> Float, group: Int, distance: Float = 9f) =
    graphicsLayer {
        val value = GlassMotion.reveal(progress(), group)
        alpha = 0.3f + value * 0.7f
        translationY = distance.dp.toPx() * (1f - value)
    }

@Composable
internal fun Modifier.glassPress(interactions: MutableInteractionSource, enabled: Boolean = true): Modifier {
    val pressed by interactions.collectIsPressedAsState()
    val scale = animateFloatAsState(
        if (pressed && enabled) 0.979f else 1f,
        if (LocalReduceMotion.current) snap() else spring(stiffness = 670f),
        label = "presión"
    )
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
