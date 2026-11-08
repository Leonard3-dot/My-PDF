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
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Un livre. Les champs ajoutés (favori, empreinte, pages, position, signets) sont enregistrés par Store. */
class Book(val id: String, var title: String, val added: Long, var opened: Long) {
    var fav = false
    var hash = ""
    var pages = 0
    var page = 0
    var offset = 0
    val marks = mutableListOf<BookMark>()
}

/** Progression de lecture (0 à 1) d'après la dernière page vue. */
fun bookProgress(b: Book): Float =
    if (b.pages > 1 && b.opened > 0) ((b.page + 1).toFloat() / b.pages).coerceIn(0f, 1f) else 0f

/** Bibliothèque : les PDF sont copiés dans le stockage de l'app (marche avec Drive, WhatsApp, Téléchargements...). */
object Lib {
    fun dir(c: Context) = File(c.filesDir, "books").apply { mkdirs() }
    fun coverDir(c: Context) = File(c.filesDir, "covers").apply { mkdirs() }
    fun file(c: Context, b: Book) = File(dir(c), b.id)
    fun cover(c: Context, b: Book) = File(coverDir(c), b.id + ".png")

    private fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun digest(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(8192)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return toHex(md.digest())
    }

    /** Empreinte SHA-256 du fichier d'un livre (calculée une fois, puis mémorisée). */
    fun hashOf(c: Context, b: Book): String? {
        if (b.hash.isNotBlank()) return b.hash
        val f = file(c, b)
        if (!f.exists()) return null
        val h = try {
            digest(f)
        } catch (e: Exception) {
            return null
        }
        b.hash = h
        Store.saveSoon()
        return h
    }

    /** Supprime les fichiers temporaires restés après une erreur. */
    fun cleanup(c: Context) {
        dir(c).listFiles()?.forEach {
            if (it.name.endsWith(".tmp") || it.name.endsWith(".part")) it.delete()
        }
    }

    /** Liste des livres (ce sont les objets de la base : les modifier puis appeler save). */
    fun load(c: Context): MutableList<Book> {
        Store.init(c)
        return Store.bookList()
    }

    fun save(c: Context, l: List<Book>) {
        Store.init(c)
        // distinctBy est appliqué par Store : jamais deux livres avec le même identifiant
        Store.setBooks(l)
    }

    /**
     * Copie le PDF dans l'app. Si le même fichier est déjà dans la bibliothèque,
     * renvoie le livre existant au lieu d'en créer un second.
     */
    fun addStream(c: Context, rawName: String, input: java.io.InputStream): Book? {
        val name = rawName.ifBlank { "PDF" }
        val id = System.currentTimeMillis().toString() + (name.hashCode() and 0xffff)
        val part = File(dir(c), "$id.part")
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            input.use { i ->
                part.outputStream().use { o ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        md.update(buf, 0, n)
                    }
                }
            }
            if (part.length() == 0L) {
                part.delete()
                return null
            }
            val h = toHex(md.digest())
            val size = part.length()
            val dup = load(c).firstOrNull { b -> file(c, b).length() == size && hashOf(c, b) == h }
            if (dup != null) {
                part.delete()
                dup
            } else {
                val out = File(dir(c), id)
                if (!part.renameTo(out)) {
                    part.copyTo(out, overwrite = true)
                    part.delete()
                }
                Book(id, name.removeSuffix(".pdf").removeSuffix(".PDF"), System.currentTimeMillis(), 0L).also { it.hash = h }
            }
        } catch (e: Exception) {
            part.delete()
            null
        }
    }

    fun addPdf(c: Context, uri: Uri): Book? {
        var name = "PDF"
        try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { q ->
                if (q.moveToFirst()) name = q.getString(0) ?: name
            }
        } catch (_: Exception) {
        }
        val ins = runCatching { c.contentResolver.openInputStream(uri) }.getOrNull() ?: return null
        return addStream(c, name, ins)
    }

    /** PDF protégé : on déchiffre une copie (le mot de passe n'est plus demandé ensuite). */
    fun unlock(c: Context, b: Book, pwd: String): Boolean {
        val tmp = File(dir(c), b.id + ".tmp")
        return try {
            PDFBoxResourceLoader.init(c.applicationContext)
            val f = file(c, b)
            PDDocument.load(f, pwd).use { d ->
                d.setAllSecurityToBeRemoved(true)
                d.save(tmp)
            }
            tmp.copyTo(f, overwrite = true)
            b.hash = ""
            Store.saveSoon()
            true
        } catch (e: Exception) {
            false
        } finally {
            // toujours effacé, que le déverrouillage ait réussi ou non
            tmp.delete()
        }
    }

    fun delete(c: Context, b: Book) {
        file(c, b).delete(); cover(c, b).delete(); DocText.clear(c, b)
        Store.purge(b.id)
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
fun Library(act: MainActivity, dark: Boolean, onTheme: () -> Unit, onOpen: (Book) -> Unit, onMarks: () -> Unit, onOpds: () -> Unit, onGuide: (Boolean) -> Unit) {
    val prefs = remember { act.getSharedPreferences("p", 0) }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val books = remember { mutableStateListOf<Book>().apply { addAll(Lib.load(act)) } }
    val sel = remember { mutableStateListOf<String>() }
    var filter by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf(prefs.getString("sort", "title") ?: "title") }
    var query by remember { mutableStateOf("") }
    var rebuild by remember { mutableStateOf(0) }
    var plain by remember { mutableStateOf(prefs.getBoolean("plain", false)) }
    var mSort by remember { mutableStateOf(false) }
    var mMore by remember { mutableStateOf(false) }
    var dOpt by remember { mutableStateOf(false) }
    var dAbout by remember { mutableStateOf(false) }
    var tour by remember { mutableStateOf(!prefs.getBoolean("tour_done", false)) }
    val drawer = rememberDrawerState(DrawerValue.Closed)

    fun open(b: Book) {
        b.opened = System.currentTimeMillis()
        Lib.save(act, books)
        onOpen(b)
    }

    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            // chaque livre est enregistré au fur et à mesure : deux fichiers identiques dans le même lot sont aussi repérés
            val r = withContext(Dispatchers.IO) {
                val fresh = mutableListOf<Book>()
                var dup = 0
                var bad = 0
                for (u in uris) {
                    val b = Lib.addPdf(act, u)
                    if (b == null) {
                        bad++
                        continue
                    }
                    val l = Lib.load(act)
                    if (l.any { it.id == b.id }) {
                        dup++
                    } else {
                        l.add(b)
                        Lib.save(act, l)
                        fresh.add(b)
                    }
                }
                Triple(fresh, dup, bad)
            }
            books.addAll(r.first)
            val msg = buildString {
                append("${r.first.size} PDF importé(s)")
                if (r.second > 0) append(", ${r.second} déjà présent(s)")
                if (r.third > 0) append(", ${r.third} illisible(s)")
            }
            Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
        }
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val b = withContext(Dispatchers.IO) { Lib.addPdf(act, u) }
            if (b != null) {
                val known = books.firstOrNull { it.id == b.id }
                if (known == null) {
                    books.add(b); Lib.save(act, books); open(b)
                } else {
                    open(known)
                }
            } else {
                Toast.makeText(act, "Fichier illisible", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val exportL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { Store.exportTo(act, u) }
            Toast.makeText(act, if (ok) "Sauvegarde enregistrée" else "Échec de la sauvegarde", Toast.LENGTH_SHORT).show()
        }
    }
    val importL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val msg = withContext(Dispatchers.IO) { Store.restoreFrom(act, u) }
            val fresh = Lib.load(act)
            books.clear()
            books.addAll(fresh)
            Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
        }
    }

    BackHandler(enabled = sel.isNotEmpty()) { sel.clear() }

    // catégories présentes dans la bibliothèque, les plus fréquentes d'abord
    val allTags = books.flatMap { tagsOf(it.title) }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }

    val shown = books
        .filter { query.isBlank() || it.title.contains(query, true) || cleanTitle(it.title).contains(query, true) }
        .let { l ->
            when {
                filter == "recent" -> l.filter { it.opened > 0 }.sortedByDescending { it.opened }
                filter == "fav" -> l.filter { it.fav }.sortedBy { cleanTitle(it.title).lowercase() }
                filter.startsWith("tag:") -> {
                    val t = filter.removePrefix("tag:")
                    l.filter { t in tagsOf(it.title) }.sortedBy { cleanTitle(it.title).lowercase() }
                }
                else -> when (sort) {
                    "date" -> l.sortedByDescending { it.added }
                    "read" -> l.sortedByDescending { it.opened }
                    else -> l.sortedBy { cleanTitle(it.title).lowercase() }
                }
            }
        }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp)) {
                    Text("My PDF", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                    Text(
                        "${books.size} livre(s)", fontSize = 13.sp, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                NavigationDrawerItem(
                    icon = { Ico(Ic.Book) }, label = { Text("Bibliothèque") }, selected = filter == "all",
                    onClick = { filter = "all"; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Ico(Ic.Clock) }, label = { Text("Récents") }, selected = filter == "recent",
                    onClick = { filter = "recent"; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Ico(Ic.Bookmark) }, label = { Text("Favoris") }, selected = filter == "fav",
                    onClick = { filter = "fav"; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Ico(Ic.File) }, label = { Text("Ouvrir un fichier") }, selected = false,
                    onClick = { scope.launch { drawer.close() }; pickOne.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Ico(Ic.Globe) }, label = { Text("Catalogues OPDS") }, selected = false,
                    onClick = { onOpds() },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Ico(Ic.List) }, label = { Text("Marque-pages et notes") }, selected = false,
                    onClick = { onMarks() },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    icon = { Ico(Ic.Help) }, label = { Text("Guide et manuel") }, selected = false,
                    onClick = { onGuide(false) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { dOpt = true }) { Text("Options") }
                    TextButton(onClick = { dAbout = true }) { Text("À propos") }
                    TextButton(onClick = { onTheme() }) { Text(if (dark) "Thème clair" else "Thème sombre") }
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
                            val chosen = books.filter { it.id in sel }
                            val makeFav = chosen.any { !it.fav }
                            chosen.forEach { it.fav = makeFav }
                            Lib.save(act, books)
                            val copy = books.toList()
                            books.clear()
                            books.addAll(copy)
                            sel.clear()
                        }) { Text("Favori") }
                        TextButton(onClick = {
                            books.filter { it.id in sel }.forEach { Lib.delete(act, it) }
                            books.removeAll { it.id in sel }
                            Lib.save(act, books)
                            sel.clear()
                        }) { Text("Retirer") }
                        TextButton(onClick = { sel.clear() }) { Text("Annuler") }
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBtn(Ic.Menu) { scope.launch { drawer.open() } }
                            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                                Text("Bibliothèque", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text("${books.size} livre(s)", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            Box {
                                IconBtn(Ic.Sort) { mSort = true }
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
                                IconBtn(Ic.More) { mMore = true }
                                DropdownMenu(expanded = mMore, onDismissRequest = { mMore = false }) {
                                    DropdownMenuItem(text = { Text("Importer des livres") }, onClick = {
                                        mMore = false; pickMany.launch(arrayOf("application/pdf"))
                                    })
                                    DropdownMenuItem(text = { Text(if (plain) "✓ Sans miniature" else "Sans miniature") }, onClick = {
                                        plain = !plain; prefs.edit().putBoolean("plain", plain).apply(); mMore = false
                                    })
                                    DropdownMenuItem(text = { Text("Reconstruire les miniatures") }, onClick = {
                                        books.forEach { Lib.cover(act, it).delete() }
                                        rebuild++; mMore = false
                                    })
                                    DropdownMenuItem(text = { Text("Ouvrir un livre au hasard") }, onClick = {
                                        mMore = false
                                        if (books.isNotEmpty()) open(books.random())
                                    })
                                    DropdownMenuItem(text = { Text("Tout sélectionner") }, onClick = {
                                        sel.clear(); sel.addAll(books.map { it.id }); mMore = false
                                    })
                                }
                            }
                        }
                        SearchField(
                            query, { query = it },
                            Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp)
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Pill("Tous", filter == "all") { filter = "all" }
                            Pill("Récents", filter == "recent") { filter = "recent" }
                            Pill("Favoris", filter == "fav") { filter = "fav" }
                            allTags.forEach { t -> Pill(t, filter == "tag:$t") { filter = "tag:$t" } }
                        }
                    }
                }

                if (shown.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            when {
                                books.isEmpty() -> "Commence ta bibliothèque"
                                filter == "fav" && query.isBlank() -> "Aucun favori pour l'instant"
                                else -> "Aucun résultat"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (books.isEmpty()) {
                            Text(
                                "Importe tes PDF pour les retrouver ici, avec leur miniature et ta progression.",
                                color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Button(onClick = { pickMany.launch(arrayOf("application/pdf")) }, modifier = Modifier.padding(top = 16.dp)) {
                                Text("Importer des livres")
                            }
                        } else if (filter == "fav" && query.isBlank()) {
                            Text(
                                "Appuie longuement sur un livre, puis sur Favori.",
                                color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        items(shown, key = { it.id }) { b ->
                            BookCard(
                                act, b, plain, rebuild, b.id in sel,
                                onTap = { if (sel.isNotEmpty()) { if (b.id in sel) sel.remove(b.id) else sel.add(b.id) } else open(b) },
                                onLong = { if (b.id !in sel) sel.add(b.id) },
                                index = shown.indexOf(b)
                            )
                        }
                    }
                }
            }
            val last = books.filter { it.opened > 0 }.maxByOrNull { it.opened }
            AnimatedVisibility(
                visible = last != null && sel.isEmpty(),
                modifier = Modifier.align(Alignment.BottomEnd),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it }
            ) {
                if (last != null) {
                    ExtendedFloatingActionButton(
                        onClick = { open(last) },
                        icon = { Ico(Ic.Play, cs.onPrimary, 22.dp) },
                        text = { Text("Continuer", maxLines = 1) },
                        containerColor = cs.primary,
                        contentColor = cs.onPrimary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }

    if (tour) {
        AlertDialog(
            onDismissRequest = { tour = false; prefs.edit().putBoolean("tour_done", true).apply() },
            confirmButton = {
                TextButton(onClick = {
                    tour = false; prefs.edit().putBoolean("tour_done", true).apply(); onGuide(true)
                }) { Text("Oui, guide-moi") }
            },
            dismissButton = {
                TextButton(onClick = { tour = false; prefs.edit().putBoolean("tour_done", true).apply() }) { Text("Plus tard") }
            },
            title = { Text("Bienvenue dans My PDF") },
            text = { Text("Veux-tu une visite guidée avec une voix qui t'explique l'application ? Tu la retrouveras dans le menu, rubrique Guide et manuel.") }
        )
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
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { exportL.launch("my-pdf-sauvegarde.json") }) { Text("Sauvegarder mes données") }
                    TextButton(onClick = { importL.launch(arrayOf("*/*")) }) { Text("Restaurer une sauvegarde") }
                }
            }
        )
    }
    if (dAbout) {
        AlertDialog(
            onDismissRequest = { dAbout = false },
            confirmButton = { TextButton(onClick = { dAbout = false }) { Text("OK") } },
            title = { Text("À propos") },
            text = { Text("My PDF : bibliothèque, thèmes, reprise de lecture, signets avec notes, catalogues OPDS, PDF protégés et défilement par gestes de la main.") }
        )
    }
}

@Composable
fun Marks(act: MainActivity, onBack: () -> Unit, onOpen: (Book, Int) -> Unit) {
    val books = remember { Lib.load(act) }
    val marks = remember {
        books.flatMap { b ->
            b.marks.sortedBy { it.page }.map { m -> Triple(b, m.page, m.note) }
        }
    }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, onClick = onBack)
            Text("Marque-pages et notes", style = MaterialTheme.typography.titleLarge)
        }
        if (marks.isEmpty()) Text("Aucun marque-page pour l'instant.", modifier = Modifier.padding(16.dp))
        LazyColumn {
            items(marks) { (b, p, n) ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    TextButton(onClick = { onOpen(b, p) }) { Text("${cleanTitle(b.title)}  —  page ${p + 1}") }
                    if (n.isNotBlank()) Text(n, modifier = Modifier.padding(start = 12.dp), fontSize = 14.sp)
                }
            }
        }
    }
}
