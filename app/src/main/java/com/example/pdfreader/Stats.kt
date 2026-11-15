package com.example.pdfreader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

/** Durée lisible : « 45 s », « 12 min », « 2 h 05 ». */
fun fmtDur(sec: Int): String = when {
    sec < 60 -> "$sec s"
    sec < 3600 -> "${sec / 60} min"
    else -> "${sec / 3600} h ${"%02d".format((sec % 3600) / 60)}"
}

private class Bucket(val label: String, val from: Long, val to: Long)

private fun midnight(): Calendar = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

private fun buckets(period: String): List<Bucket> {
    val out = mutableListOf<Bucket>()
    when (period) {
        "month" -> {
            val labels = listOf("S-3", "S-2", "S-1", "Cette sem.")
            for (i in 3 downTo 0) {
                val end = midnight()
                end.add(Calendar.DAY_OF_YEAR, 1 - 7 * i)
                val start = end.clone() as Calendar
                start.add(Calendar.DAY_OF_YEAR, -7)
                out.add(Bucket(labels[3 - i], start.timeInMillis, end.timeInMillis))
            }
        }
        "year" -> {
            val letters = "JFMAMJJASOND"
            for (i in 11 downTo 0) {
                val c = midnight()
                c.set(Calendar.DAY_OF_MONTH, 1)
                c.add(Calendar.MONTH, -i)
                val from = c.timeInMillis
                val label = letters[c.get(Calendar.MONTH)].toString()
                c.add(Calendar.MONTH, 1)
                out.add(Bucket(label, from, c.timeInMillis))
            }
        }
        else -> {
            val days = listOf("Dim", "Lun", "Mar", "Mer", "Jeu", "Ven", "Sam")
            for (i in 6 downTo 0) {
                val c = midnight()
                c.add(Calendar.DAY_OF_YEAR, -i)
                val from = c.timeInMillis
                val label = days[c.get(Calendar.DAY_OF_WEEK) - 1]
                c.add(Calendar.DAY_OF_YEAR, 1)
                out.add(Bucket(label, from, c.timeInMillis))
            }
        }
    }
    return out
}

@Composable
private fun Tile(value: String, label: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.panelBg(RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = cs.onSurface, maxLines = 1)
        Text(label, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun StatsTab(act: MainActivity) {
    val prefs = remember { act.getSharedPreferences("p", 0) }
    val cs = MaterialTheme.colorScheme
    val books = remember { Lib.load(act) }
    val sessions = remember { Store.sessionList() }
    var period by remember { mutableStateOf("week") }
    var goalMin by remember { mutableStateOf(prefs.getInt("goal_min", 120)) }

    val bs = remember(period) { buckets(period) }
    val values = remember(period) {
        bs.map { b -> sessions.filter { it.start >= b.from && it.start < b.to }.sumOf { it.seconds } }
    }
    val inRange = remember(period) { sessions.filter { it.start >= bs.first().from && it.start < bs.last().to } }
    val total = values.sum()
    val pages = inRange.sumOf { (it.toPage - it.fromPage).coerceAtLeast(0) }
    val maxV = maxOf(values.maxOrNull() ?: 0, 1)

    val weekAgo = remember { System.currentTimeMillis() - 7L * 24 * 3600 * 1000 }
    val weekSec = remember { sessions.filter { it.start >= weekAgo }.sumOf { it.seconds } }
    val pct = ((weekSec / 60f) / goalMin.coerceAtLeast(1) * 100f).toInt().coerceIn(0, 100)
    val cheer = when {
        pct >= 100 -> "Objectif atteint, bravo !"
        pct >= 50 -> "Tu es sur la bonne voie."
        weekSec == 0 -> "Lance une séance pour démarrer la semaine."
        else -> "Chaque page compte."
    }

    val cats = remember {
        books.flatMap { b -> Smart.tagsFor(act, b).map { it to b } }
            .groupBy({ it.first }, { it.second })
            .entries.sortedByDescending { it.value.size }.take(5)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp)) {
        item {
            Text("Statistiques", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Semaine", period == "week") { period = "week" }
                Pill("Mois", period == "month") { period = "month" }
                Pill("Année", period == "year") { period = "year" }
            }
        }
        item {
            PanelBox(Modifier.padding(top = 16.dp)) {
                Text("Temps de lecture", fontSize = 12.sp, color = cs.onSurfaceVariant)
                Text(
                    if (total == 0) "Aucune lecture" else fmtDur(total),
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onSurface,
                    modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
                )
                Row(
                    Modifier.fillMaxWidth().height(150.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    values.forEachIndexed { i, v ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            val h = if (v == 0) 2f else maxOf(4f, 110f * v / maxV)
                            Box(
                                Modifier.fillMaxWidth().height(h.dp).clip(RoundedCornerShape(6.dp))
                                    .background(if (i == values.lastIndex) cs.secondary else cs.primary.copy(alpha = 0.85f))
                            )
                            Text(
                                bs[i].label, fontSize = 11.sp, color = cs.onSurfaceVariant,
                                maxLines = 1, modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(fmtDur(total), "Lecture", Modifier.weight(1f))
                Tile("$pages", "Pages parcourues", Modifier.weight(1f))
                Tile("${inRange.size}", "Séances", Modifier.weight(1f))
            }
        }
        item {
            PanelBox(Modifier.padding(top = 12.dp)) {
                Text("Objectif de la semaine", fontSize = 12.sp, color = cs.onSurfaceVariant)
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                        val track = cs.surfaceVariant
                        val fg = cs.secondary
                        Canvas(Modifier.fillMaxSize()) {
                            val sw = 10.dp.toPx()
                            val arc = Size(this.size.width - sw, this.size.height - sw)
                            val tl = Offset(sw / 2, sw / 2)
                            drawArc(
                                color = track, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                                topLeft = tl, size = arc, style = Stroke(width = sw, cap = StrokeCap.Round)
                            )
                            drawArc(
                                color = fg, startAngle = -90f, sweepAngle = 360f * pct / 100f, useCenter = false,
                                topLeft = tl, size = arc, style = Stroke(width = sw, cap = StrokeCap.Round)
                            )
                        }
                        Text("$pct %", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                    }
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(
                            "${fmtDur(weekSec)} sur ${fmtDur(goalMin * 60)}",
                            fontSize = 15.sp, fontWeight = FontWeight.Medium, color = cs.onSurface
                        )
                        Text(cheer, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60 to "1 h", 120 to "2 h", 180 to "3 h", 300 to "5 h").forEach { (m, label) ->
                        Pill(label, goalMin == m) {
                            goalMin = m
                            prefs.edit().putInt("goal_min", m).apply()
                        }
                    }
                }
            }
        }
        if (cats.isNotEmpty()) {
            item { SectionLabel("Progression par catégorie") }
            items(cats.size) { i ->
                val e = cats[i]
                val avg = e.value.map { bookProgress(it) }.average().toFloat()
                Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.key, fontSize = 14.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                        Text(
                            "${e.value.size} livre(s) · ${(avg * 100).toInt()} %",
                            fontSize = 12.sp, color = cs.onSurfaceVariant
                        )
                    }
                    Box(
                        Modifier.fillMaxWidth().padding(top = 6.dp).height(5.dp)
                            .clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)
                    ) {
                        Box(Modifier.fillMaxWidth(avg.coerceIn(0f, 1f)).fillMaxHeight().background(cs.primary))
                    }
                }
            }
        }
    }
}
