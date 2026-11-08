package com.example.pdfreader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF00285C),
    primaryContainer = Color(0xFF1F3A6B),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF7FD8C8),
    background = Color(0xFF0E1015),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF161922),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFA9AFBF),
    outline = Color(0xFF5A6178),
    error = Color(0xFFFF8A80)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3559E0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE5FF),
    onPrimaryContainer = Color(0xFF001A5C),
    secondary = Color(0xFF00897B),
    background = Color(0xFFF5F6FA),
    onBackground = Color(0xFF14161C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161C),
    surfaceVariant = Color(0xFFE6E8F0),
    onSurfaceVariant = Color(0xFF4A5063),
    outline = Color(0xFF9097AA),
    error = Color(0xFFB3261E)
)

@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}

enum class Ic { Menu, Search, More, Back, Plus, Close, Play, Pause, Bookmark, List, Eye, Sort, Clock, Book, File, Globe, Speaker, Help, Up, Down }

/** Icônes dessinées à la main : aucune dépendance supplémentaire. */
@Composable
fun Ico(ic: Ic, tint: Color = LocalContentColor.current, box: Dp = 24.dp) {
    Canvas(Modifier.size(box)) {
        val k = this.size.minDimension / 24f
        val sw = 2f * k
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun o(x: Float, y: Float) = Offset(x * k, y * k)
        fun ln(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color = tint, start = o(x1, y1), end = o(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
        when (ic) {
            Ic.Menu -> { ln(4f, 6f, 20f, 6f); ln(4f, 12f, 20f, 12f); ln(4f, 18f, 20f, 18f) }
            Ic.Search -> {
                drawCircle(color = tint, radius = 6f * k, center = o(10.5f, 10.5f), style = st)
                ln(15f, 15f, 20f, 20f)
            }
            Ic.More -> {
                drawCircle(color = tint, radius = 1.8f * k, center = o(12f, 5f))
                drawCircle(color = tint, radius = 1.8f * k, center = o(12f, 12f))
                drawCircle(color = tint, radius = 1.8f * k, center = o(12f, 19f))
            }
            Ic.Back -> { ln(19f, 12f, 5f, 12f); ln(11f, 6f, 5f, 12f); ln(11f, 18f, 5f, 12f) }
            Ic.Plus -> { ln(12f, 5f, 12f, 19f); ln(5f, 12f, 19f, 12f) }
            Ic.Close -> { ln(6f, 6f, 18f, 18f); ln(18f, 6f, 6f, 18f) }
            Ic.Play -> {
                val p = Path().apply {
                    moveTo(8f * k, 5f * k); lineTo(19f * k, 12f * k); lineTo(8f * k, 19f * k); close()
                }
                drawPath(p, tint)
            }
            Ic.Pause -> {
                drawRoundRect(color = tint, topLeft = o(6f, 5f), size = Size(4f * k, 14f * k), cornerRadius = CornerRadius(k))
                drawRoundRect(color = tint, topLeft = o(14f, 5f), size = Size(4f * k, 14f * k), cornerRadius = CornerRadius(k))
            }
            Ic.Bookmark -> {
                val p = Path().apply {
                    moveTo(7f * k, 4f * k); lineTo(17f * k, 4f * k); lineTo(17f * k, 20f * k)
                    lineTo(12f * k, 16f * k); lineTo(7f * k, 20f * k); close()
                }
                drawPath(p, tint, style = st)
            }
            Ic.List -> {
                ln(9f, 7f, 20f, 7f); ln(9f, 12f, 20f, 12f); ln(9f, 17f, 20f, 17f)
                drawCircle(color = tint, radius = 1.4f * k, center = o(5f, 7f))
                drawCircle(color = tint, radius = 1.4f * k, center = o(5f, 12f))
                drawCircle(color = tint, radius = 1.4f * k, center = o(5f, 17f))
            }
            Ic.Eye -> {
                drawCircle(color = tint, radius = 8f * k, center = o(12f, 12f), style = st)
                drawCircle(color = tint, radius = 3f * k, center = o(12f, 12f))
            }
            Ic.Sort -> { ln(4f, 7f, 20f, 7f); ln(4f, 12f, 15f, 12f); ln(4f, 17f, 10f, 17f) }
            Ic.Clock -> {
                drawCircle(color = tint, radius = 8.5f * k, center = o(12f, 12f), style = st)
                ln(12f, 7f, 12f, 12f); ln(12f, 12f, 16f, 14f)
            }
            Ic.Book -> {
                drawRoundRect(color = tint, topLeft = o(5f, 4f), size = Size(14f * k, 16f * k), cornerRadius = CornerRadius(2f * k), style = st)
                ln(9f, 4f, 9f, 20f)
            }
            Ic.File -> {
                val p = Path().apply {
                    moveTo(6f * k, 3f * k); lineTo(14f * k, 3f * k); lineTo(19f * k, 8f * k)
                    lineTo(19f * k, 21f * k); lineTo(6f * k, 21f * k); close()
                }
                drawPath(p, tint, style = st)
                ln(14f, 3f, 14f, 8f); ln(14f, 8f, 19f, 8f)
            }
            Ic.Globe -> {
                drawCircle(color = tint, radius = 9f * k, center = o(12f, 12f), style = st)
                ln(3f, 12f, 21f, 12f); ln(12f, 3f, 12f, 21f)
            }
            Ic.Speaker -> {
                val p = Path().apply {
                    moveTo(4f * k, 9f * k); lineTo(8f * k, 9f * k); lineTo(13f * k, 5f * k)
                    lineTo(13f * k, 19f * k); lineTo(8f * k, 15f * k); lineTo(4f * k, 15f * k); close()
                }
                drawPath(p, tint, style = st)
                drawArc(
                    color = tint, startAngle = -45f, sweepAngle = 90f, useCenter = false,
                    topLeft = o(10f, 7f), size = Size(10f * k, 10f * k), style = st
                )
            }
            Ic.Help -> {
                drawCircle(color = tint, radius = 9f * k, center = o(12f, 12f), style = st)
                drawArc(
                    color = tint, startAngle = 180f, sweepAngle = 200f, useCenter = false,
                    topLeft = o(9.5f, 8f), size = Size(5f * k, 5f * k), style = st
                )
                ln(12f, 13.2f, 12f, 14.5f)
                drawCircle(color = tint, radius = 1.1f * k, center = o(12f, 17.3f))
            }
            Ic.Up -> { ln(6f, 15f, 12f, 9f); ln(12f, 9f, 18f, 15f) }
            Ic.Down -> { ln(6f, 9f, 12f, 15f); ln(12f, 15f, 18f, 9f) }
        }
    }
}

@Composable
fun IconBtn(ic: Ic, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Ico(ic, tint, 22.dp) }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val fg by animateColorAsState(if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant, label = "chipFg")
    val bg by animateColorAsState(if (selected) cs.primaryContainer else cs.surfaceVariant, label = "chipBg")
    Text(
        text,
        color = fg,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}
