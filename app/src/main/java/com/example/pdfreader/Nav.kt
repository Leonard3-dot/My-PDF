package com.example.pdfreader

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Écran principal : 4 onglets (Accueil, Bibliothèque, Statistiques, Plus) et barre de navigation en bas. */
@Composable
fun Library(act: MainActivity, dark: Boolean, onTheme: () -> Unit, onOpen: (Book) -> Unit, onMarks: () -> Unit, onOpds: () -> Unit, onGuide: (Boolean) -> Unit) {
    val prefs = remember { act.getSharedPreferences("p", 0) }
    var tab by remember { mutableStateOf(prefs.getString("tab", "home") ?: "home") }
    fun go(t: String) {
        tab = t
        prefs.edit().putString("tab", t).apply()
    }
    BackHandler(enabled = tab != "home") { go("home") }
    LaunchedEffect(Unit) {
        delay(5000) // on laisse d'abord l'interface s'afficher et réagir
        withContext(Dispatchers.IO) { Smart.analyzeMissing(act) }
    }

    Column(Modifier.fillMaxSize().background(if (LocalGlass.current) Color.Transparent else MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Crossfade(targetState = tab, animationSpec = tween(200), label = "tab") { t ->
                when (t) {
                    "lib" -> LibraryScreen(act, dark, onTheme, onOpen, onMarks, onOpds, onGuide)
                    "stats" -> StatsTab(act)
                    "more" -> MoreTab(act, dark, onTheme, onMarks, onOpds, onGuide)
                    else -> HomeTab(act, onOpen, onLibrary = { go("lib") })
                }
            }
        }
        BottomBar(tab) { go(it) }
    }
}

@Composable
fun BottomBarSolid(tab: String, onSelect: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    NavigationBar(containerColor = cs.surface, contentColor = cs.onSurfaceVariant) {
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
                    indicatorColor = cs.primaryContainer
                )
            )
        }
    }
}

@Composable
fun NavIcon(kind: String, tint: Color) {
    if (kind == "lib") {
        Ico(Ic.Book, tint, 24.dp)
    } else {
        Canvas(Modifier.size(24.dp)) {
            val k = this.size.minDimension / 24f
            val sw = 2f * k
            val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (kind) {
                "home" -> {
                    val p = Path().apply {
                        moveTo(4f * k, 11f * k); lineTo(12f * k, 4f * k); lineTo(20f * k, 11f * k)
                        lineTo(20f * k, 20f * k); lineTo(14.5f * k, 20f * k); lineTo(14.5f * k, 14f * k)
                        lineTo(9.5f * k, 14f * k); lineTo(9.5f * k, 20f * k); lineTo(4f * k, 20f * k); close()
                    }
                    drawPath(p, tint, style = st)
                }
                "stats" -> {
                    drawLine(color = tint, start = Offset(6f * k, 20f * k), end = Offset(6f * k, 13f * k), strokeWidth = sw * 1.4f, cap = StrokeCap.Round)
                    drawLine(color = tint, start = Offset(12f * k, 20f * k), end = Offset(12f * k, 5f * k), strokeWidth = sw * 1.4f, cap = StrokeCap.Round)
                    drawLine(color = tint, start = Offset(18f * k, 20f * k), end = Offset(18f * k, 10f * k), strokeWidth = sw * 1.4f, cap = StrokeCap.Round)
                }
                else -> {
                    val s = Size(6f * k, 6f * k)
                    val r = CornerRadius(1.5f * k)
                    drawRoundRect(color = tint, topLeft = Offset(4f * k, 4f * k), size = s, cornerRadius = r, style = st)
                    drawRoundRect(color = tint, topLeft = Offset(14f * k, 4f * k), size = s, cornerRadius = r, style = st)
                    drawRoundRect(color = tint, topLeft = Offset(4f * k, 14f * k), size = s, cornerRadius = r, style = st)
                    drawRoundRect(color = tint, topLeft = Offset(14f * k, 14f * k), size = s, cornerRadius = r, style = st)
                }
            }
        }
    }
}

@Composable
fun MoreRow(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 15.sp, color = cs.onBackground)
        Text("›", fontSize = 20.sp, color = cs.onSurfaceVariant)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outline.copy(alpha = 0.25f)))
}

@Composable
fun MoreTab(act: MainActivity, dark: Boolean, onTheme: () -> Unit, onMarks: () -> Unit, onOpds: () -> Unit, onGuide: (Boolean) -> Unit) {
    val prefs = remember { act.getSharedPreferences("p", 0) }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(prefs.getString("name", "") ?: "") }
    var dOpt by remember { mutableStateOf(false) }
    var scanOpen by remember { mutableStateOf(false) }
    var dAbout by remember { mutableStateOf(false) }

    val exportL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { Store.exportTo(act, u) }
            Toast.makeText(act, if (ok) "Sauvegarde enregistrée" else "Échec de la sauvegarde", Toast.LENGTH_SHORT).show()
        }
    }
    val importL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val msg = withContext(Dispatchers.IO) { Store.restoreFrom(act, u) }
            Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
        }
    }

    if (scanOpen) ScanSheet(act) { scanOpen = false }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("Plus", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        TextField(
            value = name,
            onValueChange = { name = it; prefs.edit().putString("name", it).apply() },
            label = { Text("Ton prénom (affiché sur l'accueil)") },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = cs.surfaceVariant,
                unfocusedContainerColor = cs.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)
        )
        MoreRow("Scanner l'appareil pour trouver des PDF") { scanOpen = true }
        var glassOn by remember { mutableStateOf(act.glass) }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mode Liquid Glass", fontSize = 15.sp, color = cs.onBackground)
                Text("Verre translucide sur fond lumineux", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            Switch(checked = glassOn, onCheckedChange = {
                glassOn = it
                act.glass = it
                prefs.edit().putBoolean("glass", it).apply()
            })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outline.copy(alpha = 0.25f)))
        var showTags by remember { mutableStateOf(prefs.getBoolean("show_tags", true)) }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Catégories dans la bibliothèque", fontSize = 15.sp, color = cs.onBackground)
                Text("Puces #Python, #SQL… sous la recherche", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            Switch(checked = showTags, onCheckedChange = {
                showTags = it
                prefs.edit().putBoolean("show_tags", it).apply()
            })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outline.copy(alpha = 0.25f)))
        MoreRow("Marque-pages et notes") { onMarks() }
        MoreRow("Catalogues OPDS") { onOpds() }
        MoreRow("Guide et manuel") { onGuide(false) }
        MoreRow("Options des gestes") { dOpt = true }
        MoreRow("Sauvegarder mes données") { exportL.launch("my-pdf-sauvegarde.json") }
        MoreRow("Restaurer une sauvegarde") { importL.launch(arrayOf("*/*")) }
        MoreRow(if (dark) "Passer au thème clair" else "Passer au thème sombre") { onTheme() }
        MoreRow("À propos") { dAbout = true }
        MoreRow("Quitter") { act.finish() }
    }

    if (dOpt) {
        var sens by remember { mutableStateOf(prefs.getFloat("sens", 1f)) }
        var inv by remember { mutableStateOf(prefs.getBoolean("inv", false)) }
        AlertDialog(
            onDismissRequest = { dOpt = false },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putFloat("sens", sens).putBoolean("inv", inv).apply(); dOpt = false
                }) { Text("OK") }
            },
            title = { Text("Options des gestes") },
            text = {
                Column {
                    Text("Sensibilité des gestes : ${"%.1f".format(sens)}")
                    Slider(value = sens, onValueChange = { sens = it }, valueRange = 0.3f..2.5f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = inv, onCheckedChange = { inv = it })
                        Text("  Inverser le balayage latéral")
                    }
                }
            }
        )
    }
    if (dAbout) {
        AlertDialog(
            onDismissRequest = { dAbout = false },
            confirmButton = { TextButton(onClick = { dAbout = false }) { Text("OK") } },
            title = { Text("À propos") },
            text = { Text("My PDF : bibliothèque, accueil, statistiques de lecture, signets avec notes, catalogues OPDS, PDF protégés et défilement par gestes de la main.") }
        )
    }
}
