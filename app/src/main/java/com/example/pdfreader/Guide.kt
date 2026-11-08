package com.example.pdfreader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

class GuideSection(val title: String, val body: String)

const val ReaderTips = "Touche le haut ou le bas de l'écran pour tourner la page, et le centre pour afficher les commandes. " +
    "Utilise la loupe pour chercher un mot, la poignée à droite pour aller vite, et l'œil pour activer les gestes de la main."

val GuideSections = listOf(
    GuideSection(
        "Bienvenue",
        "Bienvenue dans My PDF, ton lecteur de PDF avec bibliothèque, étagère et défilement par gestes de la main. " +
            "Ce guide présente chaque fonction. Tu peux l'écouter section par section, ou lancer la visite guidée complète."
    ),
    GuideSection(
        "Importer tes livres",
        "Dans la bibliothèque, ouvre le menu à trois points et choisis Importer des livres. Tu peux en sélectionner plusieurs. " +
            "Les fichiers sont copiés dans l'application : ils restent disponibles même si tu supprimes l'original. " +
            "Tu peux aussi ouvrir un PDF depuis une autre application, avec Ouvrir avec ou Partager."
    ),
    GuideSection(
        "Bibliothèque et étagère",
        "Les puces Tous, Récents et Étagère changent l'affichage. Le mode Étagère range tes livres comme dans une vraie bibliothèque : " +
            "les derniers livres lus de face, les autres par le dos. Dans les autres modes, un appui long sélectionne des livres pour les retirer."
    ),
    GuideSection(
        "Lire un PDF",
        "Touche le haut ou le bas de l'écran pour avancer ou reculer d'un écran. Touche le centre pour afficher les commandes : " +
            "curseur de page et thèmes jour, nuit ou sépia. À droite, la poignée de défilement rapide te laisse glisser jusqu'à n'importe quelle page."
    ),
    GuideSection(
        "Recherche dans le document",
        "Touche la loupe en haut du lecteur et tape un mot. My PDF liste toutes les pages qui le contiennent, avec un extrait. " +
            "Touche un résultat pour y aller. La première recherche prépare le texte du livre, ensuite elle est instantanée. " +
            "Un PDF scanné, qui ne contient que des images, n'a pas de texte à chercher."
    ),
    GuideSection(
        "Défilement automatique",
        "Touche le bouton lecture pour que la page défile toute seule, et règle la vitesse avec le curseur. " +
            "Pour arrêter, touche le centre de l'écran afin d'afficher les commandes, puis appuie sur pause."
    ),
    GuideSection(
        "Gestes de la main",
        "Active l'icône œil dans le lecteur et autorise la caméra. La caméra reste invisible. " +
            "Pointe l'index vers la caméra de face, puis monte ou descends le doigt : la page te suit, puis continue sur son élan. " +
            "Ferme le poing pour lancer ou arrêter le défilement automatique. Pince le pouce et l'index pour afficher les commandes. " +
            "Ouvre la paume pour tout arrêter, ou balaie de côté avec la paume pour changer de page."
    ),
    GuideSection(
        "Signets et notes",
        "Le signet de la barre du haut mémorise la page, et tu peux y joindre une note. " +
            "Retrouve tous tes signets dans le menu, rubrique Marque-pages et notes."
    ),
    GuideSection(
        "Catalogues OPDS",
        "Dans le menu, ouvre Catalogues OPDS, ajoute l'adresse de ton serveur de livres, puis parcours les dossiers. " +
            "Touche un PDF pour le télécharger dans ta bibliothèque."
    ),
    GuideSection(
        "PDF protégés",
        "Si un PDF est protégé par un mot de passe, My PDF te le demande, puis garde une copie déverrouillée dans l'application."
    ),
    GuideSection(
        "Options",
        "Dans le menu, Options règle la sensibilité des gestes et peut inverser le balayage de côté. " +
            "Le thème clair ou sombre se change aussi depuis le menu."
    )
)

/** Voix du guide : synthèse vocale française d'Android. */
class VoiceGuide(ctx: Context) {
    /** 0 = chargement, 1 = prête, 2 = voix française indisponible */
    var state by mutableStateOf(0)
        private set
    var speaking by mutableStateOf(false)
        private set
    var current by mutableStateOf(-1)
        private set

    private var lastId = ""
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                val r = t.setLanguage(Locale.FRENCH)
                state = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) 2 else 1
                t.setSpeechRate(0.95f)
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        current = utteranceId?.toIntOrNull() ?: -1
                        speaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == lastId) { speaking = false; current = -1 }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == lastId) { speaking = false; current = -1 }
                    }
                })
            } else {
                state = 2
            }
        }
    }

    /** Dit une phrase (interrompt la lecture en cours). */
    fun say(text: String) {
        if (state != 1) return
        lastId = "-1"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "-1")
    }

    /** Lit une section du manuel. */
    fun speakOne(i: Int, text: String) {
        if (state != 1) return
        lastId = i.toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, i.toString())
    }

    /** Visite guidée : lit toutes les sections à la suite. */
    fun queue(texts: List<String>) {
        if (state != 1 || texts.isEmpty()) return
        lastId = (texts.size - 1).toString()
        texts.forEachIndexed { i, t ->
            tts?.speak(t, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, i.toString())
        }
    }

    fun stop() {
        tts?.stop()
        speaking = false
        current = -1
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}

@Composable
fun GuideScreen(act: MainActivity, autoStart: Boolean, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val voice = act.voice
    val prefs = remember { act.getSharedPreferences("p", 0) }
    val listState = rememberLazyListState()
    var tour by remember { mutableStateOf(false) }
    var hints by remember { mutableStateOf(prefs.getBoolean("vhints", false)) }
    val texts = remember { GuideSections.map { it.title + ". " + it.body } }

    BackHandler { voice.stop(); onBack() }
    DisposableEffect(Unit) { onDispose { voice.stop() } }
    LaunchedEffect(Unit) {
        if (autoStart) {
            var waited = 0
            while (voice.state == 0 && waited < 30) { delay(100); waited++ }
            if (voice.state == 1) { tour = true; voice.queue(texts) }
        }
    }
    LaunchedEffect(voice.current) {
        if (tour && voice.current >= 0) listState.animateScrollToItem(voice.current + 2)
    }
    LaunchedEffect(voice.speaking) { if (!voice.speaking) tour = false }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back) { voice.stop(); onBack() }
            Text("Guide et manuel", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Surface(
                    shape = RoundedCornerShape(24.dp), color = cs.primaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Ico(Ic.Speaker, cs.onPrimaryContainer, 32.dp)
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(
                                    "Visite guidée vocale", color = cs.onPrimaryContainer,
                                    fontSize = 18.sp, fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Une voix te présente toute l'application, section par section.",
                                    color = cs.onPrimaryContainer, fontSize = 13.sp
                                )
                            }
                        }
                        Button(
                            onClick = {
                                if (tour && voice.speaking) { voice.stop(); tour = false }
                                else { tour = true; voice.queue(texts) }
                            },
                            enabled = voice.state == 1,
                            modifier = Modifier.padding(top = 14.dp)
                        ) { Text(if (tour && voice.speaking) "Arrêter la visite" else "Lancer la visite") }
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    if (voice.state == 2) {
                        Text(
                            "La voix française n'est pas disponible sur ce téléphone. Installe-la dans Paramètres, " +
                                "Gestion générale, Synthèse vocale.",
                            color = cs.error, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Conseils parlés pendant l'utilisation", modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Switch(checked = hints, onCheckedChange = {
                            hints = it; prefs.edit().putBoolean("vhints", it).apply()
                        })
                    }
                }
            }
            items(GuideSections.size) { i ->
                val s = GuideSections[i]
                val active = voice.current == i && voice.speaking
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (active) cs.primaryContainer else cs.surface,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${i + 1}", color = cs.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(CircleShape).background(cs.primary)
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                            Text(
                                s.title, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            )
                            IconBtn(Ic.Speaker, tint = if (active) cs.primary else cs.onSurfaceVariant) {
                                if (active) voice.stop() else { tour = false; voice.speakOne(i, texts[i]) }
                            }
                        }
                        Text(
                            s.body, color = cs.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
