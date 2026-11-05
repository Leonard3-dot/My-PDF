package com.example.pdfreader

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

enum class Pose { NONE, POINT, PALM, FIST, PINCH }

fun poseLabel(p: Pose) = when (p) {
    Pose.POINT -> "☝ défilement"
    Pose.PALM -> "✋ pause"
    Pose.FIST -> "✊ lecture auto"
    Pose.PINCH -> "🤏 menu"
    Pose.NONE -> "…"
}

/**
 * Gestes de la main par la caméra frontale (jamais affichée : aucune prévisualisation).
 * - index pointé : le doigt « touche » la page, qui le suit puis continue sur son élan (inertie)
 * - poing fermé (maintenu) : lecture auto marche / arrêt
 * - pincement (maintenu) : afficher / masquer le menu
 * - paume ouverte maintenue : pause ; balayage latéral paume ouverte : page suivante / précédente
 */
class GestureEngine(private val act: ComponentActivity, private val gain: Float) {
    var onEvent: (String) -> Unit = {}
    @Volatile var dragging = false
    @Volatile var pose = Pose.NONE
    private var pendingPx = 0f
    private var prevY = 0f
    private var smY = 0f
    private var pointFrames = 0
    private var missFrames = 0

    /** Pixels de défilement accumulés depuis le dernier appel (le doigt « tire » la page). */
    fun consumeDrag(): Float = synchronized(this) { val v = pendingPx; pendingPx = 0f; v }
    @Volatile private var active = false

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var landmarker: HandLandmarker? = null
    private var provider: ProcessCameraProvider? = null
    private var ts = 0L
    private var cand = Pose.NONE
    private var stable = 0
    private var fired = Pose.NONE
    private var lastFire = 0L
    private var lastSwipe = 0L
    private val track = ArrayDeque<Pair<Long, Float>>()

    fun start() {
        active = true
        exec.execute {
            try {
                landmarker = HandLandmarker.createFromOptions(
                    act,
                    HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumHands(1)
                        .build()
                )
            } catch (e: Exception) {
                return@execute
            }
            main.post { if (active) bind() }
        }
    }

    private fun bind() {
        val f = ProcessCameraProvider.getInstance(act)
        f.addListener({
            val p = f.get()
            provider = p
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(exec) { proxy -> analyze(proxy) }
            p.unbindAll()
            p.bindToLifecycle(act, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(act))
    }

    fun stop() {
        active = false
        dragging = false
        synchronized(this) { pendingPx = 0f }
        pose = Pose.NONE
        provider?.unbindAll()
        exec.execute {
            landmarker?.close()
            landmarker = null
        }
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val lm = landmarker ?: return
            val raw = proxy.toBitmap()
            val m = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            ts = maxOf(ts + 1, System.currentTimeMillis())
            val res = lm.detectForVideo(BitmapImageBuilder(bmp).build(), ts)
            process(res.landmarks().firstOrNull())
        } catch (_: Exception) {
        } finally {
            proxy.close()
        }
    }

    private fun emit(e: String) {
        main.post { onEvent(e) }
    }

    private fun process(h: List<NormalizedLandmark>?) {
        val now = System.currentTimeMillis()
        if (h == null) {
            pose = Pose.NONE; cand = Pose.NONE
            stable = 0; fired = Pose.NONE; track.clear()
            pointFrames = 0
            if (++missFrames >= 3) dragging = false
            return
        }
        fun up(tip: Int, pip: Int) = h[tip].y() < h[pip].y()
        val idx = up(8, 6)
        val n = listOf(idx, up(12, 10), up(16, 14), up(20, 18)).count { it }
        val palmSize = hypot(h[0].x() - h[9].x(), h[0].y() - h[9].y())
        val pinch = hypot(h[4].x() - h[8].x(), h[4].y() - h[8].y()) < 0.28f * palmSize
        val p = when {
            pinch -> Pose.PINCH
            n == 4 -> Pose.PALM
            n == 0 -> Pose.FIST
            idx && n == 1 -> Pose.POINT
            else -> Pose.NONE
        }
        pose = p

        // Gestes « posés » : il faut tenir la pose quelques instants
        if (p == cand) stable++ else { cand = p; stable = 1; fired = Pose.NONE }
        val need = if (p == Pose.PALM) 12 else 8
        val discrete = p == Pose.FIST || p == Pose.PINCH || p == Pose.PALM
        if (discrete && fired != p && stable >= need && now - lastFire > 1000) {
            when (p) {
                Pose.FIST -> emit("toggleAuto")
                Pose.PINCH -> emit("menu")
                Pose.PALM -> emit("pause")
                else -> Unit
            }
            fired = p; lastFire = now
        }

        // Balayage latéral, paume ouverte (image non miroir : main vers ta gauche = dx > 0)
        if (p == Pose.PALM) {
            val x = h[0].x()
            track.addLast(now to x)
            while (track.isNotEmpty() && now - track.first().first > 400) track.removeFirst()
            val dx = x - track.first().second
            if (abs(dx) > 0.3f && now - lastSwipe > 900) {
                lastSwipe = now; lastFire = now; fired = Pose.PALM; track.clear()
                emit(if (dx > 0) "next" else "prev")
            }
        } else track.clear()

        // Défilement réaliste : l'index pointé = le doigt posé sur la page.
        // La page suit le bout du doigt ; quand on relâche, elle continue sur son élan.
        if (p == Pose.POINT) {
            missFrames = 0
            val y = h[8].y()
            smY = if (pointFrames == 0) y else smY * 0.5f + y * 0.5f
            pointFrames++
            if (pointFrames >= 3) {
                if (dragging) {
                    val dy = smY - prevY
                    if (abs(dy) > 0.0015f) synchronized(this) { pendingPx -= dy * gain }
                }
                dragging = true
            }
            prevY = smY
        } else {
            pointFrames = 0
            if (++missFrames >= 3) dragging = false
        }
    }
}
