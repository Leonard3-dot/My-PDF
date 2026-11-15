package com.example.pdfreader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/** Carte à fond de surface avec contour fin. */
@Composable
fun PanelBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().panelBg(shape).padding(16.dp),
        content = content
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 24.dp, bottom = 10.dp)
    )
}

@Composable
fun ResumeCard(act: MainActivity, b: Book, onContinue: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var bmp by remember(b.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(b.id) { bmp = withContext(Dispatchers.IO) { Lib.makeCover(act, b) } }
    val p = bookProgress(b)
    PanelBox {
        Text("Reprendre la lecture", fontSize = 12.sp, color = cs.onSurfaceVariant)
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(76.dp).aspectRatio(0.72f).clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant)
            ) {
                val c = bmp
                if (c != null) {
                    Image(
                        c.asImageBitmap(), null, Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop, alignment = Alignment.TopCenter
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(
                    cleanTitle(b.title), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface
                )
                Text(
                    if (b.pages > 0) "Page ${b.page + 1} sur ${b.pages}" else "Prêt à lire",
                    fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                )
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(cs.surfaceVariant)
                    ) {
                        Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(cs.secondary))
                    }
                    Text(
                        "${(p * 100).toInt()} %", fontSize = 12.sp, color = cs.secondary,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
                Button(onClick = onContinue, modifier = Modifier.padding(top = 12.dp)) { Text("Continuer") }
            }
        }
    }
}

@Composable
private fun StatBlock(value: String, label: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.panelBg(shape).padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = cs.onSurface, maxLines = 1)
        Text(label, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun HomeTab(act: MainActivity, onOpen: (Book) -> Unit, onLibrary: () -> Unit) {
    val prefs = remember { act.getSharedPreferences("p", 0) }
    val cs = MaterialTheme.colorScheme
    val books = remember { Lib.load(act) }
    val sessions = remember { Store.sessionList() }
    val name = (prefs.getString("name", "") ?: "").trim()
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val hello = when {
        hour < 12 -> "Bonjour"
        hour < 18 -> "Bon après-midi"
        else -> "Bonsoir"
    }
    val weekAgo = remember { System.currentTimeMillis() - 7L * 24 * 3600 * 1000 }
    val weekSec = remember { sessions.filter { it.start >= weekAgo }.sumOf { it.seconds } }
    val goalMin = prefs.getInt("goal_min", 120).coerceAtLeast(1)
    val pct = ((weekSec / 60f) / goalMin * 100f).toInt().coerceIn(0, 100)
    val last = books.filter { it.opened > 0 }.maxByOrNull { it.opened }
    val recent = books.filter { it.opened > 0 }.sortedByDescending { it.opened }.take(8)
    val favs = books.filter { it.fav }.take(8)
    // suggestions : livres jamais ouverts qui partagent une catégorie avec ta dernière lecture
    val lastTags = if (last != null) Smart.tagsFor(act, last) else emptyList()
    val suggestions = if (last == null || lastTags.isEmpty()) emptyList() else books.filter { b ->
        b.id != last.id && b.opened == 0L && Smart.tagsFor(act, b).any { t -> t in lastTags }
    }.take(8)

    fun openBook(b: Book) {
        b.opened = System.currentTimeMillis()
        Lib.save(act, books)
        onOpen(b)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp)) {
        item {
            Text(
                if (name.isBlank()) hello else "$hello, $name",
                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = cs.onBackground
            )
            Text(
                "Apprendre aujourd'hui, c'est réussir demain.",
                fontSize = 14.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )
        }
        item {
            if (last != null) {
                ResumeCard(act, last) { openBook(last) }
            } else {
                PanelBox {
                    Text("Ta bibliothèque t'attend", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                    Text(
                        "Importe tes PDF pour suivre ta progression ici.",
                        fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                    )
                    Button(onClick = onLibrary, modifier = Modifier.padding(top = 12.dp)) { Text("Ouvrir la bibliothèque") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBlock("${books.size}", "Livres", Modifier.weight(1f))
                StatBlock(fmtDur(weekSec), "Cette semaine", Modifier.weight(1f))
                StatBlock("$pct %", "Objectif", Modifier.weight(1f))
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionLabel("Lus récemment") }
            item { RecentStackHome(act, recent) { openBook(it) } }
        }
        if (suggestions.isNotEmpty()) {
            item { SectionLabel("Pour toi") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(suggestions, key = { it.id }) { b ->
                        Box(Modifier.width(128.dp)) {
                            BookCard(
                                act, b, false, 0, false,
                                onTap = { openBook(b) }, onLong = {}, index = 0
                            )
                        }
                    }
                }
            }
        }
        if (favs.isNotEmpty()) {
            item { SectionLabel("Favoris") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(favs, key = { it.id }) { b ->
                        Box(Modifier.width(128.dp)) {
                            BookCard(
                                act, b, false, 0, false,
                                onTap = { openBook(b) }, onLong = {}, index = 0
                            )
                        }
                    }
                }
            }
        }
    }
}
