package com.example.pdfreader

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Palette : fond quasi noir, un seul accent bleu électrique, cyan réservé à la progression. */
object Dz {
    val bg = Color(0xFF0B0D12)
    val surface = Color(0xFF12151C)
    val surface2 = Color(0xFF1A1E28)
    val line = Color(0xFF2A3040)
    val accent = Color(0xFF3D7BFF)
    val accentSoft = Color(0xFF15264A)
    val cyan = Color(0xFF22D3EE)
    val text = Color(0xFFEDEFF5)
    val textDim = Color(0xFF9AA3B5)
}

private val ModernDark = darkColorScheme(
    primary = Dz.accent,
    onPrimary = Color.White,
    primaryContainer = Dz.accentSoft,
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Dz.cyan,
    background = Dz.bg,
    onBackground = Dz.text,
    surface = Dz.surface,
    onSurface = Dz.text,
    surfaceVariant = Dz.surface2,
    onSurfaceVariant = Dz.textDim,
    outline = Dz.line,
    error = Color(0xFFFF8A80)
)

private val ModernLight = lightColorScheme(
    primary = Color(0xFF2F63E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF0A1F5C),
    secondary = Color(0xFF0891B2),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF14161C),
    surface = Color.White,
    onSurface = Color(0xFF14161C),
    surfaceVariant = Color(0xFFE9ECF3),
    onSurfaceVariant = Color(0xFF4A5063),
    outline = Color(0xFFC3C8D6),
    error = Color(0xFFB3261E)
)

@Composable
fun ModernTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) ModernDark else ModernLight, content = content)
}

/** Titre propre : sans extension, sans underscores, sans « (1) », première lettre en majuscule. */
fun cleanTitle(raw: String): String {
    var t = raw.replace(Regex("(?i)\\.pdf$"), "")
    t = t.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")
    t = t.replace('_', ' ')
    t = t.replace(Regex("(?<=\\p{L})-(?=\\p{L})"), " ")
    t = t.replace(Regex("\\s+"), " ").trim()
    if (t.isEmpty()) return raw
    return t.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

private val TagRules = listOf(
    "Python" to listOf("python", "pandas", "numpy", "django", "flask"),
    "SQL" to listOf("sql", "mysql", "postgres", "sqlite", "base de donn", "database"),
    "Data" to listOf("data", "donnée", "donnee", "analyse", "analytics", "statist", "power bi", "powerbi", "tableau"),
    "IA" to listOf("machine learning", "deep learning", "intelligence artificielle", "neural", "tensorflow", "pytorch"),
    "Excel" to listOf("excel", "vba", "spreadsheet"),
    "Web" to listOf("html", "css", "javascript", "react", "php", "node"),
    "Java" to listOf("java ", "spring"),
    "Kotlin" to listOf("kotlin", "android"),
    "Cloud" to listOf("aws", "azure", "docker", "kubernetes", "linux")
)

/** Catégories détectées hors ligne à partir du titre. */
fun tagsOf(title: String): List<String> {
    val t = " " + title.lowercase() + " "
    return TagRules.filter { (_, keys) -> keys.any { t.contains(it) } }.map { it.first }.take(3)
}

/** Puce de filtre : pleine quand elle est active, contour fin sinon. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Text(
        text,
        color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) cs.primary else cs.surfaceVariant)
            .then(if (selected) Modifier else Modifier.border(1.dp, cs.outline.copy(alpha = 0.5f), shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    TextField(
        value = query, onValueChange = onChange,
        placeholder = { Text("Rechercher un livre") }, singleLine = true,
        leadingIcon = { Ico(Ic.Search, cs.onSurfaceVariant, 20.dp) },
        trailingIcon = { if (query.isNotEmpty()) IconBtn(Ic.Close, cs.onSurfaceVariant) { onChange("") } },
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = cs.surfaceVariant,
            unfocusedContainerColor = cs.surfaceVariant
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/** Carte de livre : vraie première page, titre propre, catégories, fine barre de progression. */
@Composable
fun BookCard(
    act: MainActivity, b: Book, plain: Boolean, rebuild: Int, selected: Boolean,
    onTap: () -> Unit, onLong: () -> Unit, index: Int
) {
    var bmp by remember(b.id, plain, rebuild) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(b.id, plain, rebuild) {
        if (!plain) bmp = withContext(Dispatchers.IO) { Lib.makeCover(act, b) }
    }
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val progress = bookProgress(b)
    val tags = remember(b.title) { tagsOf(b.title) }

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay((index % 8) * 40L); appeared = true }
    val enter by animateFloatAsState(if (appeared) 1f else 0f, tween(350), label = "cardIn")
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        if (pressed || selected) 0.96f else 1f, spring(dampingRatio = 0.7f, stiffness = 400f), label = "cardPress"
    )

    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer {
                alpha = enter
                translationY = (1f - enter) * 32f
                scaleX = press
                scaleY = press
            }
            .pointerInput(b.id) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { tap() },
                    onLongPress = { long() }
                )
            }
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.72f).clip(shape)
                .background(cs.surfaceVariant)
                .then(if (selected) Modifier.border(2.dp, cs.primary, shape) else Modifier)
        ) {
            val c = bmp
            if (c != null) {
                Image(
                    c.asImageBitmap(), null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop, alignment = Alignment.TopCenter
                )
            } else {
                Ico(Ic.Book, cs.onSurfaceVariant.copy(alpha = 0.5f), 32.dp)
            }
            if (b.fav) {
                Text(
                    "♥", color = Color(0xFFFF5C7A), fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            if (selected) {
                Text(
                    "✓", color = cs.onPrimary, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .clip(CircleShape).background(cs.primary)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            if (progress > 0f) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(cs.secondary))
                }
            }
        }
        Text(
            cleanTitle(b.title), maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onBackground,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tags.take(2).forEach { t ->
                Text(
                    t, fontSize = 10.sp, color = cs.onSurfaceVariant, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(cs.surfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            if (progress > 0f) {
                Text("${(progress * 100).toInt()} %", fontSize = 11.sp, color = cs.secondary)
            }
        }
    }
}
