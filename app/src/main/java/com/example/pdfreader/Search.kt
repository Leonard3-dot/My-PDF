package com.example.pdfreader

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class Hit(val page: Int, val before: String, val match: String, val after: String)

object DocText {
    private fun cache(c: Context, b: Book): File =
        File(File(c.filesDir, "texts").apply { mkdirs() }, b.id + ".txt")

    fun clear(c: Context, b: Book) {
        cache(c, b).delete()
    }

    /** Texte de chaque page. Extrait une seule fois, puis relu depuis le cache. Null si impossible. */
    suspend fun pages(c: Context, b: Book, onProgress: (Int, Int) -> Unit): List<String>? = withContext(Dispatchers.IO) {
        val cf = cache(c, b)
        try {
            if (cf.exists() && cf.length() > 0) return@withContext cf.readText().split('\u000c')
            PDFBoxResourceLoader.init(c)
            val out = ArrayList<String>()
            PDDocument.load(Lib.file(c, b)).use { d ->
                val n = d.numberOfPages
                val st = PDFTextStripper()
                for (i in 1..n) {
                    ensureActive()
                    st.startPage = i
                    st.endPage = i
                    out.add(st.getText(d).replace('\u000c', ' '))
                    onProgress(i, n)
                }
            }
            cf.writeText(out.joinToString("\u000c"))
            out
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }
}

private val Spaces = Regex("\\s+")

fun findHits(pages: List<String>, q: String, limit: Int = 300): List<Hit> {
    val hits = ArrayList<Hit>()
    for ((i, t) in pages.withIndex()) {
        var from = 0
        var perPage = 0
        while (perPage < 3) {
            val k = t.indexOf(q, from, ignoreCase = true)
            if (k < 0) break
            val s = (k - 36).coerceAtLeast(0)
            val e = (k + q.length + 48).coerceAtMost(t.length)
            hits.add(
                Hit(
                    i,
                    t.substring(s, k).replace(Spaces, " "),
                    t.substring(k, k + q.length),
                    t.substring(k + q.length, e).replace(Spaces, " ")
                )
            )
            perPage++
            from = k + q.length
            if (hits.size >= limit) return hits
        }
    }
    return hits
}

/** Panneau de recherche dans le livre : résultats avec extraits, un appui mène à la page. */
@Composable
fun SearchPanel(act: MainActivity, book: Book, onGo: (Int) -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf<List<String>?>(null) }
    var prog by remember { mutableStateOf(0 to 0) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(book.id) {
        loading = true
        pages = DocText.pages(act, book) { i, n -> prog = i to n }
        loading = false
    }
    LaunchedEffect(Unit) {
        delay(200)
        try {
            focus.requestFocus()
        } catch (_: Exception) {
        }
    }

    val q = query.trim()
    val hits = remember(q, pages) {
        val p = pages
        if (p == null || q.length < 2) emptyList() else findHits(p, q)
    }
    val noText = pages?.all { it.isBlank() } == true

    Surface(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.62f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = cs.surface, shadowElevation = 16.dp
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 8.dp)
                    .width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(cs.outline)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Chercher dans ce livre") }, singleLine = true,
                    leadingIcon = { Ico(Ic.Search, cs.onSurfaceVariant, 20.dp) },
                    trailingIcon = { if (query.isNotEmpty()) IconBtn(Ic.Close, cs.onSurfaceVariant) { query = "" } },
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedContainerColor = cs.surfaceVariant,
                        unfocusedContainerColor = cs.surfaceVariant
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focus)
                )
                IconBtn(Ic.Down) { focusManager.clearFocus(force = true); onClose() }
            }

            val status = when {
                loading -> "Préparation du texte du livre… ${prog.first}/${prog.second}"
                pages == null -> "Impossible de lire le texte de ce PDF."
                noText -> "Ce PDF ne contient pas de texte (document scanné ?)."
                q.length < 2 -> "Tape au moins deux lettres."
                hits.isEmpty() -> "Aucun résultat pour « $q »."
                else -> "${hits.size} résultat(s) sur ${hits.map { it.page }.distinct().size} page(s)"
            }
            Text(status, color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 6.dp))
            if (loading && prog.second > 0) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(cs.surfaceVariant)) {
                    Box(
                        Modifier.fillMaxWidth(prog.first.toFloat() / prog.second).fillMaxHeight().background(cs.primary)
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(hits) { h ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable { focusManager.clearFocus(force = true); onGo(h.page) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            "p. ${h.page + 1}", color = cs.onPrimaryContainer, fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(cs.primaryContainer)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                        Text(
                            buildAnnotatedString {
                                append("…" + h.before)
                                withStyle(
                                    SpanStyle(
                                        background = cs.primaryContainer, color = cs.onPrimaryContainer,
                                        fontWeight = FontWeight.Bold
                                    )
                                ) { append(h.match) }
                                append(h.after + "…")
                            },
                            fontSize = 14.sp, maxLines = 2,
                            modifier = Modifier.weight(1f).padding(start = 12.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Poignée de défilement rapide : glisse le doigt sur le bord droit pour aller à n'importe quelle page. */
@Composable
fun FastScroller(state: LazyListState, total: Int, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) {
            visible = true
        } else {
            delay(1500)
            visible = false
        }
    }
    val alpha by animateFloatAsState(if (visible && total > 3) 1f else 0f, label = "fastscroll")

    BoxWithConstraints(modifier.fillMaxHeight().width(40.dp).padding(vertical = 56.dp)) {
        val trackH = maxHeight
        val thumbH = 52.dp
        val frac = if (total > 1) (state.firstVisibleItemIndex.toFloat() / (total - 1)).coerceIn(0f, 1f) else 0f
        if (alpha > 0.01f) {
            if (visible) {
                Box(
                    Modifier.fillMaxSize().pointerInput(total) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false }
                        ) { change, _ ->
                            val f = (change.position.y / size.height).coerceIn(0f, 1f)
                            scope.launch { state.scrollToItem((f * (total - 1)).toInt()) }
                        }
                    }
                )
            }
            Box(
                Modifier.align(Alignment.TopEnd).offset(y = (trackH - thumbH) * frac).padding(end = 4.dp)
                    .alpha(alpha).width(6.dp).height(thumbH)
                    .clip(RoundedCornerShape(3.dp)).background(cs.primary)
            )
            if (dragging) {
                Text(
                    "${state.firstVisibleItemIndex + 1}", color = cs.onPrimary, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd)
                        .offset(x = (-30).dp, y = (trackH - thumbH) * frac + 12.dp)
                        .clip(RoundedCornerShape(12.dp)).background(cs.primary)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}
