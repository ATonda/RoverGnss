package cz.antonin.rovergnss.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.export.ExportFilter
import cz.antonin.rovergnss.export.Exporter
import cz.antonin.rovergnss.link.LinkState
import cz.antonin.rovergnss.link.Quality
import cz.antonin.rovergnss.ntrip.NtripState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val carry: String = "palubní deska",   // 0.9.0: rover v autě vždy na palubní desce
    val autoStopSec: Int = 0,
    val light: Boolean = false,  // lehký záznam: bez surového proudu, každá 5. epocha (celý den)
    val circuit: Boolean = false, // Kontrolní okruh: studený start, měření na bodě, okruh, měření na bodě
)

/** Metadata záznamu (zadává uživatel před startem). */
data class TestMeta(
    val carry: String,         // způsob nošení
    val orientation: String,   // čelo krabičky (USB) vůči směru chůze
    val note: String,
)

data class TestMarker(val phoneMs: Long, val gnssMs: Long?, val label: String)

/**
 * Probíhající test. [phase]: prep (odpočet před krokem), step (krok běží), free (volný záznam se značkami);
 * Kontrolní okruh: c_off (čeká na vypnutí a zapnutí roveru), c_place (položit na bod), c_meas1 / c_meas2 (měření
 * s odpočtem), c_loop (okruh). [trackId] = 0, dokud záznam neběží (Kontrolní okruh před zapnutím roveru).
 */
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
    val point: Point? = null,        // Kontrolní okruh: bod
    val fixSec: Double? = null,      // Kontrolní okruh: od studeného startu na bodě do 3D fixu [s]
    val corrSec: Double? = null,     //                  do korekcí (SBAS/HAS/RTK) [s]
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
    private fun motionFileOf(trackId: Long) = File(dir(), "test_${trackId}_motion.csv")
    private var motionOut: java.io.BufferedWriter? = null
    private var lastMotion: Int? = null

    init {
        scope.launch(Dispatchers.IO) { refreshFiles() }
    }

    fun start(sc: TestScenario, meta: TestMeta) = scope.launch {
        if (_run.value != null) return@launch
        if (c.speedActive.value) {
            c.postMessage("Běží měření rychlosti (záložka Auto) – test nejde spustit, nastavení X20 je jiné")
            return@launch
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val id = c.startRecordingNow(name = "TEST ${sc.title} $stamp", forceRaw = !sc.light) ?: run {
            c.postMessage("Běží jiný záznam trasy – nejdřív ho ukonči")
            return@launch
        }
        _run.value = TestRun(id, sc, meta, System.currentTimeMillis())
        openMotion(id)
        mark("start: ${sc.title}")
        if (sc.steps.isNotEmpty()) stepJob = scope.launch { runSteps(sc) }
        else if (sc.autoStopSec > 0) stepJob = scope.launch { runTimer(sc.autoStopSec) }
        save()
    }

    private suspend fun openMotion(id: Long) {
        lastMotion = null
        withContext(Dispatchers.IO) {
            try {
                motionOut = motionFileOf(id).bufferedWriter(Charsets.UTF_8).also {
                    it.write("cas_telefon;cas_gnss;stav;stav_text;automaticky;model_x20;a_sigma_mg;a_odchylka_mg;omega_max;kroky_pct;kroky_hz;rychlost;zmen;" +
                        "teplota_imu;klid;kroky_celkem;bias_aktualizaci;bias_x;bias_y;bias_z\r\n")
                }
            } catch (_: java.io.IOException) {
                motionOut = null
            }
        }
    }

    // ------------------------------------------------------------------ Kontrolní okruh
    private var circuitJob: Job? = null
    private var circuitInfo = JSONObject()
    private var prevUser: Int? = null
    private var coldMs = 0L

    /**
     * Kontrolní okruh, krok 1: profil Automat, studený start X20 a výzva k vypnutí roveru. Záznam začne po novém
     * připojení roveru (manipulace s krabičkou se zaznamená), studený start a měření až po potvrzení „Položeno na bodě“.
     */
    fun startCircuit(sc: TestScenario, meta: TestMeta, point: Point) = scope.launch {
        if (_run.value != null) return@launch
        if (c.recording.value != null) {
            c.postMessage("Běží jiný záznam trasy – nejdřív ho ukonči")
            return@launch
        }
        circuitInfo = JSONObject().put("point", JSONObject().put("name", point.name).put("kind", point.kind)
            .put("lat", point.lat).put("lon", point.lon).put("hEll", point.hEll).put("y", point.y).put("x", point.x)
            .put("bpv", point.bpv))
        val users = c.prefs.userProfiles
        val ai = users.indexOfFirst { it.name == AUTO_PROFILE }
        prevUser = c.prefs.userIndex
        if (ai < 0) c.postMessage("Profil „$AUTO_PROFILE“ nenalezen – test běží s profilem ${c.prefs.user.name}")
        else if (ai != c.prefs.userIndex) c.selectUser(ai)
        circuitInfo.put("profile", c.prefs.user.name)
        _run.value = TestRun(0, sc, meta, System.currentTimeMillis(), phase = PH_OFF, point = point,
            info = "Studený start X20…")
        delay(1500)  // profil do roveru
        val ok = c.resetX20Now("cold")
        circuitInfo.put("coldBeforeOffMs", System.currentTimeMillis()).put("coldBeforeOffOk", ok)
        _run.value = _run.value?.copy(
            info = if (ok) "Studený start proveden – vypni rover" else "Studený start se nepotvrdil – vypni rover (po zapnutí se zopakuje)"
        )
        beep(ToneGenerator.TONE_PROP_ACK, 300)
        circuitJob = scope.launch { waitPowerCycle() }
    }

    /** Čeká na odpojení (vypnutí) a nové připojení roveru. */
    private suspend fun waitPowerCycle() {
        c.linkState.first { it !is LinkState.Connected }
        _run.value = _run.value?.copy(info = "Rover vypnutý – zapni ho, aplikace se připojí sama")
        c.linkState.first { it is LinkState.Connected }
        onPowered()
    }

    /** Ruční pokračování (rover se neodpojil nebo už je zapnutý). */
    fun circuitPowered() {
        if (_run.value?.phase != PH_OFF) return
        circuitJob?.cancel()
        circuitJob = scope.launch { onPowered() }
    }

    private suspend fun onPowered() {
        if (_run.value?.phase != PH_OFF) return
        _run.value = _run.value?.copy(info = "Rover připojen – spouštím záznam…")
        delay(3000)  // aplikace si načte stav roveru
        val r = _run.value ?: return
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val id = c.startRecordingNow(name = "TEST ${r.scenario.title} $stamp", forceRaw = true) ?: run {
            c.postMessage("Běží jiný záznam trasy – test nelze spustit")
            _run.value = _run.value?.copy(info = "Běží jiný záznam trasy – ukonči ho a test spusť znovu")
            return
        }
        val now = System.currentTimeMillis()
        _run.value = r.copy(trackId = id, startMs = now, phase = PH_PLACE, info = "Polož rover na bod ${r.point?.name ?: ""} a potvrď")
        openMotion(id)
        mark("start: ${r.scenario.title}")
        mark("rover připojen – příprava")
        beep(ToneGenerator.TONE_PROP_BEEP2, 300)
        save()
    }

    /** „Položeno na bodě“: 1. = studený start a měření [MEAS1_SEC], 2. (po okruhu) = měření [MEAS2_SEC]. */
    fun circuitPlaced() = scope.launch {
        val r = _run.value ?: return@launch
        when (r.phase) {
            PH_PLACE -> {
                mark("položeno na bodě 1")
                coldMs = System.currentTimeMillis()
                val ok = c.resetX20Now("cold")
                circuitInfo.put("cold1Ms", coldMs).put("cold1Ok", ok)
                mark(if (ok) "studený start" else "studený start nepotvrzen")
                startPointMeas(1, MEAS1_SEC)
            }
            PH_LOOP -> {
                mark("položeno na bodě 2")
                startPointMeas(2, MEAS2_SEC)
            }
            else -> Unit
        }
    }

    private suspend fun startPointMeas(n: Int, sec: Int) {
        val r = _run.value ?: return
        val p = r.point ?: return
        c.startMeasurement(p, 0, inTrack = r.trackId).join()
        val m = c.measure.value
        if (m == null) c.postMessage("Měření bodu se nespustilo – záznam běží dál")
        circuitInfo.put("meas$n", JSONObject().put("startMs", System.currentTimeMillis()).put("plannedSec", sec)
            .put("measurementId", m?.measurementId ?: JSONObject.NULL).put("staticModel", m?.staticModel ?: false))
        _run.value = r.copy(phase = if (n == 1) PH_MEAS1 else PH_MEAS2, remainSec = sec,
            info = if (n == 1) "Měření na bodě po studeném startu" else "Druhé měření na bodě")
        beep(ToneGenerator.TONE_PROP_BEEP2, 400)
        save()
        circuitJob = scope.launch { measCountdown(n, sec) }
    }

    private suspend fun measCountdown(n: Int, sec: Int) {
        val t0 = System.currentTimeMillis()
        while (true) {
            val left = sec - ((System.currentTimeMillis() - t0) / 1000).toInt()
            if (left <= 0) break
            var r = _run.value ?: return
            if (n == 1) {
                val p = c.pos.value
                if (p != null && p.rxMs > coldMs + 500) {
                    if (r.fixSec == null && p.hasFix) {
                        r = r.copy(fixSec = (p.rxMs - coldMs) / 1000.0)
                        mark("3D fix")
                    }
                    if (r.corrSec == null && p.hasFix && p.quality.code in Quality.DGNSS.code..Quality.FLOAT.code) {
                        r = r.copy(corrSec = (p.rxMs - coldMs) / 1000.0)
                        mark("korekce (${p.quality.label})")
                    }
                }
            }
            _run.value = r.copy(remainSec = left)
            if (left <= 3) beep(ToneGenerator.TONE_PROP_BEEP, 120)
            delay(1000)
        }
        val m = c.measure.value
        val o = circuitInfo.optJSONObject("meas$n") ?: JSONObject().also { circuitInfo.put("meas$n", it) }
        m?.result?.let { res ->
            o.put("epochs", res.epochs).put("used", res.used).put("lat", res.lat).put("lon", res.lon).put("hEll", res.h)
        }
        m?.offset?.let { off -> o.put("dN", off.dN).put("dE", off.dE).put("dU", off.dU).put("d2", off.d2).put("dH", off.dH) }
        c.stopMeasurement(true).join()
        o.put("endMs", System.currentTimeMillis())
        _run.value?.let { r ->
            if (n == 1) circuitInfo.put("ttffFixS", r.fixSec ?: JSONObject.NULL).put("ttffCorrS", r.corrSec ?: JSONObject.NULL)
        }
        mark("bod $n: konec měření")
        beep(ToneGenerator.TONE_PROP_ACK, 800)
        save()
        if (n == 1) {
            _run.value = _run.value?.copy(phase = PH_LOOP, remainSec = 0,
                info = "Okruh: auto, pole, les, zpět. Při změně stiskni značku, na bodě „Položeno na bodě“.")
        } else {
            stop()
        }
    }

    /**
     * Věta STA z roveru (1 Hz) během testu: stav přístroje a znaky do motion.csv, změna stavu roveru jako značka
     * „rover: …“ (porovnání s ručními značkami stavu).
     */
    fun onSta(s: cz.antonin.rovergnss.link.Sta) {
        if (_run.value == null) return
        val m = s.motion ?: return
        if (lastMotion != null && m != lastMotion) mark("rover: ${s.motionText}")
        lastMotion = m
        val now = System.currentTimeMillis()
        val p = c.pos.value
        val gnss = p?.timeMs?.let { it + (now - p.rxMs) }
        fun d(v: Double?) = v?.let { String.format(Locale.US, "%.2f", it) } ?: ""
        fun d4(v: Double?) = v?.let { String.format(Locale.US, "%.4f", it) } ?: ""
        try {
            motionOut?.write(
                listOf(now.toString(), gnss?.toString() ?: "", m.toString(), s.motionText ?: "", if (s.motionAuto) "1" else "0",
                    s.dynModel?.toString() ?: "", s.accStdMg?.toString() ?: "", s.accDevMg?.toString() ?: "", d(s.gyroMax),
                    s.stepScore?.toString() ?: "", d(s.stepHz), d(s.motionSpeed), s.motionChanges?.toString() ?: "",
                    d(s.imuTemp), s.still?.let { if (it) "1" else "0" } ?: "", s.stepsTotal?.toString() ?: "",
                    s.biasUpdates?.toString() ?: "", d4(s.biasX), d4(s.biasY), d4(s.biasZ)).joinToString(";") + "\r\n"
            )
        } catch (_: java.io.IOException) {
            motionOut = null
        }
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
        if (r.scenario.circuit) {
            val self = coroutineContext[Job]
            if (circuitJob != self) circuitJob?.cancel()
            circuitJob = null
            if (c.measure.value != null) c.stopMeasurement(true).join()
            prevUser?.let { if (it != c.prefs.userIndex) c.selectUser(it) }
            prevUser = null
            if (r.trackId == 0L) {  // záznam ještě nezačal (rover se nezapnul)
                _run.value = null
                c.postMessage("Test zrušen před začátkem záznamu")
                return@launch
            }
        }
        mark("konec")
        c.stopRecording("konec testu").join()
        withContext(Dispatchers.IO) {
            try {
                motionOut?.close()
            } catch (_: java.io.IOException) {
            }
            motionOut = null
        }
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
        if (r.trackId == 0L) return@withContext
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
            // geometrie IMU v krabičce (rover-firmware-specifikace.md; osy ověřené testem 6 poloh 20. 10.)
            .put("imu", JSONObject()
                .put("axes", "X vodorovně doprava (pohled na čelo s USB), Y dolů ke dnu, Z dovnitř krabičky (od čela)")
                .put("lever_mm", JSONArray(listOf(-17.5, -16.0, 45.0)))
                .put("box_mm", JSONArray(listOf(100, 85, 40))))
            .put("markers", JSONArray().apply {
                r.markers.forEach { m ->
                    put(JSONObject().put("phoneMs", m.phoneMs).put("gnssMs", m.gnssMs ?: JSONObject.NULL).put("label", m.label))
                }
            })
            .put("savedMs", System.currentTimeMillis())
        if (r.scenario.circuit) o.put("circuit", JSONObject(circuitInfo.toString()))
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
        motionFileOf(t.trackId).delete()
        // karta Trasy už není (0.10.0) – záznam testu se maže s testem
        c.db.tracks().get(t.trackId)?.let { c.deleteTrack(it) }
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
                motionFileOf(track.id).takeIf { it.exists() }?.let { f ->
                    z.putNextEntry(ZipEntry("motion.csv"))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                    n++
                }
                track.rawFile?.let { c.rawFile(it) }?.takeIf { it.exists() }?.let { f ->
                    z.putNextEntry(ZipEntry("raw.log"))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                    n++
                }
                // deník událostí aplikace za dobu testu (0.9.0): konec záznamu s důvodem, NTRIP, popředí, pád
                z.putNextEntry(ZipEntry("events.log"))
                z.write(EventLog.since(t.startMs - 60_000).toByteArray(Charsets.UTF_8))
                z.closeEntry()
                n++
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
        const val PH_OFF = "c_off"
        const val PH_PLACE = "c_place"
        const val PH_MEAS1 = "c_meas1"
        const val PH_LOOP = "c_loop"
        const val PH_MEAS2 = "c_meas2"
        const val MEAS1_SEC = 1800
        const val MEAS2_SEC = 900
        const val AUTO_PROFILE = "Automat"

        /** Název scénáře do jména souboru: bez diakritiky a mezer („6 poloh“ -> „6_poloh“). */
        fun slug(s: String): String =
            java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')

        private val DAY_PROC = listOf(
            "Běžný den s roverem: měření bodů, chůze, auto (krabička na palubní desce), manipulace.",
            "Při každé změně označ skutečný stav tlačítkem – slouží k ověření automatického přepínání modelu.",
            "Rover musí mít profil „Automaticky“. Změny stavu roveru se zapisují samy (značka „rover: …“).",
        )
        private val DAY_MARKS = listOf("Měřím", "Manipulace", "Chodím", "Jedu", "poznámka")

        val CARRY = listOf("palubní deska", "levý bok", "pravý bok", "geodetická tyč", "v ruce", "batoh", "jiné")
        val ORIENT = listOf("čelo (USB) dopředu", "čelo (USB) dozadu", "čelo (USB) nahoru", "čelo (USB) dolů", "neurčeno")

        val SCENARIOS = listOf(
            TestScenario(
                "circuit", "Kontrolní okruh",
                "známý bod 30 min po studeném startu, okruh autem a pěšky (pole, les), znovu bod 15 min",
                listOf(
                    "Vyber bod s danými souřadnicemi (ETRS89 nebo S-JTSK + Bpv). Profil se přepne na Automat.",
                    "„Studený start a vypnout“ – pak rover vypni. Po zapnutí se aplikace připojí a začne záznam.",
                    "Polož rover na bod (střed krabičky na značku, výška antény podle profilu) a potvrď „Položeno na bodě“ – studený start, měření 30 min s odpočtem.",
                    "Pak okruh: auto, pole, les, zpět – při změně značka. Na bodě znovu „Položeno na bodě“ – měření 15 min, pak konec.",
                    "Rover během testu nenabíjet (ohřev IMU), telefon nos u sebe (Bluetooth).",
                ),
                markers = listOf("nesu v ruce", "nastupuju", "jedu", "vystupuju", "chodím", "les – vstup", "les – výstup", "klid", "poznámka"),
                carry = "různě", circuit = true,
            ),
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
                "day_light", "Celý den (lehký)", "ověření automatického modelu; bez surové IMU, ~1 MB/h",
                DAY_PROC, markers = DAY_MARKS, carry = "různě", light = true,
            ),
            TestScenario(
                "day_full", "Celý den (plný)", "ladění mezí z dat; se surovou IMU, ~15 MB/h – na kratší úseky",
                DAY_PROC, markers = DAY_MARKS, carry = "různě",
            ),
            TestScenario(
                "car", "Auto", "jízda, model automotive",
                listOf("Krabička pevně v držáku, poloha čela zapiš v metadatech.", "Značky rozjezdu a zastavení."),
                markers = listOf("rozjezd", "zastavení", "zatáčka", "poznámka"),
                carry = "palubní deska",
            ),
        )
    }
}
