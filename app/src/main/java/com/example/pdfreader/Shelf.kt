package com.example.pdfreader

import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val SpineColors = listOf(
    Color(0xFF7B2D26), Color(0xFF1F4E5F), Color(0xFF2F5233), Color(0xFF3A3A5C), Color(0xFF8C5A1E),
    Color(0xFF4A2C4A), Color(0xFF2E2E2E), Color(0xFF5B6B2E), Color(0xFF1F3A6B), Color(0xFF6B3A1F)
)
private val Cream = Color(0xFFF4E9D8)
private val Gold = Color(0xFFD9B26A)
private val Wood = Color(0xFF5A381D)

private fun shade(c: Color, f: Float) = Color(c.red * f, c.green * f, c.blue * f, 1f)

/** Une étagère : fond de mur, montants en bois, planche et ombre portée. */
@Composable
fun ShelfBoard(height: Dp, content: @Composable BoxScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().drawBehind {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF2A1C12), Color(0xFF1C130C))))
            val sw = 9.dp.toPx()
            drawRect(Wood, Offset.Zero, Size(sw, size.height))
            drawRect(Wood, Offset(size.width - sw, 0f), Size(sw, size.height))
        }
    ) {
        Box(Modifier.fillMaxWidth().height(height).padding(horizontal = 14.dp), content = content)
        Box(
            Modifier.fillMaxWidth().height(14.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFF9A6A3A), Color(0xFF6B4426), Color(0xFF55351C))))
        )
        Box(
            Modifier.fillMaxWidth().height(8.dp)
                .background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent)))
        )
    }
}

/** Dos d'un livre rangé : épaisseur selon le nombre de pages, titre à la verticale. */
@Composable
fun Spine(b: Book, w: Dp, h: Dp, color: Color, progress: Float, delayMs: Int, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val lift by animateDpAsState(if (pressed) 12.dp else 0.dp, label = "lift")
    // le livre tombe sur l'étagère, avec un petit rebond
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); placed = true }
    val drop by animateFloatAsState(if (placed) 0f else -90f, spring(dampingRatio = 0.5f, stiffness = 220f), label = "drop")
    val fade by animateFloatAsState(if (placed) 1f else 0f, tween(260), label = "fade")
    val shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
    Box(
        Modifier.graphicsLayer { translationY = drop; alpha = fade }.offset(y = -lift).width(w).height(h).clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(shade(color, 0.7f), lerp(color, Color.White, 0.12f), color, shade(color, 0.65f))
                )
            )
            .clickable(interactionSource = src, indication = null, onClick = onClick)
    ) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).fillMaxWidth().height(2.dp).background(Gold))
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).fillMaxWidth().height(2.dp).background(Gold))
        Text(
            b.title, color = Cream, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).requiredWidth(h - 36.dp).rotate(90f)
        )
        if (progress > 0f) {
            Box(
                Modifier.align(Alignment.BottomEnd).width(3.dp).fillMaxHeight(progress)
                    .background(Color(0xFF8AB4FF))
            )
        }
    }
}

/** Livre posé de face (couverture visible) sur l'étagère des lectures récentes. */
@Composable
fun ShelfCover(act: MainActivity, b: Book, w: Dp, progress: Float, onClick: () -> Unit) {
    var bmp by remember(b.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(b.id) { bmp = withContext(Dispatchers.IO) { Lib.makeCover(act, b) } }
    var up by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(120); up = true }
    val pop by animateFloatAsState(if (up) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "pop")
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .graphicsLayer {
                alpha = pop.coerceIn(0f, 1f)
                scaleX = 0.8f + 0.2f * pop
                scaleY = 0.8f + 0.2f * pop
            }
            .width(w).aspectRatio(0.7f).shadow(8.dp, shape).clip(shape)
            .background(Color(0xFF3A2A1E)).clickable(onClick = onClick)
    ) {
        val c = bmp
        if (c != null) {
            Image(c.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                b.title, color = Cream, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 4,
                modifier = Modifier.align(Alignment.Center).padding(8.dp)
            )
        }
        Box(Modifier.align(Alignment.CenterStart).width(5.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.18f)))
        if (progress > 0f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.4f))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(0xFF8AB4FF)))
            }
        }
    }
}

@Composable
fun Shelf(act: MainActivity, books: List<Book>, onOpen: (Book) -> Unit) {
    fun progress(b: Book): Float = bookProgress(b)

    val recent = books.filter { it.opened > 0 }.sortedByDescending { it.opened }.take(3)
    val ordered = books.sortedWith(compareByDescending<Book> { it.opened }.thenBy { it.title.lowercase() })

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF1C130C))) {
        val avail = maxWidth.value - 28f

        fun hash(b: Book) = b.id.hashCode() and 0x7fffffff
        fun spineW(b: Book): Float {
            val pc = b.pages
            return if (pc > 0) 24f + (pc.coerceAtMost(400) / 400f) * 20f else 26f + (hash(b) % 12)
        }

        val rows = mutableListOf<List<Book>>()
        var cur = mutableListOf<Book>()
        var used = 0f
        for (b in ordered) {
            val w = spineW(b) + 3f
            if (used + w > avail && cur.isNotEmpty()) {
                rows.add(cur); cur = mutableListOf(); used = 0f
            }
            cur.add(b); used += w
        }
        if (cur.isNotEmpty()) rows.add(cur)

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (recent.isNotEmpty()) {
                Text(
                    "Lus récemment", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
                )
                val cw = ((avail - 24f) / 3f).dp
                ShelfBoard((cw.value / 0.7f + 14f).dp) {
                    Row(
                        Modifier.align(Alignment.BottomStart),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        recent.forEach { b -> ShelfCover(act, b, cw, progress(b)) { onOpen(b) } }
                    }
                }
            }
            Text(
                "Ma bibliothèque", color = Gold, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
            )
            rows.forEachIndexed { r, row ->
                ShelfBoard(184.dp) {
                    Row(
                        Modifier.align(Alignment.BottomStart),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        row.forEachIndexed { k, b ->
                            val h = hash(b)
                            Spine(
                                b, spineW(b).dp, (118 + (h % 6) * 9).dp,
                                SpineColors[h % SpineColors.size], progress(b), r * 90 + k * 35
                            ) { onOpen(b) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
