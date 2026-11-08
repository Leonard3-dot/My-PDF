package com.example.pdfreader

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Signet de page avec sa note (note vide = signet sans note). */
class BookMark(var page: Int, var note: String)

/** Étagère de l'utilisateur (étape 2). */
class ShelfData(val id: String, var name: String, var order: Int, val bookIds: MutableList<String> = mutableListOf())

/** Collection personnalisée (étape 3). */
class CollData(val id: String, var name: String, var color: Long, val bookIds: MutableList<String> = mutableListOf())

/** Une séance de lecture : sert aux statistiques et aux objectifs. */
class ReadSession(val bookId: String, val start: Long, val seconds: Int, val fromPage: Int, val toPage: Int)

/** Annotation ou surlignage (étape 5). kind = "note" ou "highlight". */
class Annot(val id: String, val bookId: String, val page: Int, val kind: String, var text: String, var color: Long, val created: Long)

/** Tout le contenu de la base. */
class Snapshot {
    val books = mutableListOf<Book>()
    val shelves = mutableListOf<ShelfData>()
    val collections = mutableListOf<CollData>()
    val sessions = mutableListOf<ReadSession>()
    val annots = mutableListOf<Annot>()
}

/**
 * Base de données locale unique : le fichier data.json dans le stockage de l'app.
 * Au premier lancement, les anciennes données (SharedPreferences) sont reprises automatiquement ;
 * elles ne sont pas effacées, ce qui permet de revenir en arrière sans rien perdre.
 */
object Store {
    private const val FILE = "data.json"
    private const val VERSION = 1

    private var app: Context? = null
    private var loaded = false
    private var loadedFromFile = false
    private var bakDone = false
    private var snap = Snapshot()

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val fileLock = Any()
    private val flushTask = Runnable { flush() }

    // ---------- démarrage ----------

    @Synchronized
    fun init(c: Context) {
        if (loaded) return
        app = c.applicationContext
        val f = File(c.filesDir, FILE)
        val bak = File(c.filesDir, "$FILE.bak")
        var s: Snapshot? = if (f.exists()) tryParse(f) else null
        if (s != null) {
            loadedFromFile = true
        } else if (f.exists()) {
            // fichier abîmé : on garde une trace, puis on tente la copie de secours
            try { f.copyTo(File(c.filesDir, "$FILE.corrupt"), overwrite = true) } catch (_: Exception) {}
            s = if (bak.exists()) tryParse(bak) else null
        }
        val fresh = s == null
        if (s == null) s = migrate(c)
        snap = s
        loaded = true
        if (fresh) {
            val json = toJson(snap).toString()
            io.execute { write(c, json) }
        }
    }

    private fun tryParse(f: File): Snapshot? = try {
        parse(JSONObject(f.readText()))
    } catch (e: Exception) {
        null
    }

    /** Reprise automatique des anciennes données. */
    private fun migrate(c: Context): Snapshot {
        val s = Snapshot()
        val p = c.getSharedPreferences("p", 0)
        val hp = c.getSharedPreferences("hashes", 0)
        try {
            val a = JSONArray(c.getSharedPreferences("lib", 0).getString("books", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val b = Book(o.getString("id"), o.getString("title"), o.getLong("added"), o.getLong("opened"))
                if (s.books.any { it.id == b.id }) continue
                b.hash = hp.getString(b.id, "") ?: ""
                b.pages = p.getInt("pc_${b.id}", 0)
                val pos = (p.getString("pos_${b.id}", "0,0") ?: "0,0").split(",")
                b.page = pos.getOrNull(0)?.toIntOrNull() ?: 0
                b.offset = pos.getOrNull(1)?.toIntOrNull() ?: 0
                val set = p.getStringSet("bm_${b.id}", emptySet()) ?: emptySet()
                set.mapNotNull { it.toIntOrNull() }.sorted().forEach { pg ->
                    b.marks.add(BookMark(pg, p.getString("note_${b.id}_$pg", "") ?: ""))
                }
                s.books.add(b)
            }
        } catch (_: Exception) {
        }
        return s
    }

    // ---------- accès ----------

    @Synchronized
    fun bookList(): MutableList<Book> = snap.books.toMutableList()

    @Synchronized
    fun setBooks(l: List<Book>) {
        snap.books.clear()
        snap.books.addAll(l.distinctBy { it.id })
        commit()
    }

    @Synchronized
    fun addSession(s: ReadSession) {
        snap.sessions.add(s)
        saveSoon()
    }

    /** Retire les données liées à un livre supprimé. L'historique des séances est conservé pour les statistiques. */
    @Synchronized
    fun purge(bookId: String) {
        snap.annots.removeAll { it.bookId == bookId }
        snap.shelves.forEach { it.bookIds.remove(bookId) }
        snap.collections.forEach { it.bookIds.remove(bookId) }
        saveSoon()
    }

    // ---------- écriture ----------

    /** Écriture différée (position de lecture, signets...). */
    fun saveSoon() {
        handler.removeCallbacks(flushTask)
        handler.postDelayed(flushTask, 800)
    }

    /** Écriture immédiate en arrière-plan (ajout, suppression de livres...). */
    fun commit() {
        handler.removeCallbacks(flushTask)
        flush()
    }

    /** Écriture terminée avant de rendre la main (quand l'app passe en arrière-plan). */
    fun flushSync() {
        val c = app ?: return
        handler.removeCallbacks(flushTask)
        val json = synchronized(this) { toJson(snap).toString() }
        try {
            io.submit { write(c, json) }.get()
        } catch (_: Exception) {
        }
    }

    private fun flush() {
        val c = app ?: return
        val json = synchronized(this) { toJson(snap).toString() }
        io.execute { write(c, json) }
    }

    private fun write(c: Context, json: String) {
        synchronized(fileLock) {
            try {
                val f = File(c.filesDir, FILE)
                val tmp = File(c.filesDir, "$FILE.tmp")
                if (!bakDone && loadedFromFile && f.exists()) {
                    f.copyTo(File(c.filesDir, "$FILE.bak"), overwrite = true)
                    bakDone = true
                }
                tmp.writeText(json)
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
                loadedFromFile = true
            } catch (_: Exception) {
            }
        }
    }

    // ---------- JSON ----------

    private fun toJson(s: Snapshot): JSONObject {
        val o = JSONObject().put("app", "My PDF").put("version", VERSION)
        val books = JSONArray()
        s.books.forEach { b ->
            val marks = JSONArray()
            b.marks.forEach { m -> marks.put(JSONObject().put("p", m.page).put("n", m.note)) }
            books.put(
                JSONObject().put("id", b.id).put("title", b.title).put("added", b.added).put("opened", b.opened)
                    .put("fav", b.fav).put("hash", b.hash).put("pages", b.pages)
                    .put("page", b.page).put("offset", b.offset).put("marks", marks)
            )
        }
        o.put("books", books)
        val shelves = JSONArray()
        s.shelves.forEach { sh ->
            shelves.put(
                JSONObject().put("id", sh.id).put("name", sh.name).put("order", sh.order)
                    .put("bookIds", JSONArray(sh.bookIds))
            )
        }
        o.put("shelves", shelves)
        val cols = JSONArray()
        s.collections.forEach { cl ->
            cols.put(
                JSONObject().put("id", cl.id).put("name", cl.name).put("color", cl.color)
                    .put("bookIds", JSONArray(cl.bookIds))
            )
        }
        o.put("collections", cols)
        val ses = JSONArray()
        s.sessions.forEach { x ->
            ses.put(
                JSONObject().put("b", x.bookId).put("t", x.start).put("s", x.seconds)
                    .put("f", x.fromPage).put("e", x.toPage)
            )
        }
        o.put("sessions", ses)
        val an = JSONArray()
        s.annots.forEach { x ->
            an.put(
                JSONObject().put("id", x.id).put("b", x.bookId).put("p", x.page).put("k", x.kind)
                    .put("t", x.text).put("c", x.color).put("d", x.created)
            )
        }
        o.put("annots", an)
        return o
    }

    private fun ids(a: JSONArray?): MutableList<String> {
        val l = mutableListOf<String>()
        if (a != null) for (i in 0 until a.length()) l.add(a.optString(i, ""))
        return l.filter { it.isNotEmpty() }.toMutableList()
    }

    private fun parse(o: JSONObject): Snapshot {
        val s = Snapshot()
        val a = o.optJSONArray("books") ?: JSONArray()
        for (i in 0 until a.length()) {
            val j = a.optJSONObject(i) ?: continue
            val id = j.optString("id", "")
            if (id.isEmpty() || s.books.any { it.id == id }) continue
            val b = Book(id, j.optString("title", "PDF"), j.optLong("added", 0L), j.optLong("opened", 0L))
            b.fav = j.optBoolean("fav", false)
            b.hash = j.optString("hash", "")
            b.pages = j.optInt("pages", 0)
            b.page = j.optInt("page", 0)
            b.offset = j.optInt("offset", 0)
            val m = j.optJSONArray("marks")
            if (m != null) {
                for (k in 0 until m.length()) {
                    val mj = m.optJSONObject(k) ?: continue
                    b.marks.add(BookMark(mj.optInt("p", 0), mj.optString("n", "")))
                }
            }
            s.books.add(b)
        }
        val sh = o.optJSONArray("shelves") ?: JSONArray()
        for (i in 0 until sh.length()) {
            val j = sh.optJSONObject(i) ?: continue
            s.shelves.add(ShelfData(j.optString("id", ""), j.optString("name", ""), j.optInt("order", 0), ids(j.optJSONArray("bookIds"))))
        }
        val cl = o.optJSONArray("collections") ?: JSONArray()
        for (i in 0 until cl.length()) {
            val j = cl.optJSONObject(i) ?: continue
            s.collections.add(CollData(j.optString("id", ""), j.optString("name", ""), j.optLong("color", 0L), ids(j.optJSONArray("bookIds"))))
        }
        val se = o.optJSONArray("sessions") ?: JSONArray()
        for (i in 0 until se.length()) {
            val j = se.optJSONObject(i) ?: continue
            s.sessions.add(ReadSession(j.optString("b", ""), j.optLong("t", 0L), j.optInt("s", 0), j.optInt("f", 0), j.optInt("e", 0)))
        }
        val an = o.optJSONArray("annots") ?: JSONArray()
        for (i in 0 until an.length()) {
            val j = an.optJSONObject(i) ?: continue
            s.annots.add(Annot(j.optString("id", ""), j.optString("b", ""), j.optInt("p", 0), j.optString("k", "note"), j.optString("t", ""), j.optLong("c", 0L), j.optLong("d", 0L)))
        }
        return s
    }

    // ---------- sauvegarde et restauration ----------

    /** Écrit toutes les données dans le fichier choisi (sans les PDF). */
    fun exportTo(c: Context, uri: Uri): Boolean {
        init(c)
        bookList().forEach { Lib.hashOf(c, it) }
        return try {
            val json = synchronized(this) { toJson(snap).toString(2) }
            c.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) } != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reprend favoris, signets, notes, positions et séances d'une sauvegarde.
     * Les livres sont retrouvés par l'empreinte de leur fichier : il faut donc avoir réimporté les PDF avant.
     */
    fun restoreFrom(c: Context, uri: Uri): String {
        init(c)
        val text = try {
            c.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        } ?: return "Fichier illisible"
        val src = try {
            parse(JSONObject(text))
        } catch (e: Exception) {
            return "Ce n'est pas une sauvegarde My PDF"
        }
        if (src.books.isEmpty()) return "Cette sauvegarde ne contient aucun livre"

        val byHash = HashMap<String, Book>()
        for (b in bookList()) {
            val h = Lib.hashOf(c, b)
            if (h != null) byHash[h] = b
        }
        val idMap = HashMap<String, String>()
        var matched = 0
        var missing = 0
        synchronized(this) {
            for (sb in src.books) {
                val lb = if (sb.hash.isNotBlank()) byHash[sb.hash] else null
                if (lb == null) {
                    missing++
                    continue
                }
                matched++
                idMap[sb.id] = lb.id
                if (sb.fav) lb.fav = true
                for (m in sb.marks) {
                    val ex = lb.marks.firstOrNull { it.page == m.page }
                    if (ex == null) lb.marks.add(BookMark(m.page, m.note))
                    else if (ex.note.isBlank() && m.note.isNotBlank()) ex.note = m.note
                }
                if (sb.opened > lb.opened) {
                    lb.opened = sb.opened
                    lb.page = sb.page
                    lb.offset = sb.offset
                }
            }
            for (sh in src.shelves) {
                val mapped = sh.bookIds.mapNotNull { idMap[it] }.toMutableList()
                val ex = snap.shelves.firstOrNull { it.id == sh.id }
                if (ex == null) snap.shelves.add(ShelfData(sh.id, sh.name, sh.order, mapped))
                else mapped.forEach { if (it !in ex.bookIds) ex.bookIds.add(it) }
            }
            for (cl in src.collections) {
                val mapped = cl.bookIds.mapNotNull { idMap[it] }.toMutableList()
                val ex = snap.collections.firstOrNull { it.id == cl.id }
                if (ex == null) snap.collections.add(CollData(cl.id, cl.name, cl.color, mapped))
                else mapped.forEach { if (it !in ex.bookIds) ex.bookIds.add(it) }
            }
            for (x in src.sessions) {
                val bid = idMap[x.bookId] ?: x.bookId
                if (snap.sessions.none { it.start == x.start && it.seconds == x.seconds }) {
                    snap.sessions.add(ReadSession(bid, x.start, x.seconds, x.fromPage, x.toPage))
                }
            }
            for (x in src.annots) {
                val bid = idMap[x.bookId] ?: continue
                if (snap.annots.none { it.id == x.id }) {
                    snap.annots.add(Annot(x.id, bid, x.page, x.kind, x.text, x.color, x.created))
                }
            }
        }
        commit()
        return buildString {
            append("Restauré : $matched livre(s) retrouvé(s)")
            if (missing > 0) append(", $missing introuvable(s). Réimporte leurs PDF puis restaure à nouveau.")
        }
    }

    /** Séances de lecture enregistrées (pour les statistiques). */
    @Synchronized
    fun sessionList(): List<ReadSession> = snap.sessions.toList()
}
