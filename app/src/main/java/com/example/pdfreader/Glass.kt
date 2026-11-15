package com.example.pdfreader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Vrai quand le style « Liquid Glass » est activé (réglage dans l'onglet Plus). */
val LocalGlass = compositionLocalOf { false }

/**
 * Fond lumineux derrière le verre : de grandes taches bleu, cyan et violet très douces.
 * Il est dessiné une seule fois (aucune animation continue), donc sans coût pour la batterie.
 */
@Composable
fun AuroraBackground() {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val base = if (dark) Color(0xFF070A12) else Color(0xFFEEF2FA)
    val a = if (dark) 0.55f else 0.40f
    Canvas(Modifier.fillMaxSize()) {
        drawRect(base)
        val w = size.width
        val h = size.height
        fun blob(c: Color, cx: Float, cy: Float, r: Float) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(c.copy(alpha = a), Color.Transparent),
                    center = Offset(cx, cy), radius = r
                ),
                radius = r, center = Offset(cx, cy)
            )
        }
        blob(Color(0xFF3D7BFF), w * 0.10f, h * 0.10f, w * 0.95f)
        blob(Color(0xFF22D3EE), w * 1.00f, h * 0.36f, w * 0.80f)
        blob(Color(0xFF8B5CF6), w * 0.15f, h * 0.80f, w * 0.95f)
        blob(Color(0xFF3D7BFF), w * 0.95f, h * 1.00f, w * 0.70f)
    }
}

/**
 * Effet verre : fond translucide dégradé, contour lumineux (plus clair en haut à gauche)
 * et fin reflet sur le bord supérieur.
 */
@Composable
fun Modifier.glass(shape: Shape, tint: Color = Color.Transparent): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val top = if (dark) 0.17f else 0.62f
    val bottom = if (dark) 0.05f else 0.30f
    val edge = if (dark) {
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.60f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.28f)))
    } else {
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Color(0x14000000), Color.White.copy(alpha = 0.55f)))
    }
    return this
        .clip(shape)
        .background(tint)
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = top), Color.White.copy(alpha = bottom))))
        .drawBehind {
            val inset = 18.dp.toPx().coerceAtMost(size.width / 4f)
            drawLine(
                Color.White.copy(alpha = if (dark) 0.40f else 0.90f),
                Offset(inset, 1.5f), Offset(size.width - inset, 1.5f), strokeWidth = 1.5f
            )
        }
        .border(1.dp, edge, shape)
}

/** Fond d'un panneau : verre si le mode est activé, sinon carte moderne classique. */
@Composable
fun Modifier.panelBg(shape: Shape): Modifier {
    val cs = MaterialTheme.colorScheme
    return if (LocalGlass.current) {
        this.glass(shape)
    } else {
        this.clip(shape).background(cs.surface).border(1.dp, cs.outline.copy(alpha = 0.35f), shape)
    }
}

/** Surface du lecteur (barre du haut, îlot) : verre fumé en mode Liquid Glass, sinon Surface normale. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color,
    border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    if (LocalGlass.current) {
        // teinte sombre conservée pour que les commandes restent lisibles sur une page blanche
        Box(modifier.glass(shape, tint = color.copy(alpha = color.alpha * 0.85f))) { content() }
    } else {
        Surface(modifier = modifier, shape = shape, color = color, shadowElevation = shadowElevation, border = border, content = content)
    }
}

/** Barre de navigation : capsule de verre flottante en mode Liquid Glass. */
@Composable
fun BottomBar(tab: String, onSelect: (String) -> Unit) {
    if (!LocalGlass.current) {
        BottomBarSolid(tab, onSelect)
        return
    }
    val cs = MaterialTheme.colorScheme
    Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).glass(RoundedCornerShape(30.dp))) {
        NavigationBar(containerColor = Color.Transparent, contentColor = cs.onSurfaceVariant, tonalElevation = 0.dp) {
            listOf("home" to "Accueil", "lib" to "Bibliothèque", "stats" to "Statistiques", "more" to "Plus").forEach { (k, label) ->
                NavigationBarItem(
                    selected = tab == k,
                    onClick = { onSelect(k) },
                    icon = { NavIcon(k, if (tab == k) cs.primary else cs.onSurfaceVariant) },
                    label = { Text(label, fontSize = 11.sp, maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = cs.primary,
                        selectedTextColor = cs.primary,
                        unselectedIconColor = cs.onSurfaceVariant,
                        unselectedTextColor = cs.onSurfaceVariant,
                        indicatorColor = Color.White.copy(alpha = 0.16f)
                    )
                )
            }
        }
    }
}
