package com.example.pdfreader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Un PDF trouvé sur l'appareil. */
class FoundPdf(val path: String, val name: String, val size: Long, val modified: Long)

/** Scan global du stockage : tout se passe sur le téléphone, rien n'est envoyé nulle part. */
object DeviceScan {

    /** Android 11 et plus : accès à tous les fichiers (réglage système). Avant : autorisation de lecture du stockage. */
    fun hasAccess(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Page de réglages « Accès à tous les fichiers » de l'application (Android 11 et plus). */
    fun settingsIntent(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + c.packageName))

    /** Stockage interne et cartes SD. */
    private fun roots(c: Context): List<File> {
        val out = LinkedHashSet<File>()
        out.add(Environment.getExternalStorageDirectory())
        for (d in c.getExternalFilesDirs(null)) {
            if (d == null) continue
            val p = d.absolutePath
            val i = p.indexOf("/Android/data")
            if (i > 0) out.add(File(p.substring(0, i)))
        }
        return out.filter { it.exists() && it.canRead() }
    }

    /** Parcourt tout le stockage et renvoie les PDF (les plus récents d'abord). Les dossiers cachés et Android/data sont ignorés. */
    suspend fun scan(c: Context, onProgress: (Int, String) -> Unit): List<FoundPdf> = withContext(Dispatchers.IO) {
        val out = ArrayList<FoundPdf>()
        var dirs = 0
        for (root in roots(c)) {
            root.walkTopDown()
                .onEnter { d ->
                    val n = d.name
                    !(n.startsWith(".") || (d.parentFile?.name == "Android" && (n == "data" || n == "obb")))
                }
                .onFail { _, _ -> }
                .forEach { f ->
                    ensureActive()
                    if (f.isDirectory) {
                        dirs++
                        if (dirs % 40 == 0) onProgress(out.size, f.name)
                    } else if (f.name.endsWith(".pdf", ignoreCase = true)) {
                        val len = f.length()
                        if (len >= 1024) out.add(FoundPdf(f.absolutePath, f.name, len, f.lastModified()))
                    }
                }
            if (out.size >= 5000) break
        }
        out.distinctBy { it.path }.sortedByDescending { it.modified }
    }

    /** Copie les PDF choisis dans la bibliothèque. Renvoie (importés, déjà présents, illisibles). */
    suspend fun importAll(c: Context, items: List<FoundPdf>, onProgress: (Int, Int) -> Unit): Triple<Int, Int, Int> =
        withContext(Dispatchers.IO) {
            var ok = 0
            var dup = 0
            var bad = 0
            items.forEachIndexed { i, f ->
                ensureActive()
                val b = try {
                    Lib.addStream(c, f.name, File(f.path).inputStream())
                } catch (e: Exception) {
                    null
                }
                if (b == null) {
                    bad++
                } else {
                    val l = Lib.load(c)
                    if (l.any { x -> x.id == b.id }) {
                        dup++
                    } else {
                        l.add(b)
                        Lib.save(c, l)
                        ok++
                    }
                }
                onProgress(i + 1, items.size)
                delay(3) // évite deux identifiants identiques pour des fichiers de même nom
            }
            Triple(ok, dup, bad)
        }
}

private fun sizeLabel(s: Long): String =
    if (s < 1_048_576L) "${s / 1024} Ko" else String.format(java.util.Locale.US, "%.1f Mo", s / 1_048_576f)

/** Écran du scan : autorisation, recherche, choix des PDF, import. */
@Composable
fun ScanSheet(act: MainActivity, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var access by remember { mutableStateOf(DeviceScan.hasAccess(act)) }
    var phase by remember { mutableStateOf("intro") } // intro, scanning, results, importing, done
    var found by remember { mutableStateOf<List<FoundPdf>>(emptyList()) }
    val picked = remember { mutableStateListOf<String>() }
    var scanInfo by remember { mutableStateOf(0 to "") }
    var prog by remember { mutableStateOf(0 to 0) }
    var summary by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }

    // au retour de la page de réglages, on revérifie l'autorisation
    val settingsL = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        access = DeviceScan.hasAccess(act)
    }
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        access = DeviceScan.hasAccess(act)
    }

    fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                settingsL.launch(DeviceScan.settingsIntent(act))
            } catch (e: Exception) {
                try {
                    settingsL.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    Toast.makeText(act, "Ouvre Réglages > Applications > My PDF > Autorisations", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            permL.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun startScan() {
        phase = "scanning"
        scanInfo = 0 to ""
        job = scope.launch {
            val r = DeviceScan.scan(act) { n, d -> scanInfo = n to d }
            found = r
            picked.clear()
            filter = ""
            phase = "results"
        }
    }

    fun startImport() {
        val chosen = found.filter { it.path in picked }
        if (chosen.isEmpty()) return
        phase = "importing"
        prog = 0 to chosen.size
        scope.launch {
            val r = DeviceScan.importAll(act, chosen) { i, n -> prog = i to n }
            summary = buildString {
                append("${r.first} PDF importé(s)")
                if (r.second > 0) append(", ${r.second} déjà présent(s)")
                if (r.third > 0) append(", ${r.third} illisible(s)")
            }
            phase = "done"
            // catégories et résumés se préparent en arrière-plan
            scope.launch(Dispatchers.IO) { Smart.analyzeMissing(act) }
        }
    }

    Dialog(onDismissRequest = { job?.cancel(); onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Ic.Close) { job?.cancel(); onClose() }
                    Text("Scanner l'appareil", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                }

                when (phase) {
                    "intro" -> Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Text("Retrouve tous les PDF de ton téléphone", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
                        Text(
                            "My PDF parcourt ton stockage (Téléchargements, Documents, WhatsApp, carte SD…) pour lister tes PDF. " +
                                "Le scan se fait sur le téléphone : rien n'est envoyé sur Internet. Tu choisis ensuite ceux à ajouter.",
                            fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp)
                        )
                        if (!access) {
                            if (Build.VERSION.SDK_INT >= 30) {
                                Text(
                                    "Android va ouvrir une page de réglages : active « Autoriser l'accès pour gérer tous les fichiers », puis reviens dans l'application.",
                                    fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)
                                )
                            }
                            Button(onClick = { askAccess() }, modifier = Modifier.padding(top = 18.dp)) {
                                Text("Autoriser l'accès aux fichiers")
                            }
                        } else {
                            Text("Accès aux fichiers autorisé.", fontSize = 13.sp, color = cs.secondary, modifier = Modifier.padding(top = 12.dp))
                            Button(onClick = { startScan() }, modifier = Modifier.padding(top = 18.dp)) { Text("Lancer le scan") }
                        }
                    }

                    "scanning" -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.padding(top = 32.dp))
                        Text("${scanInfo.first} PDF trouvé(s)", fontSize = 16.sp, color = cs.onSurface, modifier = Modifier.padding(top = 20.dp))
                        if (scanInfo.second.isNotEmpty()) {
                            Text(
                                "Dossier : ${scanInfo.second}", fontSize = 12.sp, color = cs.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        TextButton(onClick = { job?.cancel(); phase = "intro" }, modifier = Modifier.padding(top = 16.dp)) { Text("Annuler") }
                    }

                    "results" -> {
                        if (found.isEmpty()) {
                            Column(Modifier.fillMaxSize().padding(24.dp)) {
                                Text("Aucun PDF trouvé.", fontSize = 16.sp, color = cs.onSurface)
                                Button(onClick = { startScan() }, modifier = Modifier.padding(top = 16.dp)) { Text("Relancer le scan") }
                            }
                        } else {
                            val shownList = found.filter { filter.isBlank() || it.name.contains(filter, true) }
                            Column(Modifier.fillMaxSize()) {
                                SearchField(filter, { filter = it }, Modifier.padding(horizontal = 16.dp))
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${found.size} PDF trouvés · ${picked.size} choisi(s)", fontSize = 13.sp,
                                        color = cs.onSurfaceVariant, modifier = Modifier.weight(1f)
                                    )
                                    val allShown = shownList.isNotEmpty() && shownList.all { it.path in picked }
                                    TextButton(onClick = {
                                        if (allShown) picked.removeAll(shownList.map { it.path }.toSet())
                                        else shownList.forEach { if (it.path !in picked) picked.add(it.path) }
                                    }) { Text(if (allShown) "Tout décocher" else "Tout cocher") }
                                }
                                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    items(shownList, key = { it.path }) { f ->
                                        val on = f.path in picked
                                        Row(
                                            Modifier.fillMaxWidth()
                                                .clickable { if (on) picked.remove(f.path) else picked.add(f.path) }
                                                .padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(checked = on, onCheckedChange = { v -> if (v) picked.add(f.path) else picked.remove(f.path) })
                                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    cleanTitle(f.name), fontSize = 14.sp, color = cs.onSurface,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                                )
                                                val folder = File(f.path).parent.orEmpty().removePrefix("/storage/emulated/0").ifBlank { "/" }
                                                Text(
                                                    "$folder · ${sizeLabel(f.size)}", fontSize = 11.sp, color = cs.onSurfaceVariant,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                                Button(
                                    onClick = { startImport() },
                                    enabled = picked.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                                ) { Text("Importer (${picked.size})") }
                            }
                        }
                    }

                    "importing" -> Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Text("Import en cours…", fontSize = 16.sp, color = cs.onSurface)
                        val (i, n) = prog
                        if (n > 0) {
                            LinearProgressIndicator(progress = { i.toFloat() / n }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                            Text("$i sur $n", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        }
                    }

                    else -> Column(Modifier.fillMaxSize().padding(24.dp)) {
                        Text(summary, fontSize = 16.sp, color = cs.onSurface)
                        Text(
                            "Retrouve-les dans l'onglet Bibliothèque. Les catégories et les résumés se préparent en arrière-plan.",
                            fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                        )
                        Button(onClick = onClose, modifier = Modifier.padding(top = 16.dp)) { Text("Fermer") }
                    }
                }
            }
        }
    }
}
