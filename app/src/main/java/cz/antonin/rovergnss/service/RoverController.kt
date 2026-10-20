package cz.antonin.rovergnss.service

import android.content.Context
import android.util.Log
import cz.antonin.rovergnss.data.AppDb
import cz.antonin.rovergnss.cuzk.CuzkPoint
import cz.antonin.rovergnss.data.Job
import cz.antonin.rovergnss.data.MeasureCalc
import cz.antonin.rovergnss.data.MeasureEpoch
import cz.antonin.rovergnss.data.MeasureOffset
import cz.antonin.rovergnss.data.MeasureResult
import cz.antonin.rovergnss.data.Measurement
import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.data.PointCoords
import cz.antonin.rovergnss.data.PointQuality
import cz.antonin.rovergnss.data.PointSource
import cz.antonin.rovergnss.data.UserProfile
import cz.antonin.rovergnss.geo.Angles
import cz.antonin.rovergnss.link.Quality
import cz.antonin.rovergnss.data.Prefs
import cz.antonin.rovergnss.data.Track
import cz.antonin.rovergnss.data.TrackDiag
import cz.antonin.rovergnss.data.TrackPoint
import cz.antonin.rovergnss.geo.Etrs
import cz.antonin.rovergnss.geo.Geodetic
import cz.antonin.rovergnss.link.BtLink
import cz.antonin.rovergnss.link.LinkState
import cz.antonin.rovergnss.link.Pos
import cz.antonin.rovergnss.link.RoverProtocol
import cz.antonin.rovergnss.link.Sat
import cz.antonin.rovergnss.link.Sta
import cz.antonin.rovergnss.link.StreamParser
import cz.antonin.rovergnss.ntrip.NtripClient
import cz.antonin.rovergnss.ntrip.NtripProfile
import cz.antonin.rovergnss.ntrip.NtripState
import cz.antonin.rovergnss.ntrip.RtcmFramer
import cz.antonin.rovergnss.phone.PhoneCompare
import cz.antonin.rovergnss.phone.PhoneGnss
import cz.antonin.rovergnss.phone.PhoneSource
import cz.antonin.rovergnss.phone.PhoneTtff
import cz.antonin.rovergnss.phone.X20Assist
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Kvalita spojení s roverem (z pohledu dat, ne síly signálu – tu Android u SPP neukáže).
 * @param bars 0–4 jako u signálu
 */
data class LinkQuality(
    val bars: Int,
    val ageMs: Long?,        // od posledně přijaté polohy
    val latencyMs: Long?,    // medián zpoždění (příjem v telefonu − čas GNSS) za 10 s
    val lossPct: Double?,    // ztracené epochy za 30 s
    val backlog: Long?,      // nepotvrzená data v roveru [B] (FW 0.8.1+)
    val lostTotal: Long,     // ztracené epochy od připojení
)

/** Stav záznamu trasy. */
data class RecordingState(val trackId: Long, val name: String, val points: Int, val startMs: Long)

/** Druh měření bodu. */
object MeasureMode {
    const val CONTROL = 0  // kontrolní bod (daný) – měření se ukládá k bodu
    const val SURVEY = 1   // zaměření nového bodu „tady“
    const val STAKE = 2    // vytyčení daného bodu – krátké průměrování
}

/** Zdroj epoch pro terč. */
object TargetSource {
    const val LIVE = 0     // průběžný průměr v Živě
    const val MEASURE = 1  // probíhající měření bodu
}

/** Probíhající měření bodu (průběžný výpočet 1× za sekundu). */
data class MeasureState(
    val measurementId: Long,  // CONTROL: řádek měření, jinak 0
    val trackId: Long,        // CONTROL: trasa „M …“, jinak 0
    val point: Point,         // CONTROL/STAKE: daný bod; SURVEY: nový bod (jen název, id 0)
    val startMs: Long,
    val plannedSec: Int,      // 0 = do ručního ukončení
    val antennaH: Double,
    val result: MeasureResult? = null,
    val offset: MeasureOffset? = null,
    val staticModel: Boolean = false, // rover potvrdil statický model X20
    val raw: Boolean = false,         // ukládají se RAWX/SFRBX
    val rawFrames: Int = 0,
    val mode: Int = MeasureMode.CONTROL,
    val sigmaLimit: Double = 0.0,     // SURVEY: konec při σ vodorovně ≤ mez
    val minEpochs: Int = 0,           // SURVEY: a aspoň tolik epoch
)

/**
 * Průběžný průměr polohy od posledního vynulování (panel Živě). Poloha antény (bez odečtení výšky antény).
 * Průměr a σ z nejlepší kvality s aspoň 10 epochami ([MeasureCalc]). [startMs], [lastMs] čas telefonu první
 * a poslední epochy; [resets] počet vynulování od startu aplikace, [resetReason] a [resetMs] poslední z nich.
 */
data class LiveAverage(
    val startMs: Long,
    val lastMs: Long,
    val epochs: Int,
    val result: MeasureResult,
    val resets: Int = 0,
    val resetReason: String? = null,
    val resetMs: Long = 0,
) {
    val quality: Quality get() = Quality.of(result.usedQuality)
    val qualityLabel: String get() = if (result.usedQuality == 0) "smíšená" else quality.label
}

/** Průměr uložený v telefonu při minulém běhu aplikace (nabídka po startu). */
data class LiveRestore(val startMs: Long, val lastMs: Long, val epochs: Int)

/** Údaje o roveru z odpovědi na `get info`. */
data class RoverInfo(
    val fw: String,
    val proto: Int,
    val name: String,
    val x20: String?,
    val x20Fw: String?,
    val imu: Boolean,
    val profileIndex: Int,
    val profileName: String,
    val rateHz: Int,
    // FW 0.13.0+
    val caps: Set<String> = emptySet(),  // set, cal, pass, navrec, sig, boot
    val boot: BootTimes? = null,
    val upMs: Long? = null,              // doba od startu ESP při odpovědi
    val resetReason: Int? = null,        // esp_reset_reason: 1 zapnutí napájení, 3 SW restart, …
) {
    /** Rover umí nastavení z aplikace (FW 0.13.0+). */
    val canSetup: Boolean get() = "set" in caps
}

/** Doba od zapnutí roveru do prvního 3D fixu, korekcí, float a RTK fix [ms] (null = zatím ne). */
data class BootTimes(val fix: Long?, val corr: Long?, val float: Long?, val fixRtk: Long?)

/**
 * Jádro aplikace: spojení s roverem, NTRIP, živá data, záznam tras.
 * Žije po celou dobu procesu (vytváří RoverApp); služba na popředí jen drží proces při zhasnutém displeji.
 */
class RoverController(private val context: Context, val db: AppDb, val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ------------------------------------------------------------------ živá data
    private val _pos = MutableStateFlow<Pos?>(null)
    val pos: StateFlow<Pos?> = _pos.asStateFlow()

    private val _sta = MutableStateFlow<Sta?>(null)
    val sta: StateFlow<Sta?> = _sta.asStateFlow()

    private val _sats = MutableStateFlow<List<Sat>>(emptyList())
    val sats: StateFlow<List<Sat>> = _sats.asStateFlow()

    private val _info = MutableStateFlow<RoverInfo?>(null)
    val info: StateFlow<RoverInfo?> = _info.asStateFlow()

    /** Stopa posledních poloh pro mapu (ETRS89, 1 Hz, max. [TRAIL_MAX] = 2 h). */
    private val _trail = MutableStateFlow<List<DoubleArray>>(emptyList())
    val trail: StateFlow<List<DoubleArray>> = _trail.asStateFlow()

    /** Události a hlášky pro UI (jednorázové). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Žádost o zavření okna aplikace (po [shutdown]). */
    private val _exit = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val exit: SharedFlow<Unit> = _exit.asSharedFlow()

    /** Úplné ukončení: uloží běžící záznam, odpojí NTRIP a rover (i znovupřipojování), zastaví službu, zavře okno. */
    fun shutdown() = scope.launch {
        if (_measure.value != null) stopMeasurement().join()
        stopRecording().join()
        withContext(Dispatchers.IO) { flushLiveFile() }  // průměr zůstává v telefonu pro příští start
        ntrip.stop()
        link.disconnect()
        phoneTtff.stop()
        phoneCompare.stopSession().join()
        phone.stopAll()
        RoverService.stop(context)
        _exit.tryEmit(Unit)
    }

    /** Řádky protokolu pro diagnostiku (poslední) */
    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console.asStateFlow()

    @Volatile private var lastGga: String? = null
    @Volatile private var lastGgaMs = 0L

    // ------------------------------------------------------------------ spojení
    private val satParts = ArrayList<Sat>()
    private var satNextPart = 1

    // kvalita spojení
    private val _linkQuality = MutableStateFlow(LinkQuality(0, null, null, null, null, 0))
    val linkQuality: StateFlow<LinkQuality> = _linkQuality.asStateFlow()
    private val epochTimes = ArrayDeque<Long>()      // čas GNSS přijatých epoch (30 s)
    private val epochLatency = ArrayDeque<Long>()    // zpoždění epoch (10 s)
    private var lastPosRxMs = 0L
    private var lastEpochMs = 0L
    private var lostTotal = 0L

    /** Aplikace je na obrazovce (nastavuje MainActivity) – do diagnostiky. */
    @Volatile var foreground = false
    private val parser = StreamParser(onLine = ::onLine, onUbx = ::onUbx)
    val link = BtLink(context, scope) { buf, n ->
        rawWrite(buf, n)
        synchronized(parser) { parser.feed(buf, n) }
    }

    /** Věty $PRVR se špatným kontrolním součtem */
    val badChecksum = AtomicLong()
    val droppedLines: Long get() = parser.droppedLines
    val linkState: StateFlow<LinkState> get() = link.state

    // RTCM se do roveru posílá po celých rámcích, aby se mezi ně mohly vkládat příkazy JSON
    // (rover rozpozná '{' jen mimo rámec RTCM).
    val rtcmFramer = RtcmFramer { frame -> link.send(frame) }
    val ntrip = NtripClient(scope, onRtcm = { synchronized(rtcmFramer) { rtcmFramer.feed(it) } }, ggaProvider = ::currentGga)
    val ntripState: StateFlow<NtripState> get() = ntrip.state

    // ------------------------------------------------------------------ GNSS telefonu a asistence X20
    /** Zdroje polohy telefonu (čip, Fused, Network) a jeho satelity. */
    val phone = PhoneGnss(context)
    /** Porovnání poloh telefonu s roverem. */
    val phoneCompare = PhoneCompare(scope, db, prefs)
    /** Test doby do fixu telefonu (vliv A-GNSS). */
    val phoneTtff = PhoneTtff(
        context, scope, phone,
        errorOf = { phoneCompare.errorOf(it) },
        roverFix = { _pos.value?.hasFix == true },
        message = { _messages.tryEmit(it) },
    )
    /** Asistence X20 z telefonu: MGA-INI a záloha databáze MGA-DBD (FW 0.11.0+). */
    val assist = X20Assist(
        context, scope, prefs,
        send = { link.send(it) },
        phoneFix = { phone.lastKnown() },
        busy = { _measure.value != null },
        message = { _messages.tryEmit(it) },
    )

    /** Zapne / vypne zdroj polohy telefonu (uloží se). */
    fun setPhoneSource(s: PhoneSource, on: Boolean) {
        phone.setEnabled(s, on)
        prefs.phoneSources = phone.enabled.value.map { it.code }.toSet()
        RoverService.refreshType(context)
    }

    /** Po udělení oprávnění k poloze. */
    fun phonePermissionChanged() {
        phone.refresh()
        RoverService.refreshType(context)
    }

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    // ------------------------------------------------------------------ záznam
    private val _recording = MutableStateFlow<RecordingState?>(null)
    val recording: StateFlow<RecordingState?> = _recording.asStateFlow()
    private val recBuffer = ArrayList<TrackPoint>()
    private val diagBuffer = ArrayList<TrackDiag>()
    private var rawOut: OutputStream? = null
    private val rawLock = Any()
    private var recCounter = 0

    // ------------------------------------------------------------------ zakázky
    private val _activeJob = MutableStateFlow<Job?>(null)
    val activeJob: StateFlow<Job?> = _activeJob.asStateFlow()

    /** Aktivní zakázka; když žádná není, založí „Výchozí“. */
    suspend fun ensureJob(): Job {
        _activeJob.value?.let { return it }
        val j = db.jobs().get(prefs.activeJobId) ?: db.jobs().first()
            ?: Job(name = "Výchozí").let { it.copy(id = db.jobs().insert(it)) }
        prefs.activeJobId = j.id
        _activeJob.value = j
        return j
    }

    fun setActiveJob(id: Long) = scope.launch {
        val j = db.jobs().get(id) ?: return@launch
        prefs.activeJobId = j.id
        _activeJob.value = j
    }

    fun createJob(name: String) = scope.launch {
        val n = name.trim().ifEmpty { return@launch }
        val id = db.jobs().insert(Job(name = n))
        setActiveJob(id)
    }

    fun renameJob(j: Job, name: String) = scope.launch {
        val n = name.trim().ifEmpty { return@launch }
        db.jobs().update(j.copy(name = n))
        if (_activeJob.value?.id == j.id) _activeJob.value = j.copy(name = n)
    }

    /** Smaže zakázku i s body a trasami; aktivní se stane jiná (nebo nová „Výchozí“). */
    fun deleteJob(j: Job) = scope.launch {
        if (_recording.value != null) {
            _messages.tryEmit("Během záznamu nelze mazat lokality")
            return@launch
        }
        // trasy a body se smažou kaskádou, surové soubory tras ručně
        db.tracks().listByJob(j.id).forEach { t -> t.rawFile?.let { rawFile(it).delete() } }
        db.jobs().delete(j.id)
        if (_activeJob.value?.id == j.id) {
            _activeJob.value = null
            prefs.activeJobId = 0
            ensureJob()
        }
    }

    // ------------------------------------------------------------------ body
    fun savePoint(p: Point) = scope.launch {
        val q = PointCoords.complete(p)
        if (q.id == 0L) db.points().insert(q) else db.points().update(q)
    }

    fun deletePoint(p: Point) = scope.launch {
        if (_measure.value?.point?.id == p.id) {
            _messages.tryEmit("Na bodě probíhá měření")
            return@launch
        }
        if (_navTarget.value?.id == p.id) _navTarget.value = null
        // stopy měření bodu (trasy „M …“) se smažou s ním
        db.measurements().byPointList(p.id).forEach { m ->
            m.trackId?.let { deleteTrackFiles(it) }
            m.rawUbx?.let { rawFile(it).delete() }
        }
        db.points().delete(p.id)
    }

    // ------------------------------------------------------------------ navigace k bodu
    private val _navTarget = MutableStateFlow<Point?>(null)
    val navTarget: StateFlow<Point?> = _navTarget.asStateFlow()

    fun navigateTo(p: Point?) {
        _navTarget.value = p
    }

    // ------------------------------------------------------------------ měření bodu
    private val _measure = MutableStateFlow<MeasureState?>(null)
    val measure: StateFlow<MeasureState?> = _measure.asStateFlow()
    private val measEpochs = ArrayList<MeasureEpoch>()
    private var measTimer: kotlinx.coroutines.Job? = null
    @Volatile private var measLastCalc = 0L
    @Volatile private var autoStopping = false
    private var ubxOut: OutputStream? = null       // surová data měření (RAWX/SFRBX)
    private val ubxLock = Any()
    @Volatile private var ubxFrames = 0

    /** Rover s firmwarem, který umí příkaz meas (0.9.0+). */
    private fun roverSupportsMeas(): Boolean {
        val v = _info.value?.fw?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return false
        val (a, b) = (v + listOf(0, 0)).let { it[0] to it[1] }
        return a > 0 || b >= 9
    }

    /** Statický model X20 a volitelně RAWX (FW 0.9.0+). Vrací [statický, raw] potvrzené roverem. */
    private suspend fun roverMeasStart(static: Boolean, raw: Boolean): Pair<Boolean, Boolean> {
        if (!static && !raw) return false to false
        if (!roverSupportsMeas()) {
            _messages.tryEmit("Rover nepodporuje režim měření (FW 0.9.0+) – měří se v modelu z profilu")
            return false to false
        }
        val rr = command(JSONObject().put("cmd", "meas").put("op", "start").put("static", static).put("raw", raw), 3000)
        if (rr == null || !rr.optBoolean("ok")) {
            _messages.tryEmit("Rover na příkaz měření neodpověděl – měří se v modelu z profilu")
            return false to false
        }
        if (!rr.optBoolean("ack", true)) _messages.tryEmit("X20 nepotvrdila nastavení měření")
        return rr.optBoolean("static") to rr.optBoolean("raw")
    }

    private fun startTimer(seconds: Int) {
        if (seconds > 0) measTimer = scope.launch {
            delay(seconds * 1000L)
            stopMeasurement()
        }
    }

    /**
     * Měření kontrolního bodu: záznam trasy „M bod čas“ (všechny epochy, surový proud podle nastavení) a průběžný průměr.
     * @param seconds délka měření, 0 = do ručního ukončení
     */
    fun startMeasurement(p: Point, seconds: Int) = scope.launch {
        if (_measure.value != null) return@launch
        if (link.state.value !is LinkState.Connected) {
            _messages.tryEmit("Rover není připojený")
            return@launch
        }
        if (_recording.value != null) {
            _messages.tryEmit("Nejdřív ukonči záznam trasy")
            return@launch
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        startRecording("M ${p.name} $stamp").join()
        val r = _recording.value ?: return@launch
        val now = System.currentTimeMillis()
        val antH = prefs.antennaH
        val id = db.measurements().insert(
            Measurement(
                pointId = p.id, trackId = r.trackId, startMs = now, plannedSec = seconds, antennaH = antH,
                profile = _sta.value?.profileName ?: "",
            )
        )
        synchronized(measEpochs) { measEpochs.clear() }
        measLastCalc = 0
        autoStopping = false
        val (static, raw) = roverMeasStart(prefs.measStatic, prefs.measRaw)
        var ubxName: String? = null
        if (raw) {
            val name = "meas_$id.ubx"
            try {
                val f = rawFile(name)
                f.parentFile?.mkdirs()
                synchronized(ubxLock) {
                    ubxOut = BufferedOutputStream(FileOutputStream(f), 65536)
                    ubxFrames = 0
                }
                ubxName = name
            } catch (e: IOException) {
                _messages.tryEmit("Soubor surových dat nelze založit: ${e.message}")
            }
        }
        db.measurements().get(id)?.let { db.measurements().update(it.copy(staticModel = static, rawUbx = ubxName)) }
        _measure.value = MeasureState(id, r.trackId, p, now, seconds, antH, staticModel = static, raw = ubxName != null)
        startTimer(seconds)
    }

    /** Návrh názvu dalšího zaměřeného bodu: poslední název s číslem o 1 vyšším (P7 → P8), volný v lokalitě. */
    suspend fun nextSurveyName(): String {
        val j = ensureJob()
        val used = db.points().listByJob(j.id).map { it.name }.toSet()
        var name = prefs.lastSurveyName?.let { incName(it) } ?: "P1"
        var guard = 0
        while (name in used && guard++ < 10_000) name = incName(name)
        return name
    }

    private fun incName(s: String): String {
        val m = Regex("^(.*?)(\\d+)$").find(s) ?: return s + "1"
        val (pre, num) = m.destructured
        val next = (num.toLong() + 1).toString().padStart(num.length, '0')
        return pre + next
    }

    /**
     * Zaměření nového bodu „tady“: průměr nejlepších epoch bez pohybu. Konec, co nastane dřív:
     * σ vodorovně ≤ mez profilu a aspoň N epoch, nebo nejdelší doba; kdykoli ručně (uloží se, co je).
     */
    fun startSurvey(name: String, takeAverage: Boolean = false) = scope.launch {
        if (_measure.value != null) return@launch
        if (link.state.value !is LinkState.Connected) {
            _messages.tryEmit("Rover není připojený")
            return@launch
        }
        val n = name.trim().ifEmpty { return@launch }
        val j = ensureJob()
        if (db.points().listByJob(j.id).any { it.name == n }) {
            _messages.tryEmit("Bod $n v lokalitě už je")
            return@launch
        }
        val u = prefs.user
        // převzetí průběžného průměru: jeho epochy (s odečtenou anténou) a začátek
        var startMs = System.currentTimeMillis()
        val taken = if (takeAverage) synchronized(liveEps) {
            if (liveEps.isNotEmpty()) startMs = liveStartMs
            liveEps.map { it.m.copy(h = it.m.h - u.antennaH) }
        } else emptyList()
        synchronized(measEpochs) {
            measEpochs.clear()
            measEpochs.addAll(taken)
        }
        measLastCalc = 0
        autoStopping = false
        val (static, _) = roverMeasStart(u.measStatic, false)
        prefs.lastSurveyName = n
        _measure.value = MeasureState(
            0, 0, Point(jobId = j.id, name = n), startMs, u.surveyMaxSec, u.antennaH,
            staticModel = static, mode = MeasureMode.SURVEY, sigmaLimit = u.surveySigma, minEpochs = u.surveyMinEpochs,
        )
        // nejdelší doba počítaná od začátku převzatého průměru (aspoň 3 s na doměření)
        val elapsed = ((System.currentTimeMillis() - startMs) / 1000).toInt()
        startTimer(maxOf(u.surveyMaxSec - elapsed, 3))
    }

    /** Vytyčení: krátké průměrování polohy na vytyčovaném bodě, uloží odchylku a pokračuje dalším bodem. */
    fun startStakeCheck(p: Point) = scope.launch {
        if (_measure.value != null) return@launch
        if (link.state.value !is LinkState.Connected) {
            _messages.tryEmit("Rover není připojený")
            return@launch
        }
        val u = prefs.user
        synchronized(measEpochs) { measEpochs.clear() }
        measLastCalc = 0
        autoStopping = false
        _measure.value = MeasureState(0, 0, p, System.currentTimeMillis(), u.stakeSec, u.antennaH, mode = MeasureMode.STAKE)
        startTimer(u.stakeSec)
    }

    /** Konec měření: [save] = false měření zahodí (u kontrolního bodu i s trasou). */
    fun stopMeasurement(save: Boolean = true) = scope.launch {
        val m = _measure.value ?: return@launch
        _measure.value = null
        measTimer?.cancel()  // časovač už mohl doběhnout (sám volá stopMeasurement)
        measTimer = null
        if (m.staticModel || m.raw) command(JSONObject().put("cmd", "meas").put("op", "stop"), 3000)
        closeUbx()
        if (m.mode == MeasureMode.CONTROL) stopRecording().join()
        val eps = synchronized(measEpochs) { ArrayList(measEpochs).also { measEpochs.clear() } }
        if (!save) {
            if (m.mode == MeasureMode.CONTROL) {
                if (m.raw) rawFile("meas_${m.measurementId}.ubx").delete()
                db.measurements().delete(m.measurementId)
                deleteTrackFiles(m.trackId)
            }
            _messages.tryEmit(
                when (m.mode) {
                    MeasureMode.SURVEY -> "Zaměření zrušeno"
                    MeasureMode.STAKE -> "Vytyčení bodu zrušeno"
                    else -> "Měření zrušeno"
                }
            )
            return@launch
        }
        val res = MeasureCalc.compute(eps)
        val mount = (ntrip.state.value as? NtripState.Streaming)?.mount
        val corr = when {
            res.epochs > 0 && res.nRtcm * 2 > res.epochs -> "RTCM" + (mount?.let { " $it" } ?: "")
            res.nHas > 0 -> "HAS"
            else -> "bez korekcí"
        }
        val now = System.currentTimeMillis()
        when (m.mode) {
            MeasureMode.CONTROL -> {
                val point = db.points().get(m.point.id) ?: m.point
                val off = MeasureCalc.offset(res.lat, res.lon, res.h, point)
                val old = db.measurements().get(m.measurementId) ?: return@launch
                db.measurements().update(
                    old.copy(
                        endMs = now, epochs = res.epochs, used = res.used, usedQuality = res.usedQuality,
                        nFix = res.nFix, nFloat = res.nFloat, nHas = res.nHas, nDgnss = res.nDgnss, nOther = res.nOther,
                        corr = corr, lat = res.lat, lon = res.lon, hEll = res.h, sN = res.sN, sE = res.sE, sU = res.sU,
                        dN = off.dN, dE = off.dE, dU = off.dU, dY = off.dY, dX = off.dX, refDerived = off.refDerived, dH = off.dH,
                    )
                )
                _messages.tryEmit(
                    "Měření ${point.name}: ${res.used} epoch" +
                        (off.d2?.let { String.format(Locale.US, ", odchylka %.3f m", it) } ?: "")
                )
            }
            MeasureMode.SURVEY -> {
                if (res.lat == null || res.lon == null) {
                    _messages.tryEmit("Bod ${m.point.name} neuložen – žádná epocha s polohou bez pohybu")
                    return@launch
                }
                val hs = hSigma(res)
                val settled = hs != null && hs <= m.sigmaLimit && res.used >= m.minEpochs
                val qual = Quality.of(res.usedQuality)
                val note = "zaměřeno: ${if (res.usedQuality == 0) "smíšená kvalita" else qual.label}, " +
                    "σ ${hs?.let { String.format(Locale.US, "%.3f m", it) } ?: "–"}, ${res.used} epoch" +
                    (if (settled) "" else ", neustáleno")
                val p = PointCoords.complete(
                    m.point.copy(
                        lat = res.lat, lon = res.lon, hEll = res.h, source = PointSource.MEASURED,
                        quality = if (settled && res.usedQuality == Quality.FIX.code) PointQuality.EXACT else PointQuality.APPROX,
                        kind = "zaměřený", note = note,
                    )
                )
                val pid = db.points().insert(p)
                db.measurements().insert(
                    Measurement(
                        pointId = pid, trackId = null, startMs = m.startMs, endMs = now, plannedSec = m.plannedSec,
                        antennaH = m.antennaH, epochs = res.epochs, used = res.used, usedQuality = res.usedQuality,
                        nFix = res.nFix, nFloat = res.nFloat, nHas = res.nHas, nDgnss = res.nDgnss, nOther = res.nOther,
                        corr = corr, profile = _sta.value?.profileName ?: "", lat = res.lat, lon = res.lon, hEll = res.h,
                        sN = res.sN, sE = res.sE, sU = res.sU, staticModel = m.staticModel, note = "zaměření bodu",
                    )
                )
                _messages.tryEmit("Bod ${p.name} uložen – $note")
            }
            MeasureMode.STAKE -> {
                val point = db.points().get(m.point.id) ?: m.point
                if (res.lat == null || res.lon == null) {
                    _messages.tryEmit("Vytyčení ${point.name} neuloženo – žádná epocha s polohou bez pohybu")
                    return@launch
                }
                val off = MeasureCalc.offset(res.lat, res.lon, res.h, point)
                db.points().update(
                    point.copy(
                        stakeMs = now, stakeLat = res.lat, stakeLon = res.lon, stakeH = res.h,
                        stakeDN = off.dN, stakeDE = off.dE, stakeDH = off.dH ?: off.dU, stakeDY = off.dY, stakeDX = off.dX,
                    )
                )
                _messages.tryEmit(
                    "Vytyčeno ${point.name}" + (off.d2?.let { String.format(Locale.US, ", odchylka %.3f m", it) } ?: "")
                )
                if (_stake.value != null) nextStake()
            }
        }
    }

    private fun hSigma(r: MeasureResult): Double? =
        if (r.sN != null && r.sE != null) kotlin.math.sqrt(r.sN * r.sN + r.sE * r.sE) else null

    fun deleteMeasurement(m: Measurement) = scope.launch {
        if (_measure.value?.measurementId == m.id && m.id != 0L) return@launch
        m.rawUbx?.let { rawFile(it).delete() }
        db.measurements().delete(m.id)
        m.trackId?.let { deleteTrackFiles(it) }
    }

    /** Smaže trasu i se surovým souborem (ne probíhající záznam). */
    private suspend fun deleteTrackFiles(trackId: Long) {
        if (_recording.value?.trackId == trackId) return
        val t = db.tracks().get(trackId) ?: return
        t.rawFile?.let { rawFile(it).delete() }
        db.tracks().delete(t.id)
    }

    private fun closeUbx() {
        synchronized(ubxLock) {
            try {
                ubxOut?.close()
            } catch (_: IOException) {
            }
            ubxOut = null
        }
    }

    /** Rámec UBX z roveru (RXM-RAWX / RXM-SFRBX během měření bodu) do souboru .ubx. */
    private fun onUbx(frame: ByteArray) {
        // asistence X20 (MGA-ACK, výpis databáze MGA-DBD)
        if (frame.size >= 4 && (frame[2].toInt() and 0xFF) == X20Assist.CLS_MGA) {
            assist.onUbx(frame)
            return
        }
        if (ubxOut == null) return
        // jen RXM (RAWX/SFRBX); IMU z roveru (třída 0xF5) zůstává jen v surovém proudu trasy
        if (frame.size < 4 || (frame[2].toInt() and 0xFF) != 0x02) return
        synchronized(ubxLock) {
            try {
                ubxOut?.write(frame)
                ubxFrames++
            } catch (e: IOException) {
                ubxOut = null
                _messages.tryEmit("Zápis surových dat přerušen: ${e.message}")
            }
        }
    }

    /** Epocha do probíhajícího měření (ETRS89, anténa odečtena). */
    private fun measureEpoch(p: Pos) {
        val m = _measure.value ?: return
        if (!p.hasFix || p.timeMs == null) return
        val g = if (p.itrf) Etrs.itrfToEtrs(p.lat!!, p.lon!!, p.hEll ?: 0.0, Etrs.epochYear(p.timeMs))
        else Geodetic(p.lat!!, p.lon!!, p.hEll ?: 0.0)
        synchronized(measEpochs) {
            measEpochs.add(MeasureEpoch(g.lat, g.lon, g.h - m.antennaH, p.quality.code, p.moving, p.rtcm))
        }
        val now = System.currentTimeMillis()
        if (now - measLastCalc < 1000) return
        measLastCalc = now
        val eps = synchronized(measEpochs) { ArrayList(measEpochs) }
        val res = MeasureCalc.compute(eps)
        // compareAndSet: mezitím ukončené měření se nesmí obnovit
        val ok = _measure.compareAndSet(
            m, m.copy(result = res, offset = MeasureCalc.offset(res.lat, res.lon, res.h, m.point), rawFrames = ubxFrames),
        )
        // zaměření bodu: konec po ustálení
        if (ok && m.mode == MeasureMode.SURVEY && !autoStopping && res.used >= m.minEpochs) {
            val hs = hSigma(res)
            if (hs != null && hs <= m.sigmaLimit) {
                autoStopping = true
                stopMeasurement()
            }
        }
    }

    // ------------------------------------------------------------------ průběžný průměr (panel Živě)
    // Nuluje se jen pohybem (IMU, nebo rychlost GNSS nad mezí v několika epochách po sobě) a ručně.
    // Epochy se průběžně ukládají do souboru v telefonu; po startu aplikace se nabídne pokračování.
    private val _liveAvg = MutableStateFlow<LiveAverage?>(null)
    val liveAvg: StateFlow<LiveAverage?> = _liveAvg.asStateFlow()
    private val _liveRestore = MutableStateFlow<LiveRestore?>(null)
    val liveRestore: StateFlow<LiveRestore?> = _liveRestore.asStateFlow()

    /** Epocha průměru: přijatá věta (soubor, trasa), poloha a epocha výpočtu (ETRS89, poloha antény). */
    private class LiveEp(val line: String, val p: Pos, val m: MeasureEpoch)

    private val liveEps = ArrayDeque<LiveEp>()
    private var liveStartMs = 0L
    private var liveLastMs = 0L
    private var liveCalcMs = 0L
    private var liveFastN = 0                 // epochy po sobě s rychlostí GNSS nad mezí
    private var liveResets = 0
    private var liveResetReason: String? = null
    private var liveResetMs = 0L
    private var liveRestoreCheck = false      // po obnovení: první epocha ověří, že rover stojí na stejném místě
    private val livePending = ArrayList<String>()  // řádky k dopsání do souboru
    private var liveFileReset = false         // soubor přepsat (nový průměr)
    private var restoreEps: List<LiveEp>? = null
    private var restoreStartMs = 0L

    private fun liveFile() = File(context.filesDir, "live_avg.txt")

    private fun etrsOf(p: Pos): Geodetic =
        if (p.itrf) Etrs.itrfToEtrs(p.lat!!, p.lon!!, p.hEll ?: 0.0, Etrs.epochYear(p.timeMs!!))
        else Geodetic(p.lat!!, p.lon!!, p.hEll ?: 0.0)

    private fun liveEpOf(line: String, p: Pos): LiveEp {
        val g = etrsOf(p)
        return LiveEp(line, p, MeasureEpoch(g.lat, g.lon, g.h, p.quality.code, false, p.rtcm))
    }

    /**
     * Epochy pro terč: [TargetSource.LIVE] průběžný průměr (poloha antény), [TargetSource.MEASURE] probíhající
     * měření bodu (anténa odečtena). Výška jde jen jako rozdíl od průměru, anténa na tvar nemá vliv.
     */
    fun targetEpochs(source: Int): List<MeasureEpoch> =
        if (source == TargetSource.LIVE) synchronized(liveEps) { liveEps.map { it.m } }
        else synchronized(measEpochs) { ArrayList(measEpochs) }

    /** Ruční vynulování průměru. */
    fun resetLiveAverage() = synchronized(liveEps) { liveReset("ručně") }

    private fun liveReset(reason: String?) {
        liveEps.clear()
        liveStartMs = 0
        liveRestoreCheck = false
        if (reason != null) {
            liveResets++
            liveResetReason = reason
            liveResetMs = System.currentTimeMillis()
        }
        liveFileReset = true
        livePending.clear()
        _liveAvg.value = null
    }

    private fun publishLive() {
        _liveAvg.value = LiveAverage(
            liveStartMs, liveLastMs, liveEps.size, MeasureCalc.compute(liveEps.map { it.m }),
            liveResets, liveResetReason, liveResetMs,
        )
    }

    /** Epocha do průběžného průměru ([line] = přijatá věta $PRVR,POS). */
    private fun liveEpoch(p: Pos, line: String) {
        if (!p.hasFix || p.timeMs == null) return
        val now = System.currentTimeMillis()
        synchronized(liveEps) {
            liveFastN = if (p.speed > LIVE_SPEED) liveFastN + 1 else 0
            if (p.moving || liveFastN >= LIVE_FAST_EPOCHS) {
                if (liveEps.isNotEmpty()) liveReset(if (p.moving) "pohyb (IMU)" else "pohyb (rychlost GNSS)")
                return
            }
            val ep = liveEpOf(line, p)
            if (liveRestoreCheck) {
                // obnovený průměr: rover musí stát na stejném místě
                liveRestoreCheck = false
                val r = _liveAvg.value?.result
                if (r?.lat != null && r.lon != null) {
                    val (kN, kE) = MeasureCalc.metersPerDeg(r.lat)
                    val d = kotlin.math.hypot((ep.m.lat - r.lat) * kN, (ep.m.lon - r.lon) * kE)
                    val hs = if (r.sN != null && r.sE != null) kotlin.math.hypot(r.sN, r.sE) else 0.0
                    if (d > maxOf(3 * hs, 4 * p.hAcc, 0.5)) {
                        liveReset(String.format(Locale.US, "po obnovení jiné místo (%.1f m)", d))
                    }
                }
            }
            if (liveEps.isEmpty()) {
                liveStartMs = now
                liveCalcMs = 0
                liveFileReset = true
                livePending.clear()
            }
            liveEps.addLast(ep)
            livePending.add("${p.rxMs}|$line")
            while (liveEps.size > LIVE_MAX) liveEps.removeFirst()
            liveLastMs = now
            val period = if (liveEps.size > 5000) 5000L else 1000L
            if (now - liveCalcMs < period && _liveAvg.value != null) return
            liveCalcMs = now
            publishLive()
        }
    }

    /** Dopíše nové epochy průměru do souboru (každých 5 s; při čekající nabídce obnovení nic). */
    private fun flushLiveFile() {
        if (_liveRestore.value != null) return
        val lines: List<String>
        val reset: Boolean
        val start: Long
        val empty: Boolean
        synchronized(liveEps) {
            lines = ArrayList(livePending)
            livePending.clear()
            reset = liveFileReset
            liveFileReset = false
            start = liveStartMs
            empty = liveEps.isEmpty()
        }
        try {
            val f = liveFile()
            if (reset) {
                if (empty && lines.isEmpty()) {
                    f.delete()
                    return
                }
                f.writeText("#v1|$start\n")
            }
            if (lines.isNotEmpty() && (reset || f.exists())) f.appendText(lines.joinToString("\n", postfix = "\n"))
        } catch (e: IOException) {
            Log.w("RoverController", "průměr do souboru: ${e.message}")
        }
    }

    /** Po startu aplikace: načte uložený průměr a nabídne ho ([liveRestore]). */
    private fun loadLiveFile() {
        val f = liveFile()
        if (!f.exists()) return
        var start = 0L
        val list = ArrayList<LiveEp>()
        try {
            f.forEachLine { ln ->
                if (ln.startsWith("#v1|")) {
                    start = ln.substring(4).toLongOrNull() ?: 0L
                    return@forEachLine
                }
                val i = ln.indexOf('|')
                if (i <= 0) return@forEachLine
                val rx = ln.substring(0, i).toLongOrNull() ?: return@forEachLine
                val line = ln.substring(i + 1)
                val body = RoverProtocol.checkedBody(line) ?: return@forEachLine
                val p = RoverProtocol.parsePos(body.split(','))?.copy(rxMs = rx) ?: return@forEachLine
                if (!p.hasFix || p.timeMs == null) return@forEachLine
                list.add(liveEpOf(line, p))
            }
        } catch (e: IOException) {
            return
        }
        if (list.isEmpty()) {
            f.delete()
            return
        }
        val eps = if (list.size > LIVE_MAX) list.subList(list.size - LIVE_MAX, list.size).toList() else list
        restoreEps = eps
        restoreStartMs = if (start > 0) start else eps.first().p.rxMs
        _liveRestore.value = LiveRestore(restoreStartMs, eps.last().p.rxMs, eps.size)
    }

    object RestoreAction {
        const val CONTINUE = 0
        const val DELETE = 1
        const val SAVE_TRACK = 2
    }

    /** Odpověď na nabídku obnovení průměru. */
    fun restoreLive(action: Int) = scope.launch {
        val eps = restoreEps ?: return@launch
        restoreEps = null
        when (action) {
            RestoreAction.CONTINUE -> {
                val all: List<LiveEp>
                synchronized(liveEps) {
                    // mezitím přijaté epochy jdou za obnovené
                    val current = liveEps.toList()
                    liveEps.clear()
                    liveEps.addAll(eps)
                    liveEps.addAll(current)
                    while (liveEps.size > LIVE_MAX) liveEps.removeFirst()
                    liveStartMs = restoreStartMs
                    liveLastMs = liveEps.last().p.rxMs
                    liveRestoreCheck = current.isEmpty()
                    liveFileReset = false
                    livePending.clear()
                    all = liveEps.toList()
                    publishLive()
                }
                try {
                    liveFile().writeText(
                        "#v1|$restoreStartMs\n" + all.joinToString("\n", postfix = "\n") { "${it.p.rxMs}|${it.line}" }
                    )
                } catch (_: IOException) {
                }
                _messages.tryEmit("Průměr obnoven (${eps.size} epoch)")
            }
            RestoreAction.SAVE_TRACK -> {
                val id = trackFromEpochs(null, eps)
                synchronized(liveEps) { liveFileReset = true }
                _messages.tryEmit("Uložený průměr uložen jako trasa ($id)")
            }
            else -> {
                synchronized(liveEps) { liveFileReset = true }
                _messages.tryEmit("Uložený průměr smazán")
            }
        }
        _liveRestore.value = null
        flushLiveFile()
    }

    private fun trackPointOf(trackId: Long, p: Pos) = TrackPoint(
        trackId = trackId, timeMs = p.timeMs!!, lat = p.lat!!, lon = p.lon!!,
        hEll = p.hEll ?: 0.0, hMsl = p.hMsl ?: 0.0, sN = p.sN, sE = p.sE, sU = p.sU,
        hAcc = p.hAcc, vAcc = p.vAcc, quality = p.quality.code, carrSoln = p.carrSoln, itrf = p.itrf,
        numSv = p.numSv, pdop = p.pdop, speed = p.speed, heading = p.heading, moving = p.moving,
        corrAge = p.corrAge, rtcm = p.rtcm, hp = p.highPrecision, rxMs = p.rxMs,
    )

    /** Uloží epochy průměru jako ukončenou trasu (název prázdný = „T číslo datum čas (průměr)“). Vrací název. */
    private suspend fun trackFromEpochs(name: String?, eps: List<LiveEp>): String {
        val job = ensureJob()
        val s0 = eps.first().p.rxMs
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(s0))
        val id = db.tracks().insert(
            Track(jobId = job.id, name = name ?: stamp, startMs = s0, antennaH = prefs.antennaH, rateHz = _info.value?.rateHz ?: 0)
        )
        val n = name ?: "T$id $stamp (průměr)"
        eps.chunked(2000).forEach { c -> db.tracks().insertPoints(c.map { trackPointOf(id, it.p) }) }
        db.tracks().get(id)?.let { db.tracks().update(it.copy(name = n, endMs = eps.last().p.rxMs, points = eps.size)) }
        return n
    }

    // ------------------------------------------------------------------ zásobník roveru (výpadek BT)
    // Při záznamu trasy nebo měření bodu rover při výpadku BT ukládá epochy (FW 0.10.0+). Po připojení je pošle
    // ($PRVR,BUF); berou se jen epochy z mezer v živých datech (rover posílá i pár epoch před výpadkem).
    private val gaps = ArrayList<LongArray>()        // [od, do] času GNSS; do = Long.MAX_VALUE = mezera trvá
    private val bufSeen = HashSet<Long>()
    @Volatile private var lastLiveGnssMs = 0L
    @Volatile private var bufAccepted = 0
    @Volatile private var roverRecSent: Boolean? = null

    private fun roverRecWanted() = _recording.value != null || _measure.value != null

    /** Živá epocha: uzavře otevřenou mezeru. */
    private fun liveGnss(t: Long) {
        synchronized(gaps) {
            gaps.lastOrNull()?.let { g -> if (g[1] == Long.MAX_VALUE) g[1] = t }
        }
        lastLiveGnssMs = t
    }

    /** Výpadek spojení při záznamu: otevře mezeru od poslední živé epochy. */
    private fun linkLost() {
        if (!roverRecWanted() || lastLiveGnssMs == 0L) return
        synchronized(gaps) {
            if (gaps.lastOrNull()?.get(1) != Long.MAX_VALUE) gaps.add(longArrayOf(lastLiveGnssMs, Long.MAX_VALUE))
        }
    }

    /** Rover má / nemá ukládat epochy při výpadku (záznam trasy nebo měření bodu). */
    private suspend fun syncRoverRec(force: Boolean = false) {
        val want = roverRecWanted()
        if (!want) synchronized(gaps) {
            gaps.clear()
            bufSeen.clear()
        }
        if (link.state.value !is LinkState.Connected) return
        if (!force && roverRecSent == want) return
        val r = command(JSONObject().put("cmd", "rec").put("op", if (want) "start" else "stop"), 3000)
        if (r != null && r.optBoolean("ok")) roverRecSent = want
    }

    /** Po připojení: znovu nastavit záznam v roveru a vyžádat uložené epochy. */
    private suspend fun fetchRoverBuffer() {
        syncRoverRec(force = true)
        if (!roverRecWanted()) return
        bufAccepted = 0
        val r = command(JSONObject().put("cmd", "buf").put("op", "get"), 3000) ?: return
        val n = r.optInt("n")
        if (r.optBoolean("ok") && n > 0) _messages.tryEmit("Rover posílá $n epoch z výpadku spojení…")
    }

    private fun onBufEpoch(p: Pos) {
        val t = p.timeMs ?: return
        if (!roverRecWanted()) return
        val ok = synchronized(gaps) {
            gaps.any { t > it[0] && t < it[1] } && bufSeen.add(t)
        }
        if (!ok) return
        bufAccepted++
        record(p)
        val m = _measure.value
        if (m != null && t >= m.startMs - 2000) measureEpoch(p)
    }

    private fun onBufEnd() {
        val n = bufAccepted
        if (n > 0) _messages.tryEmit("Doplněno $n epoch z paměti roveru")
        scope.launch { command(JSONObject().put("cmd", "buf").put("op", "clear"), 3000) }
    }

    // ------------------------------------------------------------------ vytyčování
    /** Vytyčování: body v seznamu, pořadí 0 = podle názvu, 1 = nejbližší další; přeskočené body. */
    data class StakeSession(val ids: List<Long>, val order: Int, val skipped: Set<Long> = emptySet())

    private val _stake = MutableStateFlow<StakeSession?>(null)
    val stake: StateFlow<StakeSession?> = _stake.asStateFlow()

    /** Spustí vytyčování vybraných bodů (nevytyčené, se souřadnicemi). */
    fun startStake(points: List<Point>, order: Int) = scope.launch {
        val ids = points.filter { it.lat != null && it.lon != null }.map { it.id }
        if (ids.isEmpty()) {
            _messages.tryEmit("Žádný bod k vytyčení")
            return@launch
        }
        prefs.stakeOrder = order
        _stake.value = StakeSession(ids, order)
        nextStake()
    }

    fun stopStake() {
        _stake.value = null
        _navTarget.value = null
    }

    /** Přeskočit aktuální bod (zůstane nevytyčený). */
    fun skipStake() = scope.launch {
        val s = _stake.value ?: return@launch
        val cur = _navTarget.value?.id ?: return@launch
        _stake.value = s.copy(skipped = s.skipped + cur)
        nextStake()
    }

    private suspend fun nextStake() {
        val s = _stake.value ?: return
        val left = s.ids.mapNotNull { db.points().get(it) }
            .filter { it.stakeMs == null && it.lat != null && it.lon != null && it.id !in s.skipped }
        if (left.isEmpty()) {
            _messages.tryEmit(if (s.skipped.isEmpty()) "Vytyčování dokončeno" else "Vytyčování dokončeno, přeskočeno ${s.skipped.size}")
            stopStake()
            return
        }
        val here = _pos.value?.takeIf { it.hasFix }
        val next = if (s.order == 1 && here != null) {
            left.minBy { Angles.distanceBearing(here.lat!!, here.lon!!, it.lat!!, it.lon!!).first }
        } else {
            left.sortedWith(NaturalOrder).first()
        }
        _navTarget.value = next
    }

    /** Řazení názvů bodů s čísly (P2 < P10). */
    object NaturalOrder : Comparator<Point> {
        private val re = Regex("(\\d+)|(\\D+)")
        override fun compare(a: Point, b: Point): Int {
            val pa = re.findAll(a.name).map { it.value }.toList()
            val pb = re.findAll(b.name).map { it.value }.toList()
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    x.toBigInteger().compareTo(y.toBigInteger()).let { if (it != 0) it else x.length - y.length }
                } else x.compareTo(y, ignoreCase = true)
                if (c != 0) return c
            }
            return pa.size - pb.size
        }
    }

    /** Výběr bodu do seznamu vytyčování. */
    fun setStakeSel(p: Point, sel: Boolean) = scope.launch {
        db.points().get(p.id)?.let { db.points().update(it.copy(stakeSel = sel)) }
    }

    fun setStakeSelAll(points: List<Point>, sel: Boolean) = scope.launch {
        points.forEach { p -> db.points().get(p.id)?.let { db.points().update(it.copy(stakeSel = sel)) } }
    }

    /** Vynuluje výsledek vytyčení bodů (vytyčit znovu). */
    fun clearStake(points: List<Point>) = scope.launch {
        points.forEach { p ->
            db.points().get(p.id)?.let {
                db.points().update(
                    it.copy(
                        stakeMs = null, stakeLat = null, stakeLon = null, stakeH = null, stakeDN = null, stakeDE = null,
                        stakeDH = null, stakeDY = null, stakeDX = null,
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------ uživatelské profily
    private val _user = MutableStateFlow(prefs.user)
    val user: StateFlow<UserProfile> = _user.asStateFlow()
    private val _users = MutableStateFlow(prefs.userProfiles)
    val users: StateFlow<List<UserProfile>> = _users.asStateFlow()

    /** Názvy profilů roveru (z příkazu get profiles po připojení). */
    private val _roverProfiles = MutableStateFlow<List<String>>(emptyList())
    val roverProfiles: StateFlow<List<String>> = _roverProfiles.asStateFlow()

    private fun refreshUsers() {
        _users.value = prefs.userProfiles
        _user.value = prefs.user
    }

    /** Výběr uživatelského profilu; přepne i profil roveru, když ho profil určuje. */
    fun selectUser(i: Int) {
        val l = prefs.userProfiles
        if (i !in l.indices) return
        prefs.userIndex = i
        refreshUsers()
        applyUserRoverProfile()
    }

    fun updateUser(p: UserProfile) {
        prefs.updateUser { p }
        refreshUsers()
    }

    /** Nový profil jako kopie aktivního. */
    fun addUser(name: String) {
        val n = name.trim().ifEmpty { return }
        val l = prefs.userProfiles + prefs.user.copy(name = n)
        prefs.userProfiles = l
        prefs.userIndex = l.size - 1
        refreshUsers()
    }

    fun deleteUser(i: Int) {
        val l = prefs.userProfiles.toMutableList()
        if (l.size <= 1 || i !in l.indices) return
        l.removeAt(i)
        prefs.userProfiles = l
        prefs.userIndex = prefs.userIndex.let { if (it >= l.size) l.size - 1 else if (it > i) it - 1 else it }
        refreshUsers()
    }

    private fun applyUserRoverProfile() {
        val rp = prefs.user.roverProfile
        if (rp < 0 || link.state.value !is LinkState.Connected) return
        if (_info.value?.profileIndex == rp) return
        setProfile(rp)
    }

    private fun requestRoverProfiles() = scope.launch {
        val r = command(JSONObject().put("cmd", "get").put("what", "profiles")) ?: return@launch
        val a = r.optJSONObject("data")?.optJSONArray("profiles") ?: return@launch
        _roverProfiles.value = (0 until a.length()).map { a.getJSONObject(it).optString("name", "Profil ${it + 1}") }
    }

    fun takeCuzkPoint(c: CuzkPoint) = scope.launch {
        val j = ensureJob()
        db.points().insert(c.toPoint(j.id))
        _messages.tryEmit("Bod ${c.name} převzat do lokality ${j.name}")
    }

    init {
        // GNSS telefonu: polohy do porovnání a testu doby do fixu; zdroje zapnuté minule
        phone.onFix = { f ->
            phoneCompare.onPhone(f)
            phoneTtff.onFix(f)
        }
        phone.setAll(prefs.phoneSources.mapNotNull { PhoneSource.of(it) }.toSet())
        scope.launch { ensureJob() }
        // Po připojení zjistit, co je na druhé straně
        scope.launch {
            link.state.collect { st ->
                if (st is LinkState.Connected) {
                    delay(300)
                    requestInfo().join()
                    fetchRoverBuffer()
                } else {
                    linkLost()
                    assist.onDisconnected()
                    roverRecSent = null
                    if (st is LinkState.Idle) _info.value = null
                }
            }
        }
        // Služba na popředí běží, dokud je něco aktivní
        scope.launch {
            combine(link.state, ntrip.state, _recording, phone.enabled) { l, n, r, ph ->
                (l !is LinkState.Idle && l !is LinkState.Failed) || n !is NtripState.Off || r != null || ph.isNotEmpty()
            }.distinctUntilChanged().collect { active ->
                if (active) RoverService.start(context) else RoverService.stop(context)
            }
        }
        // Po zapnutí roveru: doba od zapnutí do fixu a korekcí (FW 0.13.0+) – dotaz, dokud nejsou známé
        scope.launch {
            while (true) {
                delay(10_000)
                val i = _info.value ?: continue
                val b = i.boot ?: continue
                if (link.state.value !is LinkState.Connected || i.resetReason != 1) continue
                if ((i.upMs ?: Long.MAX_VALUE) > 30 * 60_000L) continue
                if (b.fix != null && b.corr != null) continue
                refreshBootTimes()
            }
        }
        // Kvalita spojení: přepočet i bez nových dat (stárnutí)
        scope.launch {
            while (true) {
                delay(500)
                updateLinkQuality()
            }
        }
        scope.launch {
            link.state.collect { st ->
                if (st is LinkState.Connected) synchronized(epochTimes) {
                    epochTimes.clear()
                    epochLatency.clear()
                    lastEpochMs = 0
                    lostTotal = 0
                    lastPosRxMs = 0
                }
            }
        }
        // Záznam trasy / měření bodu → rover ukládá epochy při výpadku BT
        scope.launch {
            combine(_recording, _measure) { r, m -> r != null || m != null }
                .distinctUntilChanged().collect { syncRoverRec() }
        }
        // Průběžný průměr: nabídka uloženého z minula a průběžné ukládání do souboru
        scope.launch(Dispatchers.IO) {
            loadLiveFile()
            while (true) {
                delay(5000)
                flushLiveFile()
            }
        }
        // Zápis bodů trasy do databáze po dávkách
        scope.launch {
            while (true) {
                delay(1000)
                flushRecording()
            }
        }
    }

    // ------------------------------------------------------------------ ovládání
    fun connect(address: String) {
        prefs.roverAddress = address
        link.connect(address)
    }

    fun disconnect() {
        ntrip.stop()
        link.disconnect()
    }

    fun startNtrip(p: NtripProfile) = ntrip.start(p)
    fun stopNtrip() = ntrip.stop()

    /** Odešle příkaz JSON a počká na odpověď (null = bez odpovědi do [timeoutMs]). */
    suspend fun command(cmd: JSONObject, timeoutMs: Long = 3000): JSONObject? {
        if (link.state.value !is LinkState.Connected) return null
        val id = nextId.getAndIncrement()
        cmd.put("id", id)
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        link.send((cmd.toString() + "\n").toByteArray(Charsets.UTF_8))
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        pending.remove(id)
        return r
    }

    fun requestInfo() = scope.launch {
        val r = command(JSONObject().put("cmd", "get").put("what", "info")) ?: run {
            _messages.tryEmit("Rover neodpovídá na příkazy – je ve firmwaru protokol (0.8.0+)?")
            return@launch
        }
        if (!r.optBoolean("ok")) return@launch
        val x = r.optJSONObject("x20")
        val p = r.optJSONObject("prof")
        _info.value = RoverInfo(
            fw = r.optString("fw"),
            proto = r.optInt("proto"),
            name = r.optString("name"),
            x20 = x?.optString("mod")?.takeIf { it.isNotEmpty() },
            x20Fw = x?.optString("fw")?.takeIf { it.isNotEmpty() },
            imu = r.optBoolean("imu"),
            profileIndex = p?.optInt("i") ?: 0,
            profileName = p?.optString("name") ?: "",
            rateHz = p?.optInt("hz") ?: 0,
            caps = r.optString("caps").split(',').filter { it.isNotEmpty() }.toSet(),
            boot = r.optJSONObject("boot")?.let { b ->
                fun t(k: String) = b.optLong(k, -1).takeIf { it >= 0 }
                BootTimes(t("fix"), t("corr"), t("float"), t("fixRtk"))
            },
            upMs = if (r.has("upMs")) r.optLong("upMs") else null,
            resetReason = if (r.has("reset")) r.optInt("reset") else null,
        )
        _info.value?.let { roverTtff.onInfo(it) }
        requestRoverProfiles()
        applyUserRoverProfile()
        assist.onInfo(r.optString("fw"))
    }

    /** Jen doby od zapnutí do fixu (bez načítání profilů a asistence jako [requestInfo]). */
    private suspend fun refreshBootTimes() {
        val r = command(JSONObject().put("cmd", "get").put("what", "info")) ?: return
        val b = r.optJSONObject("boot") ?: return
        fun t(k: String) = b.optLong(k, -1).takeIf { it >= 0 }
        val i = _info.value ?: return
        _info.value = i.copy(
            boot = BootTimes(t("fix"), t("corr"), t("float"), t("fixRtk")),
            upMs = if (r.has("upMs")) r.optLong("upMs") else i.upMs,
        )
        _info.value?.let { roverTtff.onInfo(it) }
    }

    fun setProfile(i: Int) = scope.launch {
        val r = command(JSONObject().put("cmd", "profile").put("i", i), 5000)
        if (r == null || !r.optBoolean("ok")) _messages.tryEmit("Přepnutí profilu selhalo: ${r?.optString("err") ?: "bez odpovědi"}")
        requestInfo()
    }

    fun resetX20(kind: String) = scope.launch {
        val r = command(JSONObject().put("cmd", "x20").put("op", "reset").put("kind", kind))
        _messages.tryEmit(if (r?.optBoolean("ok") == true) "X20: restart ($kind)" else "X20: restart selhal")
    }

    /** Restart příjmu X20 pro test doby do fixu (true = rover potvrdil). */
    suspend fun resetX20Now(kind: String): Boolean =
        command(JSONObject().put("cmd", "x20").put("op", "reset").put("kind", kind))?.optBoolean("ok") == true

    // ------------------------------------------------------------------ nastavení roveru (FW 0.13.0+)
    /** Profily roveru celé: {"active":0,"default":0,"max":10,"profiles":[…]} (null = bez odpovědi). */
    suspend fun roverProfilesJson(): JSONObject? =
        command(JSONObject().put("cmd", "get").put("what", "profiles"))?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")

    /** Uloží profily do roveru; vrací null = OK, jinak chybu. */
    suspend fun saveRoverProfiles(data: JSONObject): String? {
        val r = command(JSONObject().put("cmd", "set").put("what", "profiles").put("data", data), 8000)
            ?: return "rover neodpovídá"
        if (!r.optBoolean("ok")) return r.optString("err", "chyba")
        requestInfo()
        return null
    }

    /** Nastavení přístroje: {"btName":"…"} + výchozí název. */
    suspend fun roverDevice(): JSONObject? = command(JSONObject().put("cmd", "get").put("what", "device"))?.takeIf { it.optBoolean("ok") }

    /** Název BT (prázdný = výchozí ROVER-xxxx); platí po restartu roveru. Vrací null = OK, jinak chybu. */
    suspend fun saveRoverDevice(btName: String): String? {
        val r = command(JSONObject().put("cmd", "set").put("what", "device").put("data", JSONObject().put("btName", btName)))
            ?: return "rover neodpovídá"
        return if (r.optBoolean("ok")) null else r.optString("err", "chyba")
    }

    /** Kalibrace: {"gyro":[x,y,z],"div":…,"bat":…,"batOk":…}. */
    suspend fun roverCal(): JSONObject? = command(JSONObject().put("cmd", "get").put("what", "cal"))?.takeIf { it.optBoolean("ok") }

    /** Kalibrace baterie změřeným napětím [V] nebo gyra ([volts] = null). Odpověď roveru (ok, err, nové hodnoty). */
    suspend fun calibrateRover(volts: Double?): JSONObject? {
        val c = JSONObject().put("cmd", "cal")
        if (volts != null) c.put("what", "battery").put("v", volts) else c.put("what", "gyro")
        return command(c, 8000)
    }

    /** Průchozí režim USB <-> X20 (u-center na PC přes USB roveru). */
    fun roverPassthrough(on: Boolean) = scope.launch {
        val r = command(JSONObject().put("cmd", "passthrough").put("op", if (on) "start" else "stop"))
        _messages.tryEmit(
            when {
                r == null -> "Rover neodpovídá"
                !r.optBoolean("ok") -> "Průchozí režim: ${r.optString("err")}"
                on -> "Průchozí režim zapnut – u-center na PC přes USB roveru, konec tlačítkem nebo zde"
                else -> "Průchozí režim ukončen, X20 se znovu nastavuje"
            }
        )
    }

    /** Restart ESP roveru (X20 běží dál a po startu dostane znovu konfiguraci). */
    fun restartRover() = scope.launch {
        val r = command(JSONObject().put("cmd", "restart"))
        _messages.tryEmit(if (r?.optBoolean("ok") == true) "Rover se restartuje…" else "Rover neodpovídá")
    }

    // ------------------------------------------------------------------ testy (záložka Test)
    /** Test doby do fixu roveru (restart X20) a po zapnutí roveru. */
    val roverTtff = RoverTtff(context, scope, prefs, reset = ::resetX20Now, message = { _messages.tryEmit(it) })
    /** Řízené testovací záznamy pro EKF (scénáře, značky, metadata). */
    val test = TestSession(context, scope, this)

    // ------------------------------------------------------------------ záznam trasy
    /** Záznam trasy; [includeAverage] = trasa začne epochami běžícího průměru (od jeho začátku). */
    fun startRecording(name: String? = null, includeAverage: Boolean = false) = scope.launch {
        startRecordingNow(name, includeAverage)
    }

    /**
     * Spustí záznam a vrátí id trasy (null = záznam už běží). [forceRaw] = surový proud i bez volby
     * v uživatelském profilu (test – podklad pro EKF).
     */
    suspend fun startRecordingNow(name: String? = null, includeAverage: Boolean = false, forceRaw: Boolean = false): Long? {
        if (_recording.value != null) return null
        val job = ensureJob()
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
        val id = db.tracks().insert(
            Track(jobId = job.id, name = name ?: stamp, startMs = now, antennaH = prefs.antennaH, rateHz = _info.value?.rateHz ?: 0)
        )
        // výchozí název: číslo trasy a čas na sekundy (jednoznačný), lze přejmenovat
        val n = name ?: "T$id $stamp"
        db.tracks().get(id)?.let { db.tracks().update(it.copy(name = n)) }
        if (prefs.recordRaw || forceRaw) {
            val name = "track_$id.log"
            try {
                val f = rawFile(name)
                f.parentFile?.mkdirs()
                synchronized(rawLock) { rawOut = BufferedOutputStream(FileOutputStream(f), 65536) }
                db.tracks().get(id)?.let { db.tracks().update(it.copy(rawFile = name)) }
            } catch (e: IOException) {
                _messages.tryEmit("Surový záznam nelze založit: ${e.message}")
            }
        }
        recCounter = 0
        var startMs = now
        var pre = 0
        if (includeAverage) {
            val eps = synchronized(liveEps) { liveEps.toList() }
            if (eps.isNotEmpty()) {
                eps.chunked(2000).forEach { c -> db.tracks().insertPoints(c.map { trackPointOf(id, it.p) }) }
                startMs = eps.first().p.rxMs
                pre = eps.size
                db.tracks().get(id)?.let { db.tracks().update(it.copy(startMs = startMs)) }
            }
        }
        _recording.value = RecordingState(id, n, pre, startMs)
        return id
    }

    fun stopRecording() = scope.launch {
        val r = _recording.value ?: return@launch
        // trasu měření bodu ukončuje jen konec měření (stopMeasurement nejdřív zruší _measure)
        if (_measure.value != null) {
            _messages.tryEmit("Probíhá měření bodu – ukonči ho v pruhu měření")
            return@launch
        }
        _recording.value = null
        flushRecording(r.trackId)
        synchronized(rawLock) {
            try {
                rawOut?.close()
            } catch (_: IOException) {
            }
            rawOut = null
        }
        val t = db.tracks().get(r.trackId) ?: return@launch
        db.tracks().update(t.copy(endMs = System.currentTimeMillis(), points = db.tracks().pointCount(r.trackId)))
    }

    fun renameTrack(t: Track, name: String) = scope.launch {
        val n = name.trim()
        if (n.isEmpty()) return@launch
        db.tracks().get(t.id)?.let { db.tracks().update(it.copy(name = n)) }
        _recording.value?.let { if (it.trackId == t.id) _recording.value = it.copy(name = n) }
    }

    fun rawFile(name: String): File = File(File(context.filesDir, "raw"), name)

    fun deleteTrack(t: Track) = scope.launch {
        if (_recording.value?.trackId == t.id) return@launch
        t.rawFile?.let { rawFile(it).delete() }
        db.tracks().delete(t.id)
    }

    private suspend fun flushRecording(trackId: Long? = _recording.value?.trackId) {
        val batch: List<TrackPoint>
        val diag: List<TrackDiag>
        synchronized(recBuffer) {
            batch = ArrayList(recBuffer)
            recBuffer.clear()
            diag = ArrayList(diagBuffer)
            diagBuffer.clear()
        }
        if (batch.isNotEmpty()) db.tracks().insertPoints(batch)
        if (diag.isNotEmpty()) db.tracks().insertDiag(diag)
        if (trackId != null) synchronized(rawLock) {
            try {
                rawOut?.flush()
            } catch (_: IOException) {
            }
        }
    }

    private fun rawWrite(buf: ByteArray, n: Int) {
        if (rawOut == null) return
        synchronized(rawLock) {
            try {
                rawOut?.write(buf, 0, n)
            } catch (e: IOException) {
                rawOut = null
                _messages.tryEmit("Surový záznam přerušen: ${e.message}")
            }
        }
    }

    /** Diagnostika 1 Hz – volá se po každé větě STA. */
    private fun recordDiag(s: Sta) {
        val r = _recording.value ?: return
        val sats = _sats.value
        val used = sats.filter { it.used }
        val now = System.currentTimeMillis()
        val nt = ntrip.state.value
        val lq = _linkQuality.value
        val d = TrackDiag(
            trackId = r.trackId, rxMs = now, gnssMs = _pos.value?.timeMs,
            batV = s.batV, profile = s.profile, rtcmOk = s.rtcmOk, rtcmBad = s.rtcmBad, rtcmAge = s.rtcmAge,
            rtcmType = s.rtcmType, ubxErr = s.ubxErr, freeHeap = s.freeHeap, btDropped = s.btDropped,
            satTracked = sats.size, satUsed = used.size,
            usedGps = used.count { it.gnssId == 0 }, usedGal = used.count { it.gnssId == 2 },
            usedBds = used.count { it.gnssId == 3 }, usedGlo = used.count { it.gnssId == 6 },
            usedOther = used.count { it.gnssId !in setOf(0, 2, 3, 6) },
            cnoMeanUsed = if (used.isEmpty()) null else used.map { it.cno }.average(),
            cnoMax = sats.maxOfOrNull { it.cno } ?: 0,
            btRxBytes = link.rxBytes.get(), btTxBytes = link.txBytes.get(), btTxDropped = link.txDropped.get(),
            btReconnects = link.reconnects.get(), badChecksum = badChecksum.get(), droppedLines = parser.droppedLines,
            ntripState = when (nt) {
                NtripState.Off -> "off"
                is NtripState.Connecting -> "connecting"
                is NtripState.Streaming -> "streaming"
                is NtripState.Failed -> "failed"
            },
            ntripBytes = ntrip.bytes.get(), ntripFrames = rtcmFramer.frames, ntripBadCrc = rtcmFramer.badCrc,
            ntripAge = if (ntrip.lastDataMs > 0) (now - ntrip.lastDataMs) / 1000.0 else null,
            foreground = foreground, network = NetInfo.type(context), netRxBytes = NetInfo.rxBytes(),
            linkBars = lq.bars, latencyMs = lq.latencyMs, lossPct = lq.lossPct, lostTotal = lq.lostTotal,
            btBacklog = s.btBacklog, hpCount = s.hpCount, appMode = s.appMode,
        )
        synchronized(recBuffer) { diagBuffer.add(d) }
    }

    private fun record(p: Pos) {
        val r = _recording.value ?: return
        if (!p.hasFix || p.timeMs == null) return
        recCounter++
        // měření bodu a testovací záznam ukládají každou epochu
        if (_measure.value == null && test.run.value == null && recCounter % prefs.recordEvery.coerceAtLeast(1) != 0) return
        val tp = TrackPoint(
            trackId = r.trackId, timeMs = p.timeMs, lat = p.lat!!, lon = p.lon!!,
            hEll = p.hEll ?: 0.0, hMsl = p.hMsl ?: 0.0, sN = p.sN, sE = p.sE, sU = p.sU,
            hAcc = p.hAcc, vAcc = p.vAcc, quality = p.quality.code, carrSoln = p.carrSoln, itrf = p.itrf,
            numSv = p.numSv, pdop = p.pdop, speed = p.speed, heading = p.heading, moving = p.moving,
            corrAge = p.corrAge, rtcm = p.rtcm, hp = p.highPrecision, rxMs = p.rxMs,
        )
        synchronized(recBuffer) { recBuffer.add(tp) }
        _recording.value = r.copy(points = r.points + 1)
    }

    // ------------------------------------------------------------------ příjem
    private var lastTrailMs = 0L

    private fun onLine(line: String) {
        when {
            line.startsWith("\$PRVR,") -> onPrvr(line)
            line.startsWith("{") -> onJson(line)
            line.length > 6 && line.startsWith("$") && line.regionMatches(3, "GGA", 0, 3) -> {
                lastGga = line
                lastGgaMs = System.currentTimeMillis()
            }
        }
    }

    private fun onPrvr(line: String) {
        val body = RoverProtocol.checkedBody(line) ?: run {
            badChecksum.incrementAndGet()
            return
        }
        val f = body.split(',')
        when (f.getOrNull(1)) {
            "POS" -> RoverProtocol.parsePos(f)?.let { p ->
                p.timeMs?.let { liveGnss(it) }
                _pos.value = p
                onEpoch(p)
                record(p)
                measureEpoch(p)
                liveEpoch(p, line)
                phoneCompare.onRover(p)
                assist.onPos(p)
                roverTtff.onPos(p)
                val now = System.currentTimeMillis()
                if (p.hasFix && now - lastTrailMs >= 1000) {
                    lastTrailMs = now
                    val g = if (p.itrf && p.timeMs != null)
                        Etrs.itrfToEtrs(p.lat!!, p.lon!!, p.hEll ?: 0.0, Etrs.epochYear(p.timeMs))
                    else null
                    val pt = doubleArrayOf(g?.lat ?: p.lat!!, g?.lon ?: p.lon!!, p.quality.code.toDouble())
                    val t = _trail.value
                    _trail.value = (if (t.size >= TRAIL_MAX) t.drop(t.size - TRAIL_MAX + 1) else t) + listOf(pt)
                }
            }
            "BUF" -> RoverProtocol.parsePos(f)?.let { onBufEpoch(it) }
            "BUFEND" -> onBufEnd()
            "STA" -> RoverProtocol.parseSta(f)?.let {
                _sta.value = it
                recordDiag(it)
                assist.onSta(it)
            }
            "SAT" -> RoverProtocol.parseSat(f)?.let { part ->
                // části musí jít po sobě; chybějící část = celá sada se zahodí (jinak se slijí dvě epochy)
                if (part.part == 1) {
                    satParts.clear()
                    satNextPart = 1
                }
                if (part.part != satNextPart) {
                    satParts.clear()
                    satNextPart = 1
                    return@let
                }
                satParts.addAll(part.sats)
                satNextPart++
                if (part.part == part.parts) {
                    _sats.value = ArrayList(satParts)
                    satParts.clear()
                    satNextPart = 1
                }
            }
        }
    }

    private fun onJson(line: String) {
        addConsole(line)
        val o = try {
            JSONObject(line)
        } catch (e: Exception) {
            Log.w(TAG, "chybný JSON: $line")
            return
        }
        if (o.has("id")) {
            pending.remove(o.optInt("id"))?.complete(o)
        } else if (o.has("ev")) {
            _messages.tryEmit("Rover: ${o.optString("ev")}")
        } else if (!o.optBoolean("ok", true)) {
            _messages.tryEmit("Rover: ${o.optString("err")}")
        }
    }

    private fun addConsole(s: String) {
        val c = _console.value
        _console.value = (if (c.size >= 50) c.drop(1) else c) + s
    }

    /** Evidence epoch pro kvalitu spojení. */
    private fun onEpoch(p: Pos) {
        val t = p.timeMs ?: return
        synchronized(epochTimes) {
            lastPosRxMs = p.rxMs
            val per = periodMs()
            if (lastEpochMs > 0 && t > lastEpochMs) {
                val missing = Math.round((t - lastEpochMs).toDouble() / per) - 1
                if (missing in 1L..3000L) lostTotal += missing
            }
            if (t > lastEpochMs) lastEpochMs = t
            epochTimes.addLast(t)
            while (epochTimes.isNotEmpty() && t - epochTimes.first() > 30_000) epochTimes.removeFirst()
            epochLatency.addLast(p.rxMs - t)
            while (epochLatency.size > 10 * (1000 / per).toInt().coerceAtLeast(1)) epochLatency.removeFirst()
        }
    }

    private fun periodMs(): Long {
        val hz = _info.value?.rateHz ?: 0
        return if (hz > 0) 1000L / hz else 200L
    }

    private fun updateLinkQuality() {
        if (link.state.value !is LinkState.Connected) {
            _linkQuality.value = LinkQuality(0, null, null, null, null, lostTotal)
            return
        }
        val now = System.currentTimeMillis()
        val q: LinkQuality
        synchronized(epochTimes) {
            val age = if (lastPosRxMs > 0) now - lastPosRxMs else null
            val lat = if (epochLatency.isEmpty()) null else epochLatency.sorted()[epochLatency.size / 2]
            val loss = if (epochTimes.size >= 2) {
                val span = epochTimes.last() - epochTimes.first()
                val expected = span / periodMs() + 1
                ((expected - epochTimes.size).coerceAtLeast(0L) * 100.0 / expected)
            } else null
            val backlog = _sta.value?.btBacklog
            // stáří dat vůči „teď“: když data nechodí, zpoždění roste i bez nových epoch
            val lagNow = if (lastEpochMs > 0) now - lastEpochMs else null
            val bars = when {
                age == null || age > 10_000L -> 0
                age > 3000L -> 1
                (lagNow ?: 0L) > 2000L || (loss ?: 0.0) > 10 -> 2
                (lat ?: 0L) > 600L || (loss ?: 0.0) > 2 || (backlog ?: 0L) > 1024L -> 3
                else -> 4
            }
            q = LinkQuality(bars, age, lat, loss, backlog, lostTotal)
        }
        _linkQuality.value = q
    }

    fun postMessage(text: String) {
        _messages.tryEmit(text)
    }

    fun clearTrail() {
        _trail.value = emptyList()
    }

    /** GGA pro NTRIP: poslední z roveru, pokud není starší než 10 s. */
    private fun currentGga(): String? =
        if (System.currentTimeMillis() - lastGgaMs < 10_000) lastGga else null

    companion object {
        private const val TAG = "RoverController"
        const val TRAIL_MAX = 7200  // 2 h při 1 Hz
        const val LIVE_MAX = 50_000       // průběžný průměr: nejvýš posledních 50 000 epoch (14 h při 1 Hz)
        const val LIVE_SPEED = 0.3        // [m/s] rychlost GNSS, nad kterou se počítá pohyb
        const val LIVE_FAST_EPOCHS = 5    // tolik epoch po sobě nad rychlostí = pohyb
    }
}
