package com.example.pdfreader

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException

/** Miniatures gardées en mémoire : plus de relecture du disque quand on fait défiler la bibliothèque. */
object CoverCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun peek(id: String): Bitmap? = cache.get(id)
    fun put(id: String, b: Bitmap) { cache.put(id, b) }
    fun remove(id: String) { cache.remove(id) }
    fun clear() { cache.evictAll() }
}

/** Pages déjà dessinées : revenir en arrière dans un livre est instantané. */
object PageCache {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(96L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun peek(key: String): Bitmap? = cache.get(key)
    fun put(key: String, b: Bitmap) { cache.put(key, b) }
}

/** Cartes déjà apparues : l'animation d'entrée ne se rejoue pas quand on remonte la liste. */
object SeenCards {
    val ids: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())
}

/**
 * Page du lecteur : image en cache si elle existe, sinon emplacement à la vraie proportion de la page
 * (le texte ne saute plus quand les pages se dessinent).
 */
@Composable
fun Page(d: PdfDoc, i: Int, w: Int, f: ColorFilter?) {
    val key = "${d.uid}:$i:$w"
    var bmp by remember(d, i, w) { mutableStateOf(PageCache.peek(key)) }
    LaunchedEffect(d, i, w) {
        if (bmp == null) {
            bmp = try {
                d.render(i, w).also { PageCache.put(key, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    val b = bmp
    if (b != null) {
        val img = remember(b) { b.asImageBitmap() }
        Image(img, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth, colorFilter = f)
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(1f / d.ratio(i)).background(Color(0x14FFFFFF)))
    }
}
