package com.ialocal.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ialocal.android.R

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
internal fun RibbonMark(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val dark = LocalGlass.current.dark
    val shape = RoundedCornerShape(37)
    Box(
        modifier.size(size).clip(shape).background(
            Brush.linearGradient(
                if (dark) listOf(Color(0xFF39305D), Color(0xFF192844))
                else listOf(Color(0xFFF0E7FF), Color(0xFFDCEAFF))
            )
        ).border(0.8.dp, Color.White.copy(alpha = if (dark) 0.22f else 0.87f), shape),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(R.drawable.brand_ribbons), null, Modifier.fillMaxSize())
    }
}

@Composable
internal fun RibbonHero(modifier: Modifier = Modifier) {
    val dark = LocalGlass.current.dark
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().drawWithCache {
            val center = Offset(size.width / 2f, size.height / 2f)
            val span = size.minDimension
            val violet = Brush.radialGradient(
                listOf(Violet.copy(alpha = if (dark) 0.29f else 0.23f), Color.Transparent),
                center - Offset(span * 0.17f, span * 0.07f), span * 0.53f
            )
            val blue = Brush.radialGradient(
                listOf(BrightBlue.copy(alpha = if (dark) 0.24f else 0.31f), Color.Transparent),
                center + Offset(span * 0.14f, span * 0.08f), span * 0.51f
            )
            val lens = Brush.linearGradient(
                listOf(Color.White.copy(alpha = if (dark) 0.11f else 0.55f), Color.Transparent,
                    BrightBlue.copy(alpha = if (dark) 0.12f else 0.19f)),
                center - Offset(span * 0.3f, span * 0.4f), center + Offset(span * 0.3f, span * 0.4f)
            )
            val bevel = Brush.linearGradient(
                listOf(Color.White.copy(alpha = if (dark) 0.36f else 0.90f), Color.Transparent,
                    BrightBlue.copy(alpha = 0.23f)),
                center - Offset(span * 0.4f, span * 0.4f), center + Offset(span * 0.4f, span * 0.4f)
            )
            onDrawBehind {
                drawRect(violet)
                drawRect(blue)
                drawCircle(lens, span * 0.405f, center)
                drawCircle(bevel, span * 0.405f, center, style = Stroke(1.dp.toPx()))
            }
        }) {}
        Image(painterResource(R.drawable.brand_ribbons), null, Modifier.size(158.dp))
    }
}
