package com.pdm.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class TechPalette(
    val hull: Color,
    val deck: Color,
    val edge: Color,
    val amber: Color,
    val linkGreen: Color,
    val mute: Color,
    val stoppedRail: Color,
    val danger: Color,
    /** Secure transport tags (HTTPS, TLS). */
    val cyan: Color,
    /** Write-method tags (POST, DELETE). */
    val magenta: Color,
    /** Behind everything. */
    val background: Color,
    /** High-emphasis text and numerals: what dark mode draws in white. */
    val ink: Color,
    /** Backdrop under small stamps that sit on busy plates. */
    val scrim: Color,
    /** Blurred glows (hero counts, tag halos): light on black adds, light on paper stains, so it is weaker. */
    val glow: Float,
    /** Background name and activity text strength. */
    val watermark: Float,
    val isLight: Boolean,
)

val DarkTechPalette = TechPalette(
    hull = Color(0xFF050505),
    deck = Color(0xFF2C2C2C),
    edge = Color(0xFF3D3D3D),
    amber = Color(0xFFFF9800),
    linkGreen = Color(0xFF69F0AE),
    mute = Color(0xFF757575),
    stoppedRail = Color(0xFF4A4A4A),
    danger = Color(0xFFE53935),
    cyan = Color(0xFF3EE6FF),
    magenta = Color(0xFFFF4F8B),
    background = Color.Black,
    ink = Color.White,
    scrim = Color.Black,
    glow = 1f,
    watermark = 1f,
    isLight = false,
)

/**
 * Daylight: a printed datasheet. Warm paper instead of OLED black, the same amber identity burnt
 * darker. Every text colour clears WCAG AA (4.5:1) on paper, plates and the deck fill.
 */
val LightTechPalette = TechPalette(
    hull = Color(0xFFFDFCF9),
    deck = Color(0xFFECE8DF),
    edge = Color(0xFFB9B2A3),
    amber = Color(0xFF9C4C00),
    linkGreen = Color(0xFF08703F),
    mute = Color(0xFF66625A),
    stoppedRail = Color(0xFFA39C8E),
    danger = Color(0xFFB8241D),
    cyan = Color(0xFF006C80),
    magenta = Color(0xFFA8255A),
    background = Color(0xFFEFECE5),
    ink = Color(0xFF1C1B18),
    scrim = Color(0xFFFDFCF9),
    glow = 0.45f,
    watermark = 0.7f,
    isLight = true,
)

val LocalTechColors = staticCompositionLocalOf { DarkTechPalette }

object TechColors {
    val Hull: Color @Composable get() = LocalTechColors.current.hull
    val Deck: Color @Composable get() = LocalTechColors.current.deck
    val Edge: Color @Composable get() = LocalTechColors.current.edge
    val Amber: Color @Composable get() = LocalTechColors.current.amber
    val LinkGreen: Color @Composable get() = LocalTechColors.current.linkGreen
    val Mute: Color @Composable get() = LocalTechColors.current.mute
    val StoppedRail: Color @Composable get() = LocalTechColors.current.stoppedRail
    val Danger: Color @Composable get() = LocalTechColors.current.danger
    val Cyan: Color @Composable get() = LocalTechColors.current.cyan
    val Magenta: Color @Composable get() = LocalTechColors.current.magenta
    val Background: Color @Composable get() = LocalTechColors.current.background
    val Ink: Color @Composable get() = LocalTechColors.current.ink
    val Scrim: Color @Composable get() = LocalTechColors.current.scrim
    val Glow: Float @Composable get() = LocalTechColors.current.glow
    val Watermark: Float @Composable get() = LocalTechColors.current.watermark
    val IsLight: Boolean @Composable get() = LocalTechColors.current.isLight
}

val TechPlateShape = CutCornerShape(bottomEnd = 28.dp)

@Composable
fun techRailColor(status: String): Color = when (status.lowercase()) {
    "running", "online", "available", "good" -> TechColors.LinkGreen
    "stopped", "offline" -> TechColors.StoppedRail
    "error", "warning" -> TechColors.Amber
    else -> TechColors.Mute
}

@Composable
fun TechActionPlate(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val shape = CutCornerShape(bottomEnd = 10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (emphasized) TechColors.Deck else TechColors.Hull)
            .border(1.dp, if (emphasized) MaterialTheme.colorScheme.primary else TechColors.Edge, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Rectangular ON/OFF plate. Same binary tap as a Switch, cut-corner like the action plates. */
@Composable
fun TechSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val trackWidth = 64.dp
    val trackHeight = 28.dp
    val thumbWidth = 32.dp
    val inset = 3.dp
    val travel = trackWidth - thumbWidth - inset * 2
    val offsetX by animateDpAsState(
        targetValue = if (checked) travel else 0.dp,
        animationSpec = tween(durationMillis = 140),
        label = "techSwitchThumb",
    )
    val shape = CutCornerShape(bottomEnd = 8.dp)
    val thumbShape = CutCornerShape(bottomEnd = 6.dp)
    val border = when {
        !enabled -> TechColors.Edge
        checked -> TechColors.Amber
        else -> TechColors.Edge
    }
    val thumbFill = when {
        !enabled -> TechColors.Deck
        checked -> TechColors.Amber
        else -> TechColors.Mute
    }
    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(TechColors.Hull)
            .border(1.dp, border, shape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(inset),
    ) {
        Box(
            modifier = Modifier
                .offset(x = offsetX)
                .width(thumbWidth)
                .fillMaxHeight()
                .clip(thumbShape)
                .background(thumbFill),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (checked) "ON" else "OFF",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 0.6.sp,
                color = if (checked) TechColors.Hull else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Plate fill opacity: opaque enough for text, open enough for the background name. */
const val PLATE_FILL_ALPHA = 0.72f

@Composable
fun TechPlate(
    modifier: Modifier = Modifier,
    railColor: Color = TechColors.Amber,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(TechPlateShape)
            // Slightly see-through so the blurred server name glows behind it like frosted glass.
            .background(TechColors.Hull.copy(alpha = PLATE_FILL_ALPHA))
            .border(1.dp, TechColors.Edge, TechPlateShape),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(railColor))
            // 16 dp at the bottom: with 12 dp at the end, the 28 dp cut corner can no longer
            // clip the right end of the last line (right-aligned values, bars).
            Column(
                Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 16.dp),
                content = content,
            )
        }
    }
}

@Composable
fun TechSectionLabel(title: String, count: Int? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(12.dp).height(3.dp).background(TechColors.Amber))
        Spacer(Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            color = TechColors.Amber,
            style = MaterialTheme.typography.labelMedium,
        )
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                count.toString(),
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
