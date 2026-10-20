package cz.antonin.rovergnss.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import cz.antonin.rovergnss.export.ExportFilter
import cz.antonin.rovergnss.export.Exporter
import cz.antonin.rovergnss.ntrip.NtripState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Řízený krok testu: příprava (odpočet), pak [sec] sekund s pípnutím na začátku a na konci. */
data class TestStep(val label: String, val sec: Int, val prepSec: Int = 5)

/** Scénář testovacího záznamu pro EKF. [autoStopSec] > 0 = záznam se sám ukončí. */
data class TestScenario(
    val id: String,
    val title: String,
    val purpose: String,
    val procedure: List<String>,
    val steps: List<TestStep> = emptyList(),
    val markers: List<String> = emptyList(),
    val carry: String = "levý bok",
    val autoStopSec: Int = 0,
)

/** Metadata záznamu (zadává uživatel před startem). */
data class TestMeta(
    val carry: String,         // způsob nošení
    val orientation: String,   // čelo krabičky (USB) vůči směru chůze
    val note: String,
)

data class TestMarker(val phoneMs: Long, val gnssMs: Long?, val label: String)

/** Probíhající test. [phase]: prep (odpočet před krokem), step (krok běží), free (volný záznam se značkami). */
data class TestRun(
    val trackId: Long,
    val scenario: TestScenario,
    val meta: TestMeta,
    val startMs: Long,
    val markers: List<TestMarker> = emptyList(),
    val stepIndex: Int = -1,
    val phase: String = "free",
    val remainSec: Int = 0,
    val info: String = "",
)

/** Uložený test (soubor tests/test_<trasa>.json). */
data class TestFile(val trackId: Long, val title: String, val startMs: Long, val markers: Int, val file: File)

/**
 * Testovací záznamy pro vývoj EKF: scénář s postupem, metadata (nošení, orientace), značky s časem GNSS,
 * řízené kroky s odpočtem a pípnutím. Záznam je běžná trasa se surovým proudem (IMU 0xF5, NAV-PVT/COV/HPPOSLLH
 * z roveru FW 0.13.0+) a diagnostikou; metadata a značky jdou do tests/test_<id>.json. Export = jeden ZIP.
 */
class TestSession(private val context: Context, private val scope: CoroutineScope, private val c: RoverController) {
    private val _run = MutableStateFlow<TestRun?>(null)
    val run: StateFlow<TestRun?> = _run.asStateFlow()
    private val _files = MutableStateFlow<List<TestFile>>(emptyList())
    val files: StateFlow<List<TestFile>> = _files.asStateFlow()
    private var stepJob: Job? = null

    private fun dir() = File(context.filesDir, "tests").also { it.mkdirs() }
    private fun fileOf(trackId: Long) = File(dir(), "test_$trackId.json")

    init {
        scope.launch(Dispatchers.IO) { refreshFiles() }
    }

    fun start(sc: TestScenario, meta: TestMeta) = scope.launch {
        if (_run.value != null) return@launch
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val id = c.startRecordingNow(name = "TEST ${sc.title} $stamp", forceRaw = true) ?: run {
            c.postMessage("Běží jiný záznam trasy – nejdřív ho ukonči")
            return@launch
        }
        _run.value = TestRun(id, sc, meta, System.currentTimeMillis())
        mark("start: ${sc.title}")
        if (sc.steps.isNotEmpty()) stepJob = scope.launch { runSteps(sc) }
        else if (sc.autoStopSec > 0) stepJob = scope.launch { runTimer(sc.autoStopSec) }
        save()
    }

    /** Značka s časem telefonu a odhadem času GNSS (poslední epocha + doba od jejího příjmu). */
    fun mark(label: String) {
        val r = _run.value ?: return
        val now = System.currentTimeMillis()
        val p = c.pos.value
        val gnss = p?.timeMs?.let { it + (now - p.rxMs) }
        _run.value = r.copy(markers = r.markers + TestMarker(now, gnss, label))
    }

    fun stop() = scope.launch {
        val r = _run.value ?: return@launch
        stepJob?.cancel()
        mark("konec")
        c.stopRecording().join()
        save()
        _run.value = null
        withContext(Dispatchers.IO) { refreshFiles() }
        c.postMessage("Test uložen: ${r.scenario.title} – export ZIP v seznamu testů")
    }

    private suspend fun runTimer(sec: Int) {
        for (left in sec downTo 1) {
            _run.value = _run.value?.copy(phase = "free", remainSec = left, info = "do konce")
            delay(1000)
        }
        beep(ToneGenerator.TONE_PROP_ACK, 600)
        stop()
    }

    private suspend fun runSteps(sc: TestScenario) {
        sc.steps.forEachIndexed { i, st ->
            for (left in st.prepSec downTo 1) {
                _run.value = _run.value?.copy(stepIndex = i, phase = "prep", remainSec = left, info = st.label)
                if (left <= 3) beep(ToneGenerator.TONE_PROP_BEEP, 120)
                delay(1000)
            }
            mark("${st.label}: začátek")
            beep(ToneGenerator.TONE_PROP_BEEP2, 400)
            for (left in st.sec downTo 1) {
                _run.value = _run.value?.copy(stepIndex = i, phase = "step", remainSec = left, info = st.label)
                delay(1000)
            }
            mark("${st.label}: konec")
            beep(ToneGenerator.TONE_PROP_ACK, 300)
            save()
        }
        _run.value = _run.value?.copy(stepIndex = sc.steps.size, phase = "free", remainSec = 0, info = "kroky hotové")
        delay(2000)
        stop()
    }

    private fun beep(tone: Int, ms: Int) {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            tg.startTone(tone, ms)
            scope.launch {
                delay(ms + 200L)
                tg.release()
            }
        } catch (_: RuntimeException) {
            // bez zvuku (např. obsazený zvukový kanál) – test běží dál
        }
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        val r = _run.value ?: return@withContext
        val info = c.info.value
        val sta = c.sta.value
        val o = JSONObject()
            .put("format", 1)
            .put("scenario", JSONObject().put("id", r.scenario.id).put("title", r.scenario.title)
                .put("purpose", r.scenario.purpose).put("procedure", JSONArray(r.scenario.procedure)))
            .put("trackId", r.trackId)
            .put("startMs", r.startMs)
            .put("meta", JSONObject().put("carry", r.meta.carry).put("orientation", r.meta.orientation)
                .put("note", r.meta.note).put("antennaH_m", c.prefs.antennaH))
            .put("rover", JSONObject().put("fw", info?.fw).put("x20", info?.x20).put("x20fw", info?.x20Fw)
                .put("profile", info?.profileName).put("rateHz", info?.rateHz).put("navRec", sta?.navRec)
                .put("corrSource", sta?.corrSource))
            .put("corrections", when (c.ntripState.value) {
                is NtripState.Streaming -> "NTRIP (RTCM)"
                else -> "bez NTRIP (HAS/SBAS podle profilu roveru)"
            })
            // geometrie IMU v krabičce (rover-firmware-specifikace.md, Rozhodnutí 20. 10.)
            .put("imu", JSONObject()
                .put("axes", "X vodorovně doleva (pohled na čelo s USB), Y dolů ke dnu, Z ven z čela")
                .put("lever_mm", JSONArray(listOf(17.5, -16.0, -45.0)))
                .put("box_mm", JSONArray(listOf(100, 85, 40))))
            .put("markers", JSONArray().apply {
                r.markers.forEach { m ->
                    put(JSONObject().put("phoneMs", m.phoneMs).put("gnssMs", m.gnssMs ?: JSONObject.NULL).put("label", m.label))
                }
            })
            .put("savedMs", System.currentTimeMillis())
        fileOf(r.trackId).writeText(o.toString(2))
    }

    private fun refreshFiles() {
        val l = dir().listFiles { f -> f.name.startsWith("test_") && f.name.endsWith(".json") }?.mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                TestFile(o.optLong("trackId"), o.optJSONObject("scenario")?.optString("title") ?: "?",
                    o.optLong("startMs"), o.optJSONArray("markers")?.length() ?: 0, f)
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()
        _files.value = l.sortedByDescending { it.startMs }
    }

    fun delete(t: TestFile) = scope.launch(Dispatchers.IO) {
        t.file.delete()
        refreshFiles()
    }

    /** ZIP: test.json, body.csv (všechny epochy), diag.csv, raw.log (surový proud). Vrací počet souborů. */
    suspend fun exportZip(t: TestFile, out: OutputStream): Int = withContext(Dispatchers.IO) {
        val track = c.db.tracks().get(t.trackId)
        var n = 0
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("test.json"))
            t.file.inputStream().use { it.copyTo(z) }
            z.closeEntry()
            n++
            if (track != null) {
                z.putNextEntry(ZipEntry("body.csv"))
                Exporter.csv(track, c.db.tracks().points(track.id), ExportFilter(minQuality = (0..6).toSet(), maxHAcc = 1e9), NoClose(z))
                z.closeEntry()
                z.putNextEntry(ZipEntry("diag.csv"))
                Exporter.diagCsv(c.db.tracks().diag(track.id), NoClose(z))
                z.closeEntry()
                n += 2
                track.rawFile?.let { c.rawFile(it) }?.takeIf { it.exists() }?.let { f ->
                    z.putNextEntry(ZipEntry("raw.log"))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                    n++
                }
            }
        }
        n
    }

    /** Proud do ZIPu, který export nesmí zavřít (writer.flush stačí). */
    private class NoClose(private val o: OutputStream) : OutputStream() {
        override fun write(b: Int) = o.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = o.write(b, off, len)
        override fun flush() = o.flush()
        override fun close() = o.flush()
    }

    companion object {
        val CARRY = listOf("levý bok", "pravý bok", "geodetická tyč", "v ruce", "batoh", "auto – držák", "jiné")
        val ORIENT = listOf("čelo (USB) dopředu", "čelo (USB) dozadu", "čelo (USB) nahoru", "čelo (USB) dolů", "neurčeno")

        val SCENARIOS = listOf(
            TestScenario(
                "rest", "Klid 30 min", "šum a bias IMU, teplotní drift (jednou studený, jednou zahřátý rover)",
                listOf("Krabička leží dnem dolů na rovné pevné ploše.", "Nehýbat, nedotýkat se, bez otřesů (stůl, ne parapet u silnice).",
                    "Záznam skončí sám po 30 min."),
                carry = "leží na stole", autoStopSec = 1800,
            ),
            TestScenario(
                "six", "6 poloh", "bias a měřítko akcelerometru, kontrola os IMU",
                listOf("Krabičku pokládej na rovnou plochu podle pokynu, každá poloha 30 s.",
                    "Během odpočtu (5 s) přetoč, při pípnutí už nehýbat.", "Pořadí a názvy stěn jsou na obrazovce."),
                steps = listOf(
                    TestStep("dnem dolů (anténa nahoru)", 30, 8), TestStep("víkem dolů (anténa dolů)", 30, 8),
                    TestStep("čelem s USB nahoru", 30, 8), TestStep("čelem s USB dolů", 30, 8),
                    TestStep("levým bokem nahoru", 30, 8), TestStep("pravým bokem nahoru", 30, 8),
                ),
                carry = "leží na stole",
            ),
            TestScenario(
                "orient", "Orientace při nošení", "osy krabičky vůči tělu a směru chůze",
                listOf("Krabička na levém boku tak, jak ji nosíš.", "Řiď se pokyny: stání, předklon, záklon, úklony, rovná chůze.",
                    "Venku s fixem, chůze rovně po cestě."),
                steps = listOf(
                    TestStep("stát klidně", 10), TestStep("předklon", 5), TestStep("záklon", 5),
                    TestStep("úklon vlevo", 5), TestStep("úklon vpravo", 5), TestStep("rovná chůze", 30),
                ),
            ),
            TestScenario(
                "open", "Volné prostranství s RTK", "reference: výpadky GNSS se simulují vyřazením dat",
                listOf("Zapni NTRIP (RTK fix).", "Okruh po otevřené ploše, aspoň 3 zastávky po 60 s a víc.",
                    "Zastávky označ tlačítky."),
                markers = listOf("zastávka – začátek", "zastávka – konec", "otočka", "poznámka"),
            ),
            TestScenario(
                "city", "Město", "skutečné výpadky a vícecestné šíření u budov",
                listOf("Ulice s budovami, průchod podloubím nebo průjezdem.", "Značky u zastávek a průchodů."),
                markers = listOf("zastávka – začátek", "zastávka – konec", "průchod – začátek", "průchod – konec", "u budovy", "poznámka"),
            ),
            TestScenario(
                "forest", "Les", "výpadky pod stromy",
                listOf("Začni i skonči na volném prostranství (kontrola konce trasy).", "Značky vstupu a výstupu z lesa."),
                markers = listOf("vstup do lesa", "výstup z lesa", "zastávka – začátek", "zastávka – konec", "poznámka"),
            ),
            TestScenario(
                "car", "Auto", "jízda, model automotive",
                listOf("Krabička pevně v držáku, poloha čela zapiš v metadatech.", "Značky rozjezdu a zastavení."),
                markers = listOf("rozjezd", "zastavení", "zatáčka", "poznámka"),
                carry = "auto – držák",
            ),
        )
    }
}
