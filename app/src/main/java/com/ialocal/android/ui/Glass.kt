package com.ialocal.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal enum class GlassTone { STANDARD, QUIET, TINTED, SHEET }

@Composable
internal fun AmbientBackdrop(modifier: Modifier = Modifier) {
    val colors = LocalGlass.current
    Box(modifier.fillMaxSize().background(colors.canvas)) {
        Canvas(Modifier.fillMaxSize().drawWithCache {
            val span = maxOf(size.width, size.height * 0.62f)
            val violet = Brush.radialGradient(
                colors = listOf(DeepViolet.copy(alpha = if (colors.dark) 0.24f else 0.16f), Color.Transparent),
                center = Offset(size.width * 0.04f, size.height * 0.23f), radius = span * 0.64f
            )
            val blue = Brush.radialGradient(
                colors = listOf(ElectricBlue.copy(alpha = if (colors.dark) 0.22f else 0.18f), Color.Transparent),
                center = Offset(size.width * 0.98f, size.height * 0.08f), radius = span * 0.68f
            )
            val bottomLight = Brush.radialGradient(
                colors = listOf(Violet.copy(alpha = if (colors.dark) 0.10f else 0.12f), Color.Transparent),
                center = Offset(size.width * 0.82f, size.height * 0.98f), radius = span * 0.50f
            )
            onDrawBehind {
                drawRect(violet)
                drawRect(blue)
                drawRect(bottomLight)
            }
        }) {}
        // The only render-effect blur is bounded and static. Surfaces blend the same backdrop.
        Box(
            Modifier.align(Alignment.TopEnd).size(240.dp).offset(x = 42.dp, y = 6.dp)
                .blur(26.dp).background(
                    Brush.radialGradient(
                        listOf(BrightBlue.copy(alpha = if (colors.dark) 0.14f else 0.21f), Color.Transparent)
                    ), RoundedCornerShape(50)
                )
        )
    }
}

@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    tone: GlassTone = GlassTone.STANDARD,
    depth: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = LocalGlass.current
    val fills = when (tone) {
        GlassTone.STANDARD -> listOf(colors.glassTop, colors.glassBottom)
        GlassTone.QUIET -> listOf(colors.glassTop.copy(alpha = if (colors.dark) 0.34f else 0.66f),
            colors.glassBottom.copy(alpha = if (colors.dark) 0.23f else 0.46f))
        GlassTone.TINTED -> if (colors.dark) listOf(Color(0xD3402B6D), Color(0xFF212F5A))
            else listOf(Color(0xFFEAE0FF), Color(0xFFDFEAFF))
        GlassTone.SHEET -> listOf(
            if (colors.dark) Color(0xF6212948) else Color(0xF8F9F7FF), colors.sheet
        )
    }
    val edge = Brush.linearGradient(
        0f to colors.edge.copy(alpha = if (colors.dark) 0.32f else 0.98f),
        0.44f to colors.edge.copy(alpha = if (colors.dark) 0.09f else 0.4f),
        1f to (if (colors.dark) BrightBlue else Color(0xFFB0BAE9)).copy(alpha = 0.26f)
    )
    val elevation = if (depth) Modifier.shadow(
        elevation = 13.dp, shape = shape, clip = false,
        ambientColor = Color(0x69202B58), spotColor = Color(0x72303E77)
    ) else Modifier
    Box(
        modifier.then(elevation).clip(shape).background(Brush.verticalGradient(fills))
            .border(1.dp, edge, shape),
        content = content
    )
}

@Composable
internal fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    description: String? = null,
    shape: Shape = RoundedCornerShape(19.dp),
    padding: PaddingValues = PaddingValues(horizontal = 15.dp, vertical = 9.dp),
    content: @Composable RowScope.() -> Unit
) {
    val colors = LocalGlass.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled) 0.965f else 1f,
        if (LocalReduceMotion.current) snap() else spring(stiffness = 650f),
        label = "glass-press"
    )
    val fill = when {
        !enabled -> listOf(colors.glassTop.copy(alpha = 0.45f), colors.glassBottom.copy(alpha = 0.35f))
        primary && pressed -> listOf(Color(0xFF8150E5), Color(0xFF376DCE))
        primary -> listOf(Color(0xFF7042D2), Color(0xFF3264C2))
        pressed -> listOf(colors.glassTop.copy(alpha = 0.94f), colors.glassBottom)
        else -> listOf(colors.glassTop, colors.glassBottom)
    }
    val tint = when {
        !enabled -> colors.softInk.copy(alpha = 0.65f)
        primary -> Color.White
        else -> colors.ink
    }
    Row(
        modifier.heightIn(min = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(Brush.linearGradient(fill))
            .border(
                1.dp,
                Brush.linearGradient(listOf(Color.White.copy(alpha = if (primary) 0.48f else 0.25f),
                    Color.White.copy(alpha = 0.06f), BrightBlue.copy(alpha = 0.24f))),
                shape
            )
            .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button,
                onClick = onClick)
            .then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(color = tint)) {
            CompositionLocalProvider(LocalContentColor provides tint) { content() }
        }
    }
}

@Composable
internal fun GlassIconButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    content: @Composable RowScope.() -> Unit
) = GlassButton(
    onClick, modifier.size(48.dp), enabled, primary, description,
    shape = RoundedCornerShape(17.dp), padding = PaddingValues(0.dp), content = content
)

@Composable
internal fun PrismMark(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        drawCircle(Brush.linearGradient(listOf(Color(0xFFA679FF), DeepViolet, ElectricBlue)), radius)
        drawCircle(Color.White.copy(alpha = 0.26f), radius - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
        drawPath(prismStar(center, radius * 0.57f), Color.White)
    }
}

@Composable
internal fun PrismOrb(modifier: Modifier = Modifier) {
    val dark = LocalGlass.current.dark
    Canvas(modifier.drawWithCache {
        val h = size.height
        val w = size.width
        val origin = Offset(w * 0.50f, h * 0.51f)
        val radius = minOf(h, w) * 0.31f
        val sphere = Brush.radialGradient(
            0f to Color(0x76FFFFFF), 0.26f to Color(0xB8B4A5FF), 0.55f to Color(0xD47458E6),
            0.79f to Color(0xCE384FBC), 1f to Color(0x78243B8C),
            center = origin - Offset(radius * 0.32f, radius * 0.42f), radius = radius * 1.53f
        )
        val innerGlow = Brush.radialGradient(
            listOf(Color(0xB660A5FA), Color.Transparent),
            center = origin + Offset(radius * 0.40f, radius * 0.62f), radius = radius * 1.06f
        )
        val halo = Brush.radialGradient(
            listOf(Violet.copy(alpha = if (dark) 0.36f else 0.29f), Color.Transparent),
            center = origin, radius = radius * 1.98f
        )
        val rim = Brush.linearGradient(
            listOf(Color(0xFFF5EEFF), Color(0x546D66CA), Color(0xD568B8FF)),
            origin - Offset(radius, radius), origin + Offset(radius, radius)
        )
        val orbitSize = Size(radius * 3.34f, radius * 0.94f)
        val orbitStart = Offset(origin.x - orbitSize.width / 2, origin.y - orbitSize.height / 2)
        val orbit = Brush.linearGradient(
            listOf(Color(0x367591FF), Color(0xCFD3BDFF), Color(0x855EACFC)),
            origin - Offset(radius * 1.6f, 0f), origin + Offset(radius * 1.6f, 0f)
        )
        val reflection = Brush.linearGradient(
            listOf(Color.Transparent, Color(0xCDFFFFFF), Color.Transparent),
            origin - Offset(radius, radius), origin + Offset(radius, -radius)
        )
        val ballSize = Size(radius * 2, radius * 2)
        val ballStart = origin - Offset(radius, radius)
        val star = prismStar(origin, radius * 0.50f)
        val smallStar = prismStar(Offset(origin.x + radius * 0.66f, origin.y - radius * 0.58f), radius * 0.13f)
        val starTint = Brush.verticalGradient(
            listOf(Color.White, Color(0xFFC9D8FF)), origin.y - radius * 0.50f, origin.y + radius * 0.50f
        )
        onDrawBehind {
            drawCircle(halo, radius * 1.98f, origin)
            rotate(-27f, origin) {
                drawArc(orbit, 180f, 180f, false, orbitStart, orbitSize, style = Stroke(1.1.dp.toPx()))
            }
            drawCircle(sphere, radius, origin)
            drawCircle(innerGlow, radius, origin)
            drawCircle(rim, radius, origin, style = Stroke(1.45.dp.toPx()))
            drawArc(reflection, 205f, 95f, false, ballStart, ballSize, style = Stroke(2.3.dp.toPx()))
            drawPath(star, starTint)
            drawPath(smallStar, Color(0xB7F5E9FF))
            rotate(-27f, origin) {
                drawArc(orbit, 0f, 180f, false, orbitStart, orbitSize, style = Stroke(1.5.dp.toPx()))
            }
        }
    }) {}
}

private fun prismStar(center: Offset, radius: Float) = Path().apply {
    val x = center.x
    val y = center.y
    val control = radius * 0.23f
    moveTo(x, y - radius)
    quadraticTo(x + control, y - control, x + radius, y)
    quadraticTo(x + control, y + control, x, y + radius)
    quadraticTo(x - control, y + control, x - radius, y)
    quadraticTo(x - control, y - control, x, y - radius)
    close()
}
