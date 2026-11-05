package com.example.pdfreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class Book(val id: String, var title: String, val added: Long, var opened: Long)

/** Bibliothèque : les PDF sont copiés dans le stockage de l'app (marche avec Drive, WhatsApp, Téléchargements...). */
object Lib {
    fun dir(c: Context) = File(c.filesDir, "books").apply { mkdirs() }
    fun coverDir(c: Context) = File(c.filesDir, "covers").apply { mkdirs() }
    fun file(c: Context, b: Book) = File(dir(c), b.id)
    fun cover(c: Context, b: Book) = File(coverDir(c), b.id + ".png")

    fun load(c: Context): MutableList<Book> {
        val a = JSONArray(c.getSharedPreferences("lib", 0).getString("books", "[]") ?: "[]")
        return MutableList(a.length()) { i ->
            a.getJSONObject(i).let { Book(it.getString("id"), it.getString("title"), it.getLong("added"), it.getLong("opened")) }
        }
    }

    fun save(c: Context, l: List<Book>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("added", it.added).put("opened", it.opened)) }
        c.getSharedPreferences("lib", 0).edit().putString("books", a.toString()).apply()
    }

    fun addPdf(c: Context, uri: Uri): Book? = try {
        var name = "PDF"
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { q ->
            if (q.moveToFirst()) name = q.getString(0) ?: name
        }
        val id = System.currentTimeMillis().toString() + (name.hashCode() and 0xffff)
        val out = File(dir(c), id)
        c.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        Book(id, name.removeSuffix(".pdf").removeSuffix(".PDF"), System.currentTimeMillis(), 0L)
    } catch (e: Exception) {
        null
    }

    fun delete(c: Context, b: Book) {
        file(c, b).delete(); cover(c, b).delete()
        c.getSharedPreferences("p", 0).edit().remove("pos_${b.id}").remove("bm_${b.id}").apply()
    }

    fun makeCover(c: Context, b: Book): Bitmap? {
        val cf = cover(c, b)
        if (cf.exists()) return BitmapFactory.decodeFile(cf.path)
        return try {
            val pfd = ParcelFileDescriptor.open(file(c, b), ParcelFileDescriptor.MODE_READ_ONLY)
            val r = PdfRenderer(pfd)
            try {
                val p = r.openPage(0)
                val w = 360
                val h = (w.toFloat() * p.height / p.width).toInt()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                p.close()
                FileOutputStream(cf).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                bmp
            } finally {
                r.close(); pfd.close()
            }
        } catch (e: Exception) {
            null
        }
    }
}

@Composable
fun Cell(act: MainActivity, b: Book, plain: Boolean, rebuild: Int, selected: Boolean, onTap: () -> Unit, onLong: () -> Unit) {
    var bmp by remember(b.id, plain, rebuild) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(b.id, plain, rebuild) {
        if (!plain) bmp = withContext(Dispatchers.IO) { Lib.makeCover(act, b) }
    }
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.fillMaxWidth().aspectRatio(0.7f).clip(shape)
            .background(Color(0xFF455A64))
            .then(if (selected) Modifier.border(3.dp, Color.Cyan, shape) else Modifier)
            .pointerInput(b.id) { detectTapGestures(onTap = { tap() }, onLongPress = { long() }) }
    ) {
        val c = bmp
        if (c != null) {
            Image(c.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                b.title, color = Color.White, fontSize = 14.sp, maxLines = 5,
                modifier = Modifier.align(Alignment.Center).padding(8.dp)
            )
        }
    }
}

@Composable
fun Library(act: MainActivity, dark: Boolean, onTheme: () -> Unit, onOpen: (Book) -> Unit, onMarks: () -> Unit) {
    val prefs = remember { act.getSharedPreferences("p", 0) }
    val scope = rememberCoroutineScope()
    val books = remember { mutableStateListOf<Book>().apply { addAll(Lib.load(act)) } }
    val sel = remember { mutableStateListOf<String>() }
    var filter by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf(prefs.getString("sort", "title") ?: "title") }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var rebuild by remember { mutableStateOf(0) }
    var plain by remember { mutableStateOf(prefs.getBoolean("plain", false)) }
    var mTitle by remember { mutableStateOf(false) }
    var mSort by remember { mutableStateOf(false) }
    var mMore by remember { mutableStateOf(false) }
    var dOpt by remember { mutableStateOf(false) }
    var dAbout by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)

    fun open(b: Book) {
        b.opened = System.currentTimeMillis()
        Lib.save(act, books)
        onOpen(b)
    }

    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val added = withContext(Dispatchers.IO) { uris.mapNotNull { Lib.addPdf(act, it) } }
            books.addAll(added)
            Lib.save(act, books)
            Toast.makeText(act, "${added.size}/${uris.size} PDF importé(s)", Toast.LENGTH_SHORT).show()
        }
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val b = withContext(Dispatchers.IO) { Lib.addPdf(act, u) }
            if (b != null) {
                books.add(b); Lib.save(act, books); open(b)
            } else {
                Toast.makeText(act, "Fichier illisible", Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = sel.isNotEmpty()) { sel.clear() }

    val shown = books
        .filter { query.isBlank() || it.title.contains(query, true) }
        .let { l ->
            if (filter == "recent") l.filter { it.opened > 0 }.sortedByDescending { it.opened }
            else when (sort) {
                "date" -> l.sortedByDescending { it.added }
                "read" -> l.sortedByDescending { it.opened }
                else -> l.sortedBy { it.title.lowercase() }
            }
        }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text("📖 Lecteur PDF", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp))
                NavigationDrawerItem(
                    label = { Text("Liste des récents") }, selected = filter == "recent",
                    onClick = { filter = "recent"; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("Bibliothèque") }, selected = filter == "all",
                    onClick = { filter = "all"; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("Fichiers") }, selected = false,
                    onClick = { scope.launch { drawer.close() }; pickOne.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("Marque-pages et notes") }, selected = false,
                    onClick = { onMarks() },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { dOpt = true }) { Text("Options") }
                    TextButton(onClick = { dAbout = true }) { Text("À propos") }
                    TextButton(onClick = { onTheme() }) { Text(if (dark) "Thème Nuit" else "Thème Jour") }
                    TextButton(onClick = { act.finish() }) { Text("Quitter") }
                }
            }
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (sel.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${sel.size} sélectionné(s)", modifier = Modifier.weight(1f).padding(start = 8.dp))
                        TextButton(onClick = {
                            books.filter { it.id in sel }.forEach { Lib.delete(act, it) }
                            books.removeAll { it.id in sel }
                            Lib.save(act, books)
                            sel.clear()
                        }) { Text("Retirer") }
                        TextButton(onClick = { sel.clear() }) { Text("Annuler") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Text("☰", fontSize = 22.sp) }
                        if (searching) {
                            TextField(
                                value = query, onValueChange = { query = it },
                                placeholder = { Text("Rechercher") }, singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Box(Modifier.weight(1f)) {
                                TextButton(onClick = { mTitle = true }) {
                                    Text(if (filter == "recent") "Récents ▾" else "Tous les livres ▾", fontSize = 18.sp)
                                }
                                DropdownMenu(expanded = mTitle, onDismissRequest = { mTitle = false }) {
                                    DropdownMenuItem(text = { Text("Tous les livres") }, onClick = { filter = "all"; mTitle = false })
                                    DropdownMenuItem(text = { Text("Récents") }, onClick = { filter = "recent"; mTitle = false })
                                }
                            }
                        }
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Text("🔍", fontSize = 20.sp) }
                        Box {
                            IconButton(onClick = { mSort = true }) { Text("⇅", fontSize = 22.sp) }
                            DropdownMenu(expanded = mSort, onDismissRequest = { mSort = false }) {
                                listOf("title" to "Titre", "date" to "Date d'ajout", "read" to "Dernière lecture").forEach { (k, label) ->
                                    DropdownMenuItem(
                                        text = { Text(if (sort == k) "✓ $label" else label) },
                                        onClick = { sort = k; filter = "all"; prefs.edit().putString("sort", k).apply(); mSort = false }
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { mMore = true }) { Text("⋮", fontSize = 22.sp) }
                            DropdownMenu(expanded = mMore, onDismissRequest = { mMore = false }) {
                                DropdownMenuItem(text = { Text("Importer des livres") }, onClick = {
                                    mMore = false; pickMany.launch(arrayOf("application/pdf"))
                                })
                                DropdownMenuItem(text = { Text(if (plain) "✓ Couverture par défaut" else "Couverture par défaut") }, onClick = {
                                    plain = !plain; prefs.edit().putBoolean("plain", plain).apply(); mMore = false
                                })
                                DropdownMenuItem(text = { Text("Reconstruire les couvertures de livres") }, onClick = {
                                    books.forEach { Lib.cover(act, it).delete() }
                                    rebuild++; mMore = false
                                })
                                DropdownMenuItem(text = { Text("Ouvre un livre au hasard") }, onClick = {
                                    mMore = false
                                    if (books.isNotEmpty()) open(books.random())
                                })
                                DropdownMenuItem(text = { Text("Tout sélectionner") }, onClick = {
                                    sel.clear(); sel.addAll(books.map { it.id }); mMore = false
                                })
                            }
                        }
                    }
                }

                if (shown.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(if (books.isEmpty()) "Ta bibliothèque est vide." else "Aucun résultat.")
                        if (books.isEmpty()) {
                            Button(onClick = { pickMany.launch(arrayOf("application/pdf")) }, modifier = Modifier.padding(top = 16.dp)) {
                                Text("Importer des livres")
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(shown, key = { it.id }) { b ->
                            Cell(
                                act, b, plain, rebuild, b.id in sel,
                                onTap = { if (sel.isNotEmpty()) { if (b.id in sel) sel.remove(b.id) else sel.add(b.id) } else open(b) },
                                onLong = { if (b.id !in sel) sel.add(b.id) }
                            )
                        }
                    }
                }
            }
            FloatingActionButton(
                onClick = { books.filter { it.opened > 0 }.maxByOrNull { it.opened }?.let { open(it) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            ) { Text("📖", fontSize = 22.sp) }
        }
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
            title = { Text("Options") },
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
            text = { Text("Lecteur PDF avec bibliothèque, thèmes, reprise de lecture, signets et défilement par gestes de la main.") }
        )
    }
}

@Composable
fun Marks(act: MainActivity, onBack: () -> Unit, onOpen: (Book, Int) -> Unit) {
    val prefs = remember { act.getSharedPreferences("p", 0) }
    val books = remember { Lib.load(act) }
    val marks = remember {
        books.flatMap { b ->
            (prefs.getStringSet("bm_${b.id}", emptySet()) ?: emptySet()).map { it.toInt() }.sorted().map { b to it }
        }
    }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
            Text("Marque-pages et notes", style = MaterialTheme.typography.titleLarge)
        }
        if (marks.isEmpty()) Text("Aucun marque-page pour l'instant.", modifier = Modifier.padding(16.dp))
        LazyColumn {
            items(marks) { (b, p) ->
                TextButton(onClick = { onOpen(b, p) }) { Text("${b.title}  —  page ${p + 1}") }
            }
        }
    }
}
