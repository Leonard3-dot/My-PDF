package com.example.pdfreader

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

/** Multiplicateurs de vitesse : 1.0x = défilement de base. */
private val ReaderSpeeds = listOf(0.5f, 1f, 1.5f, 2f, 3f)

/**
 * Secondes par page, d'après tes séances passées (ce livre d'abord, puis tous les livres).
 * Les séances où tu as sauté de page en page sans lire (trop rapides) sont ignorées.
 */
private fun secPerPage(book: Book): Float {
    val all = Store.sessionList().filter {
        it.seconds >= 20 && it.toPage > it.fromPage && (it.toPage - it.fromPage) <= it.seconds / 5
    }
    fun pace(l: List<ReadSession>): Float? {
        val sec = l.sumOf { it.seconds }
        val pg = l.sumOf { it.toPage - it.fromPage }
        return if (sec >= 60 && pg >= 2) sec.toFloat() / pg else null
    }
    val p = pace(all.filter { it.bookId == book.id }) ?: pace(all) ?: 90f
    return p.coerceIn(10f, 600f)
}

private fun durLabel(s: Int): String = when {
    s < 60 -> "moins d'1 min"
    s < 3600 -> "${s / 60} min"
    else -> "${s / 3600} h ${"%02d".format((s % 3600) / 60)}"
}

@Composable
fun Reader(act: MainActivity, book: Book, onBack: () -> Unit) {
    val prefs = remember { act.getSharedPreferences("p", 0) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val widthPx = act.resources.displayMetrics.widthPixels
    val screenH = act.resources.displayMetrics.heightPixels

    // L'écran reste allumé uniquement pendant la lecture
    DisposableEffect(Unit) {
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Séance de lecture : enregistrée à la sortie
    DisposableEffect(book.id) {
        val t0 = System.currentTimeMillis()
        val p0 = book.page
        onDispose {
            val sec = ((System.currentTimeMillis() - t0) / 1000).toInt().coerceAtMost(7200)
            if (sec >= 5) Store.addSession(ReadSession(book.id, t0, sec, p0, book.page))
        }
    }

    var reload by remember { mutableStateOf(0) }
    val opened = remember(book.id, reload) { runCatching { PdfDoc(Lib.file(act, book)) } }
    val doc = opened.getOrNull()
    DisposableEffect(opened) { onDispose { opened.getOrNull()?.close() } }

    var menu by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var auto by remember { mutableStateOf(false) }
    var speedIdx by remember { mutableStateOf(prefs.getInt("speed_idx", 1).coerceIn(0, ReaderSpeeds.lastIndex)) }
    val speedNow by rememberUpdatedState(2f * ReaderSpeeds[speedIdx])
    var themeIdx by remember { mutableStateOf(prefs.getInt("theme", 1)) }
    var showBm by remember { mutableStateOf(false) }
    var noteFor by remember { mutableStateOf<Int?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var smartOpen by remember { mutableStateOf(false) }
    val bookmarks = remember {
        mutableStateListOf<Int>().apply { addAll(book.marks.map { it.page }.sorted()) }
    }
    val pace = remember(book.id) { secPerPage(book) }

    val view = LocalView.current
    val engine = remember { GestureEngine(act, screenH.toFloat()) }
    var gestureOn by remember {
        mutableStateOf(
            prefs.getBoolean("gest", false) &&
                ContextCompat.checkSelfPermission(act, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var gPose by remember { mutableStateOf(Pose.NONE) }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        gestureOn = ok
        prefs.edit().putBoolean("gest", ok).apply()
    }

    BackHandler { onBack() }
    BackHandler(enabled = searchOpen) { searchOpen = false }

    fun handleGesture(e: String) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val h = screenH * 0.85f * (if (prefs.getBoolean("inv", false)) -1f else 1f)
        when (e) {
            "toggleAuto" -> { auto = !auto; if (auto) menu = false }
            "pause" -> auto = false
            "menu" -> menu = !menu
            "next" -> scope.launch { listState.animateScrollBy(h) }
            "prev" -> scope.launch { listState.animateScrollBy(-h) }
        }
    }
    SideEffect { engine.onEvent = { e -> handleGesture(e) } }
    DisposableEffect(gestureOn) {
        if (gestureOn) engine.start()
        onDispose { engine.stop() }
    }

    // Reprise de lecture
    LaunchedEffect(doc) {
        if (doc != null) {
            listState.scrollToItem(
                book.page.coerceIn(0, maxOf(doc.pageCount - 1, 0)),
                book.offset.coerceAtLeast(0)
            )
        }
    }
    // Sauvegarde de la position
    LaunchedEffect(doc) {
        if (doc != null) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collectLatest { (i, o) ->
                    delay(500)
                    book.page = i
                    book.offset = o
                    Store.saveSoon()
                }
        }
    }

    // Défilement automatique (mains libres), vitesse modifiable en direct
    LaunchedEffect(auto, doc) {
        var last = 0L
        while (auto && doc != null) {
            // synchronisé sur les images de l'écran : vitesse constante, sans saccades (60 ou 120 Hz)
            val now = withFrameNanos { it }
            if (last != 0L) {
                val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                try {
                    listState.scrollBy(speedNow * 60f * dt)
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
                if (!listState.canScrollForward) auto = false
            }
            last = now
        }
    }

    // Défilement par gestes : la page suit le doigt, puis continue sur son élan
    LaunchedEffect(gestureOn, doc) {
        var buf = 0f
        var vel = 0f
        while (gestureOn && doc != null) {
            buf += engine.consumeDrag() * prefs.getFloat("sens", 1f)
            val step = buf * 0.4f
            buf -= step
            if (engine.pose == Pose.PALM) { buf = 0f; vel = 0f }
            val dragging = engine.dragging
            if (dragging) {
                vel = vel * 0.7f + step * 0.3f
            } else {
                vel *= 0.95f
                if (abs(vel) < 0.3f) vel = 0f
            }
            val move = if (dragging) step else step + vel
            if (move != 0f) {
                try {
                    listState.scrollBy(move)
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                }
            }
            if (gPose != engine.pose) gPose = engine.pose
            delay(16)
        }
    }

    // Touches volume / pédale Bluetooth
    DisposableEffect(Unit) {
        act.keyHandler = { k ->
            val h = screenH * 0.85f
            when (k) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    scope.launch { listState.animateScrollBy(h) }; true
                }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    scope.launch { listState.animateScrollBy(-h) }; true
                }
                else -> false
            }
        }
        onDispose { act.keyHandler = null }
    }

    val th = themes[themeIdx]

    fun addBookmark() {
        val p = listState.firstVisibleItemIndex
        if (p !in bookmarks) {
            bookmarks.add(p); bookmarks.sort()
            book.marks.add(BookMark(p, ""))
            Store.saveSoon()
        }
        noteFor = p
    }
    fun nextTheme() {
        themeIdx = (themeIdx + 1) % themes.size
        prefs.edit().putInt("theme", themeIdx).apply()
    }
    fun nextSpeed() {
        speedIdx = (speedIdx + 1) % ReaderSpeeds.size
        prefs.edit().putInt("speed_idx", speedIdx).apply()
    }
    fun toggleGestures() {
        if (gestureOn) {
            gestureOn = false
            prefs.edit().putBoolean("gest", false).apply()
        } else if (ContextCompat.checkSelfPermission(act, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            gestureOn = true
            prefs.edit().putBoolean("gest", true).apply()
            if (prefs.getBoolean("vhints", false)) {
                act.voice.say("Gestes de la main activés. Pointe l'index vers la caméra, puis monte ou descends le doigt pour faire défiler.")
            }
        } else camPerm.launch(Manifest.permission.CAMERA)
    }

    // Mémorise le nombre de pages
    LaunchedEffect(doc) {
        doc?.let {
            book.pages = it.pageCount
            Store.saveSoon()
        }
    }

    // Les commandes se masquent seules après quelques secondes (sauf îlot déplié)
    LaunchedEffect(menu, tick, showBm, noteFor, expanded) {
        if (menu && !expanded && !showBm && noteFor == null && !searchOpen) {
            delay(5000)
            menu = false
        }
    }

    Box(Modifier.fillMaxSize().background(th.bg)) {
        if (doc == null) {
            val secure = opened.exceptionOrNull() is SecurityException
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (secure) {
                    var pwd by remember { mutableStateOf("") }
                    var wrong by remember { mutableStateOf(false) }
                    Text("Ce PDF est protégé par un mot de passe.")
                    TextField(
                        value = pwd, onValueChange = { pwd = it; wrong = false },
                        label = { Text("Mot de passe") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    if (wrong) Text("Mot de passe incorrect.", color = Color.Red)
                    Button(
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { Lib.unlock(act, book, pwd) }
                                if (ok) reload++ else wrong = true
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp)
                    ) { Text("Déverrouiller") }
                } else {
                    Text("Impossible de lire ce PDF (fichier abîmé ou format non pris en charge).")
                }
                Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Retour à la bibliothèque") }
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures { o ->
                        val h = size.height
                        when {
                            o.y < h * 0.3f -> scope.launch { listState.animateScrollBy(-h * 0.85f) }
                            o.y > h * 0.7f -> scope.launch { listState.animateScrollBy(h * 0.85f) }
                            else -> menu = !menu
                        }
                    }
                }
            ) {
                items(doc.pageCount) { i -> Page(doc, i, widthPx, th.filter) }
            }
            val total = maxOf(doc.pageCount, 1)
            val cur = (listState.firstVisibleItemIndex + 1).coerceIn(1, total)
            val cs = MaterialTheme.colorScheme
            val remainLabel = when {
                total <= 1 -> ""
                cur >= total -> "Dernière page"
                else -> "≈ ${durLabel(((total - cur) * pace).toInt())} restantes"
            }

            // poignée de défilement rapide
            FastScroller(listState, total, Modifier.align(Alignment.CenterEnd))

            // fine barre de progression de lecture
            Box(
                Modifier.align(Alignment.TopStart).fillMaxWidth().height(3.dp)
                    .background(Color.Black.copy(alpha = 0.25f))
            ) {
                Box(Modifier.fillMaxWidth(cur.toFloat() / total).fillMaxHeight().background(cs.secondary))
            }

            // pastille de page quand les commandes sont masquées
            if (!menu) {
                Text(
                    "$cur / $total", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                        .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            // geste reconnu
            if (gestureOn) {
                Text(
                    poseLabel(gPose), color = Color.Cyan, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            // barre du haut
            AnimatedVisibility(
                visible = menu, modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }
            ) {
                GlassSurface(
                    color = cs.surface.copy(alpha = 0.92f), shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(Ic.Back) { onBack() }
                        Text(
                            cleanTitle(book.title), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        )
                        IconBtn(
                            Ic.Bookmark,
                            tint = if (listState.firstVisibleItemIndex in bookmarks) cs.primary else cs.onSurface
                        ) { tick++; addBookmark() }
                        IconBtn(Ic.List) { tick++; showBm = true }
                        IconBtn(Ic.Search) { tick++; menu = false; searchOpen = true }
                        IconBtn(Ic.File) { tick++; smartOpen = true }
                    }
                }
            }

            // îlot flottant du bas : l'essentiel, déplié à la demande
            AnimatedVisibility(
                visible = menu, modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }
            ) {
                GlassSurface(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(30.dp),
                    color = cs.surface.copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, cs.outline.copy(alpha = 0.45f)),
                    shadowElevation = 10.dp
                ) {
                    Column(Modifier.animateContentSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (expanded) {
                            Slider(
                                value = cur.toFloat(),
                                onValueChange = { v ->
                                    tick++
                                    scope.launch { listState.scrollToItem((v.toInt() - 1).coerceIn(0, total - 1)) }
                                },
                                valueRange = 1f..maxOf(total, 2).toFloat(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { nextTheme(); tick++ }.padding(6.dp)
                                ) {
                                    Box(Modifier.size(26.dp).clip(CircleShape).background(th.bg).border(2.dp, cs.outline, CircleShape))
                                    Text(th.name, fontSize = 11.sp, color = cs.onSurfaceVariant)
                                }
                                IconBtn(Ic.Eye, tint = if (gestureOn) cs.primary else cs.onSurfaceVariant) {
                                    tick++; toggleGestures()
                                }
                                IconBtn(Ic.Speaker, tint = if (act.voice.speaking) cs.primary else cs.onSurfaceVariant) {
                                    tick++
                                    if (act.voice.speaking) act.voice.stop() else act.voice.say(ReaderTips)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(44.dp).clip(CircleShape).background(cs.primary)
                                    .clickable { auto = !auto; tick++; if (auto) menu = false },
                                contentAlignment = Alignment.Center
                            ) { Ico(if (auto) Ic.Pause else Ic.Play, cs.onPrimary, 22.dp) }
                            Text(
                                String.format(java.util.Locale.US, "%.1fx", ReaderSpeeds[speedIdx]),
                                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onPrimaryContainer,
                                modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(50))
                                    .background(cs.primaryContainer)
                                    .clickable { nextSpeed(); tick++ }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("Page $cur sur $total", fontSize = 13.sp, color = cs.onSurface, maxLines = 1)
                                if (remainLabel.isNotEmpty()) {
                                    Text(remainLabel, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1)
                                }
                            }
                            IconBtn(if (expanded) Ic.Down else Ic.Up, tint = cs.onSurfaceVariant) {
                                expanded = !expanded; tick++
                            }
                        }
                    }
                }
            }

            // recherche dans le livre
            AnimatedVisibility(
                visible = searchOpen, modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }
            ) {
                SearchPanel(
                    act, book,
                    onGo = { p -> scope.launch { listState.scrollToItem(p) }; searchOpen = false },
                    onClose = { searchOpen = false }
                )
            }
        }
    }

    if (smartOpen) {
        SmartSheet(
            act, book,
            onGo = { p -> scope.launch { listState.scrollToItem(p) }; smartOpen = false },
            onClose = { smartOpen = false }
        )
    }

    noteFor?.let { p ->
        var txt by remember(p) { mutableStateOf(book.marks.firstOrNull { it.page == p }?.note ?: "") }
        AlertDialog(
            onDismissRequest = { noteFor = null },
            confirmButton = {
                TextButton(onClick = {
                    val m = book.marks.firstOrNull { it.page == p }
                    if (m != null) m.note = txt else book.marks.add(BookMark(p, txt))
                    Store.saveSoon()
                    noteFor = null
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { noteFor = null }) { Text("Sans note") } },
            title = { Text("Note, page ${p + 1}") },
            text = {
                TextField(value = txt, onValueChange = { txt = it }, placeholder = { Text("Ta note (facultatif)") })
            }
        )
    }

    if (showBm) {
        AlertDialog(
            onDismissRequest = { showBm = false },
            confirmButton = { TextButton(onClick = { showBm = false }) { Text("Fermer") } },
            title = { Text("Signets") },
            text = {
                Column {
                    if (bookmarks.isEmpty()) Text("Aucun signet")
                    bookmarks.toList().forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { scope.launch { listState.scrollToItem(p) }; showBm = false },
                                modifier = Modifier.weight(1f)
                            ) {
                                val n = book.marks.firstOrNull { it.page == p }?.note ?: ""
                                Text(if (n.isBlank()) "Page ${p + 1}" else "Page ${p + 1} : $n", maxLines = 2)
                            }
                            TextButton(onClick = { showBm = false; noteFor = p }) { Text("✎") }
                            TextButton(onClick = {
                                bookmarks.remove(p)
                                book.marks.removeAll { it.page == p }
                                Store.saveSoon()
                            }) { Text("🗑") }
                        }
                    }
                }
            }
        )
    }
}
