package com.example.pdfreader
import kotlinx.coroutines.flow.collectLatest
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
    var keyHandler: ((Int) -> Boolean)? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme { Reader(this) } }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (keyHandler?.invoke(keyCode) == true) true else super.onKeyDown(keyCode, event)
}

class PdfDoc(ctx: Context, uri: Uri) {
    private val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")!!
    private val renderer = PdfRenderer(pfd)
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
        renderer.close(); pfd.close()
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
    LaunchedEffect(d, i) { bmp = d.render(i, w) }
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
fun Reader(act: MainActivity) {
    val prefs = remember { act.getSharedPreferences("p", 0) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val widthPx = act.resources.displayMetrics.widthPixels
    val screenH = act.resources.displayMetrics.heightPixels

    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var key by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(true) }
    var auto by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(2f) }
    var themeIdx by remember { mutableStateOf(prefs.getInt("theme", 1)) }
    var showBm by remember { mutableStateOf(false) }
    val bookmarks = remember { mutableStateListOf<Int>() }

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

    fun handleGesture(e: String) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val h = screenH * 0.85f
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

    fun open(uri: Uri) {
        try {
            val d = PdfDoc(act, uri)
            doc?.close()
            doc = d
            key = uri.toString()
            prefs.edit().putString("last", key).apply()
            bookmarks.clear()
            bookmarks.addAll(
                (prefs.getStringSet("bm_$key", emptySet()) ?: emptySet()).map { it.toInt() }.sorted()
            )
            val pos = (prefs.getString("pos_$key", "0,0") ?: "0,0").split(",")
            scope.launch { listState.scrollToItem(pos[0].toInt(), pos[1].toInt()) }
        } catch (e: Exception) {
            doc = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                act.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            open(uri)
        }
    }

    // Reprise de lecture : ouvre le dernier PDF à la dernière position
    LaunchedEffect(Unit) { prefs.getString("last", null)?.let { open(Uri.parse(it)) } }

    // Sauvegarde de la position
    LaunchedEffect(key) {
        if (key.isNotEmpty()) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collectLatest { (i, o) ->
                    delay(500)
                    prefs.edit().putString("pos_$key", "$i,$o").apply()
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
            buf += engine.consumeDrag()
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
    val pick = { picker.launch(arrayOf("application/pdf")) }

    Box(Modifier.fillMaxSize().background(th.bg)) {
        val d = doc
        if (d == null) {
            Button(onClick = { pick() }, modifier = Modifier.align(Alignment.Center)) {
                Text("Ouvrir un PDF")
            }
        } else {
            LazyColumn(
                state = listState,
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
                items(d.pageCount) { i -> Page(d, i, widthPx, th.filter) }
            }
            Text(
                "${listState.firstVisibleItemIndex + 1}/${d.pageCount}",
                color = Color.Gray,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            )
            if (gestureOn) {
                Text(
                    poseLabel(gPose), color = Color.Cyan,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                )
            }
            if (menu) {
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = 6.dp) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { auto = !auto; if (auto) menu = false }) {
                                Text(if (auto) "⏸" else "▶")
                            }
                            Slider(
                                value = speed, onValueChange = { speed = it },
                                valueRange = 0.5f..8f,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = {
                                themeIdx = (themeIdx + 1) % themes.size
                                prefs.edit().putInt("theme", themeIdx).apply()
                            }) { Text(th.name) }
                            TextButton(onClick = {
                                val p = listState.firstVisibleItemIndex
                                if (p !in bookmarks) {
                                    bookmarks.add(p); bookmarks.sort()
                                    prefs.edit().putStringSet(
                                        "bm_$key", bookmarks.map { it.toString() }.toSet()
                                    ).apply()
                                }
                            }) { Text("+ Signet") }
                            TextButton(onClick = { showBm = true }) { Text("Signets") }
                            TextButton(onClick = { pick() }) { Text("Ouvrir") }
                        }
                        TextButton(onClick = {
                            if (gestureOn) {
                                gestureOn = false
                                prefs.edit().putBoolean("gest", false).apply()
                            } else if (ContextCompat.checkSelfPermission(act, Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED
                            ) {
                                gestureOn = true
                                prefs.edit().putBoolean("gest", true).apply()
                            } else camPerm.launch(Manifest.permission.CAMERA)
                        }) { Text(if (gestureOn) "✋ Gestes : oui" else "✋ Gestes : non") }
                    }
                }
            }
        }
    }

    if (showBm) {
        AlertDialog(
            onDismissRequest = { showBm = false },
            confirmButton = { TextButton(onClick = { showBm = false }) { Text("Fermer") } },
            title = { Text("Signets") },
            text = {
                Column {
                    if (bookmarks.isEmpty()) Text("Aucun signet")
                    bookmarks.forEach { p ->
                        TextButton(onClick = {
                            scope.launch { listState.scrollToItem(p) }
                            showBm = false
                        }) { Text("Page ${p + 1}") }
                    }
                }
            }
        )
    }
}
