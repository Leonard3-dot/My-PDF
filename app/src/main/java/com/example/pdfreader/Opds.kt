package com.example.pdfreader

import android.content.Context
import android.util.Base64
import android.util.Xml
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class OpdsEntry(val title: String, val nav: String?, val pdf: String?)
class OpdsPage(val entries: List<OpdsEntry>, val next: String?)

object Opds {
    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        val c = u.openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "MyPDF/1.0")
        u.userInfo?.let {
            c.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
        }
        return c
    }

    fun fetch(url: String): OpdsPage = open(url).inputStream.use { parse(it, url) }

    fun download(c: Context, url: String, title: String): Book? = try {
        open(url).inputStream.use { Lib.addStream(c, title, it) }
    } catch (e: Exception) {
        null
    }

    private fun parse(ins: InputStream, base: String): OpdsPage {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(ins, null)
        val out = mutableListOf<OpdsEntry>()
        var next: String? = null
        var inEntry = false
        var title = ""
        var nav: String? = null
        var pdf: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "entry" -> { inEntry = true; title = ""; nav = null; pdf = null }
                    "title" -> if (inEntry) title = p.nextText()
                    "link" -> {
                        val rel = p.getAttributeValue(null, "rel") ?: ""
                        val type = p.getAttributeValue(null, "type") ?: ""
                        val href = p.getAttributeValue(null, "href") ?: ""
                        if (href.isNotEmpty()) {
                            val abs = try { URI(base).resolve(href).toString() } catch (e: Exception) { href }
                            if (!inEntry) {
                                if (rel == "next") next = abs
                            } else if (rel.contains("acquisition") && type.contains("pdf")) {
                                pdf = abs
                            } else if (rel != "alternate" && (type.contains("atom+xml") || type.contains("opds-catalog"))) {
                                if (nav == null) nav = abs
                            }
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "entry") {
                inEntry = false
                if (pdf != null || nav != null) out.add(OpdsEntry(title.ifBlank { "(sans titre)" }, nav, pdf))
            }
            ev = p.next()
        }
        return OpdsPage(out, next)
    }
}

@Composable
fun OpdsScreen(act: MainActivity, onBack: () -> Unit) {
    val prefs = remember { act.getSharedPreferences("p", 0) }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf((prefs.getString("opds_list", "") ?: "").split("\n").filter { it.isNotBlank() }) }
    var field by remember { mutableStateOf("") }
    val stack = remember { mutableStateListOf<String>() }
    var entries by remember { mutableStateOf<List<OpdsEntry>>(emptyList()) }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun load(url: String, append: Boolean = false) {
        loading = true
        msg = null
        scope.launch {
            try {
                val pg = withContext(Dispatchers.IO) { Opds.fetch(url) }
                entries = if (append) entries + pg.entries else pg.entries
                next = pg.next
                if (entries.isEmpty()) msg = "Aucun PDF ni sous-catalogue ici."
            } catch (e: Exception) {
                msg = "Erreur : ${e.message ?: "réseau"}"
            }
            loading = false
        }
    }

    fun go(url: String) {
        stack.add(url)
        entries = emptyList(); next = null
        load(url)
    }

    BackHandler {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            load(stack.last())
        } else if (stack.size == 1) {
            stack.clear(); entries = emptyList(); next = null; msg = null
        } else onBack()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back) {
                if (stack.isEmpty()) onBack() else { stack.clear(); entries = emptyList(); next = null; msg = null }
            }
            Text("Catalogues OPDS", style = MaterialTheme.typography.titleLarge)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        msg?.let { Text(it, modifier = Modifier.padding(vertical = 8.dp)) }

        if (stack.isEmpty()) {
            Text(
                "Colle l'adresse d'un catalogue OPDS (Calibre-Web, Kavita, Komga...). " +
                    "Avec identifiants : http://utilisateur:motdepasse@serveur/opds",
                modifier = Modifier.padding(vertical = 8.dp)
            )
            TextField(
                value = field, onValueChange = { field = it },
                placeholder = { Text("https://...") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val u = field.trim()
                    if (u.startsWith("http")) {
                        saved = saved + u
                        prefs.edit().putString("opds_list", saved.joinToString("\n")).apply()
                        field = ""
                    }
                },
                modifier = Modifier.padding(vertical = 8.dp)
            ) { Text("Ajouter ce catalogue") }
            LazyColumn {
                items(saved) { u ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { go(u) }, modifier = Modifier.weight(1f)) {
                            Text(u.substringAfter("@").take(60), maxLines = 1)
                        }
                        TextButton(onClick = {
                            saved = saved - u
                            prefs.edit().putString("opds_list", saved.joinToString("\n")).apply()
                        }) { Text("🗑") }
                    }
                }
            }
        } else {
            LazyColumn {
                items(entries) { e ->
                    TextButton(onClick = {
                        if (e.pdf != null) {
                            scope.launch {
                                msg = "Téléchargement…"
                                val b = withContext(Dispatchers.IO) { Opds.download(act, e.pdf, e.title) }
                                if (b != null) {
                                    val l = Lib.load(act)
                                    l.add(b)
                                    Lib.save(act, l)
                                    msg = "Ajouté à la bibliothèque : ${b.title}"
                                } else msg = "Échec du téléchargement."
                            }
                        } else if (e.nav != null) go(e.nav)
                    }) { Text((if (e.pdf != null) "⬇ " else "📁 ") + e.title, maxLines = 2) }
                }
                if (next != null) {
                    item {
                        TextButton(onClick = { load(next!!, append = true) }) { Text("Page suivante…") }
                    }
                }
            }
        }
    }
}
