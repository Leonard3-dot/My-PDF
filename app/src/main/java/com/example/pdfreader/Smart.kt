package com.example.pdfreader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Un chapitre avec ses phrases clés. */
class Chapter(val title: String, val page: Int, val points: List<String>)

/** Résultat de l'analyse d'un livre (tout est calculé sur le téléphone, sans Internet). */
class Analysis(
    val tags: List<String>,
    val keywords: List<String>,
    val words: Int,
    val chapters: List<Chapter>,
    /** Résumé de tout le livre (une phrase clé par grande partie). null = analyse ancienne, à refaire. */
    val summary: List<String>? = null
)

/** Bloc de code isolé d'un PDF technique (page en base 0). */
class CodeBlock(val page: Int, val text: String)

private val StopWords: Set<String> = (
    "le la les un une des du de dans en et est que qui pour par sur avec pas plus son ses ces cette cet ce sont être avoir " +
        "fait faire comme mais ou où donc car aux au nous vous ils elles leur leurs aussi tout tous toute toutes très peut " +
        "sans sous entre chez the and for are but not you all can had her was one our out has his how its may new now see " +
        "two way who did get him old per use that this with from have will your they their what when which there been more " +
        "also into than then them these those such only other about"
    ).split(" ").toSet()

private val WordRe = Regex("[\\p{L}][\\p{L}0-9_]{3,}")
private val SentRe = Regex("(?<=[.!?])\\s+(?=[A-ZÀ-ÖØ-Þ0-9])")
private val ChapRe = Regex(
    "^(chapitre|chapter|partie|part|section|module|leçon|lecon|lesson)\\s+[0-9ivxIVX]+.{0,70}$",
    RegexOption.IGNORE_CASE
)
private val NumRe = Regex("^[0-9]{1,2}[.)]?\\s+[A-ZÀ-ÖØ-Þ][^.!?]{3,60}$")

private val MonoNames = listOf(
    "courier", "mono", "consol", "menlo", "inconsolata", "lucida console", "sourcecode", "source code",
    "firacode", "fira code", "hack", "code", "typewriter"
)

private fun isMonoFont(name: String?): Boolean {
    if (name == null) return false
    val n = name.lowercase()
    return MonoNames.any { n.contains(it) }
}

private fun countOcc(s: String, k: String): Int {
    var n = 0
    var i = s.indexOf(k)
    while (i >= 0) {
        n++
        i = s.indexOf(k, i + k.length)
    }
    return n
}

private fun strs(a: JSONArray?): List<String> {
    val l = ArrayList<String>()
    if (a != null) for (i in 0 until a.length()) l.add(a.optString(i))
    return l
}

/** Lit les lignes du PDF avec leur police, pour repérer le code (chasse fixe) et garder l'indentation. */
private class CodeStripper : PDFTextStripper() {
    class Ln(val page: Int, val mono: Boolean, val x: Float, val cw: Float, val text: String)

    val lines = ArrayList<Ln>()
    var page = 0
    private val cur = StringBuilder()
    private val pos = ArrayList<TextPosition>()

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        if (textPositions.isEmpty()) return
        cur.append(text)
        pos.addAll(textPositions)
    }

    override fun writeWordSeparator() {
        cur.append(' ')
    }

    override fun writeLineSeparator() {
        flushLine()
    }

    fun flushLine() {
        if (pos.isNotEmpty() && cur.isNotBlank()) {
            val mono = pos.count { isMonoFont(it.font?.name) } * 2 > pos.size
            val cw = pos.map { it.widthDirAdj }.average().toFloat()
            lines.add(Ln(page, mono, pos[0].xDirAdj, cw, cur.toString()))
        }
        cur.setLength(0)
        pos.clear()
    }
}

/** Mots précis qui, répétés dans les premières pages, révèlent le sujet d'un livre. */
private val ContentRules = listOf(
    "Python" to listOf("python", "pandas", "numpy", "matplotlib", "jupyter", "pip install", "def ", "self."),
    "SQL" to listOf("sql", "insert into", "create table", "group by", "primary key", "inner join", "left join"),
    "Data" to listOf("dataset", "dataframe", "data frame", "data analysis", "analyse de donn", "régression", "regression", "statisti"),
    "IA" to listOf("machine learning", "deep learning", "réseau de neurones", "neural network", "tensorflow", "pytorch", "apprentissage automatique"),
    "Excel" to listOf("excel", "tableau croisé", "pivot table", "vba", "feuille de calcul"),
    "Web" to listOf("html", "css", "javascript", "<div", "node.js", "react.js"),
    "Java" to listOf("public class", "jvm", "spring boot"),
    "Kotlin" to listOf("kotlin", "jetpack", "android"),
    "Cloud" to listOf("docker", "kubernetes", "aws", "azure", "linux", "bash")
)

object Smart {
    /** Change quand une analyse se termine : les écrans qui l'ont lu se mettent à jour. */
    var version by mutableStateOf(0)
        private set

    private val running = AtomicBoolean(false)
    private val memo = ConcurrentHashMap<String, Analysis>()
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun dir(c: Context) = File(c.filesDir, "smart").apply { mkdirs() }
    private fun fa(c: Context, b: Book) = File(dir(c), b.id + ".json")
    private fun fc(c: Context, b: Book) = File(dir(c), b.id + ".code.json")

    // ---------- catégories ----------

    /** Catégories d'un livre : celles trouvées dans son contenu (si analysé) plus celles de son titre. */
    fun tagsFor(c: Context, b: Book): List<String> {
        if (version < 0) return emptyList()
        val a = readAnalysis(c, b)
        val fromTitle = tagsOf(b.title)
        return if (a == null) fromTitle else (a.tags + fromTitle).distinct().take(3)
    }

    fun readAnalysis(c: Context, b: Book): Analysis? {
        memo[b.id]?.let { return it }
        if (missing.contains(b.id)) return null
        val f = fa(c, b)
        if (!f.exists()) {
            missing.add(b.id)
            return null
        }
        return try {
            fromJson(JSONObject(f.readText())).also { memo[b.id] = it }
        } catch (e: Exception) {
            missing.add(b.id)
            null
        }
    }

    fun forget(c: Context, b: Book) {
        memo.remove(b.id)
        missing.remove(b.id)
        fa(c, b).delete()
        fc(c, b).delete()
    }

    // ---------- analyse ----------

    /** Analyse en arrière-plan tous les livres pas encore analysés (un seul passage à la fois). */
    suspend fun analyzeMissing(c: Context) {
        if (!running.compareAndSet(false, true)) return
        try {
            for (b in Lib.load(c)) {
                if (readAnalysis(c, b) == null && !failed.contains(b.id)) {
                    val a = analyze(c, b)
                    if (a == null) failed.add(b.id)
                    kotlinx.coroutines.delay(800) // laisse le processeur à l'interface
                }
            }
        } finally {
            running.set(false)
        }
    }

    suspend fun analyze(c: Context, b: Book, onProgress: (Int, Int) -> Unit = { _, _ -> }): Analysis? =
        withContext(Dispatchers.IO) {
            val pages = DocText.pages(c, b, onProgress) ?: return@withContext null
            if (pages.all { it.isBlank() }) return@withContext null
            val a = try {
                compute(b, pages)
            } catch (e: Exception) {
                return@withContext null
            }
            try {
                fa(c, b).writeText(toJson(a).toString())
            } catch (_: Exception) {
            }
            memo[b.id] = a
            missing.remove(b.id)
            withContext(Dispatchers.Main) { version++ }
            a
        }

    private fun compute(b: Book, pages: List<String>): Analysis {
        val freq = HashMap<String, Int>()
        var total = 0
        for (t in pages) {
            for (m in WordRe.findAll(t.lowercase())) {
                total++
                val w = m.value
                if (w in StopWords) continue
                freq[w] = (freq[w] ?: 0) + 1
            }
        }
        val keywords = freq.entries.filter { it.value >= 3 }
            .sortedByDescending { it.value }.take(8).map { it.key }

        val sample = (listOf(b.title) + pages.take(40)).joinToString(" ").lowercase()
        val tags = ContentRules
            .map { (tag, keys) -> tag to keys.sumOf { k -> countOcc(sample, k) } }
            .filter { it.second >= 10 }
            .sortedByDescending { it.second }.take(3).map { it.first }

        val starts = findChapters(pages)
        val chapters = starts.mapIndexed { k, s ->
            val end = if (k + 1 < starts.size) starts[k + 1].second else pages.size
            val text = (s.second until end).joinToString(" ") { pages[it] }.take(80000)
            Chapter(s.first, s.second, summarize(text))
        }
        return Analysis(tags, keywords, total, chapters, bookSummary(pages))
    }

    private fun scanHeads(pages: List<String>, re: Regex): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for ((i, t) in pages.withIndex()) {
            val head = t.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(6).toList()
            val hit = head.firstOrNull { re.matches(it) } ?: continue
            val title = hit.take(70)
            if (out.isEmpty() || out.last().first != title) out.add(title to i)
        }
        return out
    }

    private fun findChapters(pages: List<String>): List<Pair<String, Int>> {
        var l = scanHeads(pages, ChapRe)
        if (l.size < 2) l = scanHeads(pages, NumRe)
        if (l.size in 2..60) return l
        // pas de titres repérables : on découpe en blocs réguliers
        val n = (pages.size / 15).coerceIn(1, 8)
        val size = (pages.size + n - 1) / n
        return (0 until n).map { k ->
            val from = k * size
            val to = minOf(pages.size, from + size)
            "Pages ${from + 1} à $to" to from
        }.filter { it.second < pages.size }
    }

    /** Résumé par extraction : les phrases les plus riches en mots importants, dans l'ordre du texte. */
    private fun summarize(text: String, k: Int = 3): List<String> {
        val flat = text.replace(Regex("\\s+"), " ")
        val sentences = SentRe.split(flat).map { it.trim() }.filter {
            it.length in 50..280 &&
                it.count { ch -> ch.isLetter() } > it.length * 0.6 &&
                !it.contains("{") && !it.contains("}") && it.count { ch -> ch == '=' } < 2
        }.take(400)
        if (sentences.isEmpty()) return emptyList()
        val local = HashMap<String, Int>()
        for (m in WordRe.findAll(flat.lowercase())) {
            val w = m.value
            if (w !in StopWords) local[w] = (local[w] ?: 0) + 1
        }
        val scored = sentences.mapIndexed { i, s ->
            val ws = WordRe.findAll(s.lowercase()).map { it.value }.filter { it !in StopWords }.toList()
            val score = ws.sumOf { local[it] ?: 0 }.toDouble() / sqrt(ws.size + 1.0)
            Triple(i, s, if (i == 0) score * 1.1 else score)
        }
        return scored.sortedByDescending { it.third }.take(k).sortedBy { it.first }.map { it.second }
    }

    /** Résumé de tout le livre : une phrase clé par grande partie, pour couvrir le début comme la fin. */
    private fun bookSummary(pages: List<String>): List<String> {
        if (pages.isEmpty()) return emptyList()
        val parts = (pages.size / 40).coerceIn(4, 8)
        val size = maxOf(1, (pages.size + parts - 1) / parts)
        val out = ArrayList<String>()
        var from = 0
        while (from < pages.size) {
            val to = minOf(pages.size, from + size)
            val text = (from until to).joinToString(" ") { pages[it] }.take(80000)
            summarize(text, 1).forEach { if (it !in out) out.add(it) }
            from = to
        }
        return out
    }

    private fun toJson(a: Analysis): JSONObject {
        val ch = JSONArray()
        a.chapters.forEach {
            ch.put(JSONObject().put("t", it.title).put("p", it.page).put("b", JSONArray(it.points)))
        }
        return JSONObject().put("tags", JSONArray(a.tags)).put("kw", JSONArray(a.keywords))
            .put("w", a.words).put("ch", ch).put("sm", JSONArray(a.summary ?: emptyList<String>()))
    }

    private fun fromJson(o: JSONObject): Analysis {
        val chs = ArrayList<Chapter>()
        val arr = o.optJSONArray("ch")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                chs.add(Chapter(j.optString("t", ""), j.optInt("p", 0), strs(j.optJSONArray("b"))))
            }
        }
        val sm = if (o.has("sm")) strs(o.optJSONArray("sm")) else null
        return Analysis(strs(o.optJSONArray("tags")), strs(o.optJSONArray("kw")), o.optInt("w", 0), chs, sm)
    }

    // ---------- code ----------

    /** Blocs de code du livre (null si le PDF est illisible). Calculé une fois puis gardé. */
    suspend fun codeBlocks(c: Context, b: Book, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<CodeBlock>? =
        withContext(Dispatchers.IO) {
            val cf = fc(c, b)
            if (cf.exists()) {
                try {
                    val arr = JSONArray(cf.readText())
                    val out = ArrayList<CodeBlock>()
                    for (i in 0 until arr.length()) {
                        val j = arr.getJSONObject(i)
                        out.add(CodeBlock(j.getInt("p"), j.getString("t")))
                    }
                    return@withContext out
                } catch (_: Exception) {
                }
            }
            try {
                PDFBoxResourceLoader.init(c)
                val st = CodeStripper()
                PDDocument.load(Lib.file(c, b)).use { d ->
                    val n = d.numberOfPages
                    for (i in 1..n) {
                        ensureActive()
                        st.page = i - 1
                        st.startPage = i
                        st.endPage = i
                        st.getText(d)
                        st.flushLine()
                        onProgress(i, n)
                    }
                }
                val blocks = buildBlocks(st.lines).take(200)
                val arr = JSONArray()
                blocks.forEach { arr.put(JSONObject().put("p", it.page).put("t", it.text)) }
                try {
                    cf.writeText(arr.toString())
                } catch (_: Exception) {
                }
                blocks
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
        }

    private fun buildBlocks(lines: List<CodeStripper.Ln>): List<CodeBlock> {
        val out = ArrayList<CodeBlock>()
        var cur = ArrayList<CodeStripper.Ln>()
        fun flush() {
            if (cur.isNotEmpty()) {
                val minX = cur.minOf { it.x }
                val cw = cur.map { it.cw }.sorted()[cur.size / 2].coerceAtLeast(1f)
                val text = cur.joinToString("\n") { l ->
                    " ".repeat(((l.x - minX) / cw).roundToInt().coerceIn(0, 40)) + l.text.trim()
                }
                if (cur.size >= 2 || text.length >= 40) out.add(CodeBlock(cur[0].page, text))
                cur = ArrayList()
            }
        }
        for (l in lines) {
            if (l.mono) {
                if (cur.isNotEmpty() && cur.last().page != l.page) flush()
                cur.add(l)
            } else {
                flush()
            }
        }
        flush()
        return out
    }
}

private fun copyText(act: MainActivity, label: String, text: String) {
    val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(act, "Copié", Toast.LENGTH_SHORT).show()
}

/** Fiche du livre : fiche mémo par chapitre et extraits de code, en plein écran. */
@Composable
fun SmartSheet(act: MainActivity, book: Book, onGo: (Int) -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var tab by remember { mutableStateOf("resume") }
    var analysis by remember { mutableStateOf<Analysis?>(null) }
    var state by remember { mutableStateOf(0) } // 0 en cours, 1 prête, 2 pas de texte
    var prog by remember { mutableStateOf(0 to 0) }
    var code by remember { mutableStateOf<List<CodeBlock>?>(null) }
    var codeLoading by remember { mutableStateOf(false) }
    var codeFailed by remember { mutableStateOf(false) }

    LaunchedEffect(book.id) {
        val cached = withContext(Dispatchers.IO) { Smart.readAnalysis(act, book) }
        if (cached != null && cached.summary != null) {
            analysis = cached
            state = 1
        } else {
            // première analyse, ou ancienne analyse sans résumé : on (re)calcule
            val a = Smart.analyze(act, book) { i, n -> prog = i to n }
            analysis = a ?: cached
            state = if (analysis != null) 1 else 2
        }
    }
    LaunchedEffect(tab) {
        if (tab == "code" && code == null && !codeLoading) {
            codeLoading = true
            prog = 0 to 0
            val r = Smart.codeBlocks(act, book) { i, n -> prog = i to n }
            if (r == null) codeFailed = true else code = r
            codeLoading = false
        }
    }

    fun resumeText(a: Analysis): String = buildString {
        append(cleanTitle(book.title)).append("\n\n")
        (a.summary ?: emptyList()).forEach { append("• ").append(it).append("\n") }
        if (a.keywords.isNotEmpty()) append("\nMots-clés : ").append(a.keywords.joinToString(", ")).append("\n")
    }

    fun memoText(a: Analysis): String = buildString {
        append(cleanTitle(book.title)).append("\n\n")
        if (a.keywords.isNotEmpty()) append("Mots-clés : ").append(a.keywords.joinToString(", ")).append("\n\n")
        a.chapters.forEach { ch ->
            append(ch.title).append(" (p. ").append(ch.page + 1).append(")\n")
            ch.points.forEach { append("• ").append(it).append("\n") }
            append("\n")
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Ic.Close) { onClose() }
                    Text("Fiche du livre", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Résumé", tab == "resume") { tab = "resume" }
                    Pill("Fiche mémo", tab == "memo") { tab = "memo" }
                    Pill("Code", tab == "code") { tab = "code" }
                }

                if (tab == "resume") {
                    val a = analysis
                    when {
                        state == 0 -> Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            Text("Analyse du livre…", color = cs.onSurface)
                            val (i, n) = prog
                            if (n > 0) {
                                LinearProgressIndicator(
                                    progress = { i.toFloat() / n },
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                                )
                                Text(
                                    "$i sur $n pages", fontSize = 12.sp, color = cs.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                        a == null -> Text(
                            "Ce PDF ne contient pas de texte exploitable (scan ou images), le résumé n'est pas possible.",
                            color = cs.onSurfaceVariant, modifier = Modifier.padding(24.dp)
                        )
                        else -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)
                        ) {
                            item {
                                val pagesTxt = if (book.pages > 0) "${book.pages} pages · " else ""
                                val readTxt = fmtDur((a.words / 230f * 60f).toInt())
                                Text(
                                    cleanTitle(book.title), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                    color = cs.onSurface, modifier = Modifier.padding(top = 4.dp)
                                )
                                Text(
                                    "$pagesTxt≈ ${a.words} mots · ≈ $readTxt de lecture · ${a.chapters.size} partie(s)",
                                    fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            item {
                                PanelBox(Modifier.padding(top = 14.dp)) {
                                    Text("L'essentiel du livre", fontSize = 12.sp, color = cs.onSurfaceVariant)
                                    val sm = a.summary ?: emptyList()
                                    if (sm.isEmpty()) {
                                        Text(
                                            "Pas assez de texte rédigé pour résumer ce livre.",
                                            fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                    sm.forEach { p ->
                                        Text(
                                            "• $p", fontSize = 14.sp, lineHeight = 20.sp, color = cs.onSurface,
                                            modifier = Modifier.padding(top = 10.dp)
                                        )
                                    }
                                }
                            }
                            if (a.keywords.isNotEmpty()) {
                                item {
                                    Text("Mots-clés", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        a.keywords.forEach { k ->
                                            Text(
                                                k, fontSize = 12.sp, color = cs.onSurfaceVariant,
                                                modifier = Modifier.clip(RoundedCornerShape(50)).background(cs.surfaceVariant)
                                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            item {
                                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { copyText(act, "Résumé", resumeText(a)) }) { Text("Copier le résumé") }
                                    TextButton(onClick = { tab = "memo" }) { Text("Voir par chapitre") }
                                }
                            }
                        }
                    }
                } else if (tab == "memo") {
                    val a = analysis
                    when {
                        state == 0 -> Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            Text("Analyse du livre…", color = cs.onSurface)
                            val (i, n) = prog
                            if (n > 0) {
                                LinearProgressIndicator(
                                    progress = { i.toFloat() / n },
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                                )
                                Text(
                                    "$i sur $n pages", fontSize = 12.sp, color = cs.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                        a == null -> Text(
                            "Ce PDF ne contient pas de texte exploitable (scan ou images), la fiche n'est pas possible.",
                            color = cs.onSurfaceVariant, modifier = Modifier.padding(24.dp)
                        )
                        else -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)
                        ) {
                            if (a.keywords.isNotEmpty()) {
                                item {
                                    Text("Mots-clés", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        a.keywords.forEach { k ->
                                            Text(
                                                k, fontSize = 12.sp, color = cs.onSurfaceVariant,
                                                modifier = Modifier.clip(RoundedCornerShape(50)).background(cs.surfaceVariant)
                                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            items(a.chapters.size) { idx ->
                                val ch = a.chapters[idx]
                                PanelBox(Modifier.padding(top = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            ch.title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                            color = cs.onSurface, modifier = Modifier.weight(1f)
                                        )
                                        Text("p. ${ch.page + 1}", fontSize = 12.sp, color = cs.onSurfaceVariant)
                                    }
                                    if (ch.points.isEmpty()) {
                                        Text(
                                            "Pas assez de texte pour résumer cette partie.",
                                            fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                    ch.points.forEach { p ->
                                        Text(
                                            "• $p", fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurface,
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                    TextButton(onClick = { onGo(ch.page) }, modifier = Modifier.padding(top = 4.dp)) {
                                        Text("Aller à la page")
                                    }
                                }
                            }
                            item {
                                Button(
                                    onClick = { copyText(act, "Fiche", memoText(a)) },
                                    modifier = Modifier.padding(top = 16.dp)
                                ) { Text("Copier la fiche") }
                            }
                        }
                    }
                } else {
                    val blocks = code
                    when {
                        codeLoading -> Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            Text("Recherche du code…", color = cs.onSurface)
                            val (i, n) = prog
                            if (n > 0) {
                                LinearProgressIndicator(
                                    progress = { i.toFloat() / n },
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                                )
                                Text("$i sur $n pages", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                        codeFailed -> Text(
                            "Impossible de lire ce PDF pour y chercher du code.",
                            color = cs.onSurfaceVariant, modifier = Modifier.padding(24.dp)
                        )
                        blocks == null -> {}
                        blocks.isEmpty() -> Text(
                            "Aucun bloc de code détecté. La détection repère les polices à chasse fixe ; un code inséré comme image n'est pas reconnu.",
                            color = cs.onSurfaceVariant, modifier = Modifier.padding(24.dp)
                        )
                        else -> LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)
                        ) {
                            items(blocks.size) { idx ->
                                val bl = blocks[idx]
                                PanelBox(Modifier.padding(top = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Page ${bl.page + 1}", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { copyText(act, "Code", bl.text) }) { Text("Copier") }
                                        TextButton(onClick = { onGo(bl.page) }) { Text("Voir la page") }
                                    }
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant)
                                            .horizontalScroll(rememberScrollState()).padding(12.dp)
                                    ) {
                                        Text(
                                            bl.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                            lineHeight = 17.sp, color = cs.onSurface, softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
