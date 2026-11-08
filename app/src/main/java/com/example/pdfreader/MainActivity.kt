package com.example.pdfreader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    var keyHandler: ((Int) -> Boolean)? = null
    private var voiceInstance: VoiceGuide? = null
    val voice: VoiceGuide get() = voiceInstance ?: VoiceGuide(this).also { voiceInstance = it }

    override fun onDestroy() {
        voiceInstance?.shutdown()
        super.onDestroy()
    }

    override fun onStop() {
        // les données sont écrites avant que l'app passe en arrière-plan
        Store.flushSync()
        super.onStop()
    }
    var incoming by mutableStateOf<Uri?>(null)

    @Suppress("DEPRECATION")
    private fun handle(i: Intent?) {
        val u: Uri? = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (u != null) incoming = u
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Charge la base (et reprend automatiquement les anciennes données au premier lancement)
        Store.init(this)
        // L'écran n'est maintenu allumé que dans le lecteur (voir Reader).
        // Après une rotation, l'intention d'origine ne doit pas être rejouée.
        if (savedInstanceState == null) handle(intent)
        setContent { App(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyHandler?.invoke(keyCode) == true) true else super.onKeyDown(keyCode, event)
}

class PdfDoc(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close(); throw e
    }
    private val lock = Mutex()
    val pageCount = renderer.pageCount

    suspend fun render(i: Int, width: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.Default) {
            val p = renderer.openPage(i)
            try {
                val h = (width.toFloat() * p.height / p.width).toInt()
                val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            } finally {
                p.close()
            }
        }
    }

    fun close() {
        try {
            renderer.close(); pfd.close()
        } catch (_: Exception) {
        }
    }
}

class Theme(val name: String, val filter: ColorFilter?, val bg: Color)

val themes = listOf(
    Theme("Jour", null, Color(0xFFEEEEEE)),
    Theme(
        "Nuit",
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -.86f, 0f, 0f, 0f, 219f,
                    0f, -.86f, 0f, 0f, 219f,
                    0f, 0f, -.86f, 0f, 219f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        Color.Black
    ),
    Theme(
        "Sépia",
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    .35f, .69f, .17f, 0f, 0f,
                    .31f, .62f, .15f, 0f, 0f,
                    .24f, .48f, .12f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        Color(0xFF5B4636)
    )
)

@Composable
fun Page(d: PdfDoc, i: Int, w: Int, f: ColorFilter?) {
    var bmp by remember(d, i) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(d, i) {
        bmp = try {
            d.render(i, w)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    val b = bmp
    if (b != null) {
        Image(
            b.asImageBitmap(), null, Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth, colorFilter = f
        )
    } else {
        Spacer(Modifier.fillMaxWidth().height(500.dp))
    }
}

@Composable
fun App(act: MainActivity) {
    val prefs = remember { act.getSharedPreferences("p", 0) }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark", true)) }
    // Ces états survivent à la rotation de l'écran
    var screen by rememberSaveable { mutableStateOf("lib") }
    var currentId by rememberSaveable { mutableStateOf<String?>(null) }
    var autoTour by rememberSaveable { mutableStateOf(false) }
    val current = remember(currentId) {
        currentId?.let { id -> Lib.load(act).firstOrNull { it.id == id } }
    }

    // Nettoie les fichiers temporaires restés après une erreur
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Lib.cleanup(act) } }

    // PDF reçu d'une autre appli (Ouvrir avec / Partager)
    LaunchedEffect(act.incoming) {
        val u = act.incoming
        if (u != null) {
            act.incoming = null
            val b = withContext(Dispatchers.IO) { Lib.addPdf(act, u) }
            if (b != null) {
                val l = Lib.load(act)
                // si le PDF est déjà dans la bibliothèque, on rouvre l'existant (pas de doublon)
                val target = l.firstOrNull { it.id == b.id } ?: b.also { l.add(it) }
                target.opened = System.currentTimeMillis()
                Lib.save(act, l)
                currentId = target.id
                screen = "reader"
            }
        }
    }
    AppTheme(dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Crossfade(targetState = screen, animationSpec = tween(300), label = "screen") { s ->
            when (s) {
                "reader" -> {
                    val b = current
                    if (b != null) Reader(act, b) { screen = "lib" }
                    else LaunchedEffect(Unit) { screen = "lib" }
                }
                "marks" -> Marks(act, onBack = { screen = "lib" }, onOpen = { b, p ->
                    b.page = p
                    b.offset = 0
                    Store.saveSoon()
                    currentId = b.id; screen = "reader"
                })
                "opds" -> OpdsScreen(act) { screen = "lib" }
                "guide" -> GuideScreen(act, autoTour) { screen = "lib" }
                else -> Library(
                    act, dark,
                    onTheme = { dark = !dark; prefs.edit().putBoolean("dark", dark).apply() },
                    onOpen = { b -> currentId = b.id; screen = "reader" },
                    onMarks = { screen = "marks" },
                    onOpds = { screen = "opds" },
                    onGuide = { a -> autoTour = a; screen = "guide" }
                )
            }
            }
        }
    }
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

    // Séance de lecture : enregistrée à la sortie (base des futures statistiques)
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
    var tick by remember { mutableStateOf(0) }
    var auto by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(2f) }
    var themeIdx by remember { mutableStateOf(prefs.getInt("theme", 1)) }
    var showBm by remember { mutableStateOf(false) }
    var noteFor by remember { mutableStateOf<Int?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    val bookmarks = remember {
        mutableStateListOf<Int>().apply { addAll(book.marks.map { it.page }.sorted()) }
    }

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

    // Défilement automatique (mains libres)
    LaunchedEffect(auto, doc) {
        while (auto && doc != null) {
            try {
                listState.scrollBy(speed)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            }
            if (!listState.canScrollForward) auto = false
            delay(16)
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

    // Mémorise le nombre de pages (progression affichée sur les couvertures)
    LaunchedEffect(doc) {
        doc?.let {
            book.pages = it.pageCount
            Store.saveSoon()
        }
    }

    // Les commandes se masquent seules après quelques secondes
    LaunchedEffect(menu, tick, showBm, noteFor) {
        if (menu && !showBm && noteFor == null && !searchOpen) {
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

            // poignée de défilement rapide
            FastScroller(listState, total, Modifier.align(Alignment.CenterEnd))

            // fine barre de progression de lecture
            Box(
                Modifier.align(Alignment.TopStart).fillMaxWidth().height(3.dp)
                    .background(Color.Black.copy(alpha = 0.25f))
            ) {
                Box(Modifier.fillMaxWidth(cur.toFloat() / total).fillMaxHeight().background(cs.primary))
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
                Surface(
                    color = cs.surface.copy(alpha = 0.96f), shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(Ic.Back) { onBack() }
                        Text(
                            book.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        )
                        IconBtn(
                            Ic.Bookmark,
                            tint = if (listState.firstVisibleItemIndex in bookmarks) cs.primary else cs.onSurface
                        ) { tick++; addBookmark() }
                        IconBtn(Ic.List) { tick++; showBm = true }
                        IconBtn(Ic.Search) { tick++; menu = false; searchOpen = true }
                    }
                }
            }

            // dock du bas
            AnimatedVisibility(
                visible = menu, modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }
            ) {
                Surface(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp), color = cs.surface.copy(alpha = 0.96f),
                    shadowElevation = 12.dp
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$cur", fontSize = 12.sp, color = cs.onSurfaceVariant)
                            Slider(
                                value = cur.toFloat(),
                                onValueChange = { v ->
                                    tick++
                                    scope.launch { listState.scrollToItem((v.toInt() - 1).coerceIn(0, total - 1)) }
                                },
                                valueRange = 1f..maxOf(total, 2).toFloat(),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            Text("$total", fontSize = 12.sp, color = cs.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(48.dp).clip(CircleShape).background(cs.primary)
                                    .clickable { auto = !auto; tick++; if (auto) menu = false },
                                contentAlignment = Alignment.Center
                            ) { Ico(if (auto) Ic.Pause else Ic.Play, cs.onPrimary, 24.dp) }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text("Vitesse de défilement", fontSize = 11.sp, color = cs.onSurfaceVariant)
                                Slider(value = speed, onValueChange = { speed = it; tick++ }, valueRange = 0.5f..8f)
                            }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { nextTheme(); tick++ }.padding(6.dp)
                            ) {
                                Box(Modifier.size(28.dp).clip(CircleShape).background(th.bg).border(2.dp, cs.outline, CircleShape))
                                Text(th.name, fontSize = 10.sp, color = cs.onSurfaceVariant)
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
