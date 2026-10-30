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
import cz.antonin.rovergnss.data.ObjPlace
import cz.antonin.rovergnss.data.ObjSketch
import cz.antonin.rovergnss.data.SiteObject
import cz.antonin.rovergnss.data.sketch
import cz.antonin.rovergnss.data.toJson
import kotlinx.coroutines.sync.withLock
import cz.antonin.rovergnss.data.EvalEpoch
import cz.antonin.rovergnss.data.PointEvaluator
import cz.antonin.rovergnss.data.PointGoal
import cz.antonin.rovergnss.data.PointPhase
import cz.antonin.rovergnss.data.PointResult
import cz.antonin.rovergnss.data.PointStatus
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
import org.json.JSONArray
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
    const val POINT = 3    // měření bodu tlačítkem REC: nový bod, vyhodnocení fixu, surový proud (0.10.0)
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
    val ownTrack: Boolean = true,     // CONTROL: trasa „M …“ patří měření (false = měření uvnitř záznamu testu)
    // POINT (REC, 0.10.0)
    val goal: PointGoal? = null,
    val status: PointStatus? = null,
    val jobName: String = "",
    val verifyRequested: Boolean = false,
    val failedNotified: Boolean = false,
    val fromObject: Boolean = false,  // spuštěno z náčrtu objektu – aplikace nepřepíná na mapu
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
        stopRecording("ukončení aplikace").join()
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
    private var noFixEpoch = false    // poslední epocha bez fixu – kvalita jen podle příjmu dat a fronty
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

    /** Doba připojení k RTK a cena od vynulování; upozornění na nečinnost (0.9.0). */
    val rtkUsage by lazy {
        RtkUsage(
            prefs, scope, ntrip.state,
            isRoverConnected = { link.state.value is LinkState.Connected },
            isBusy = { _recording.value != null || _measure.value != null },
            isRoverMoving = {
                val p = _pos.value
                val st = _sta.value
                (p != null && p.hasFix && p.speed > 0.5) || (st?.motion != null && st.motion != 0)
            },
            warn = { _messages.tryEmit(it); EventLog.log("RTK: $it") },
        )
    }

    // ------------------------------------------------------------------ IMU v autě, dopočet, měření rychlosti (0.9.0)
    /** Orientace krabičky v autě a zrychlení (z rámců IMU 0xF5 – při záznamu nebo měření rychlosti). */
    val vehicleImu = VehicleImu()
    private val dr = DeadReckoner(vehicleImu)
    /** Dopočtená poloha při výpadku GNSS (null = neběží). */
    private val _drPos = MutableStateFlow<Pos?>(null)
    val drPos: StateFlow<Pos?> = _drPos.asStateFlow()
    /** Záložka Auto: živé hodnoty a testy. */
    val autoMeter = AutoMeter(vehicleImu, save = { prefs.autoHistory = AutoMeter.listToJson(it) },
        initial = AutoMeter.listFromJson(prefs.autoHistory))
    /** Měření rychlosti běží (vnitřní profil Rychlost v roveru). */
    private val _speedActive = MutableStateFlow(false)
    val speedActive: StateFlow<Boolean> = _speedActive.asStateFlow()
    private var speedJob: kotlinx.coroutines.Job? = null

    // HAS navázaný na předchozí RTK (dočasné pravidlo, test 25. 10.)
    private var lastRtkPos: Pos? = null
    private var hasAnchoredToRtk = false
    private var noFixSinceMs = 0L

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

    fun deletePoint(p: Point) = scope.launch { deletePointNow(p) }

    /** Hromadné smazání bodů (i s měřeními a jejich soubory). */
    fun deletePoints(list: List<Point>) = scope.launch {
        var n = 0
        list.forEach { if (deletePointNow(it)) n++ }
        _messages.tryEmit("Smazáno $n bodů")
    }

    private suspend fun deletePointNow(p: Point): Boolean {
        if (_measure.value?.point?.id == p.id) {
            _messages.tryEmit("Na bodě ${p.name} probíhá měření")
            return false
        }
        if (_navTarget.value?.id == p.id) _navTarget.value = null
        // stopy měření bodu (trasy „M …“) se smažou s ním
        db.measurements().byPointList(p.id).forEach { m ->
            m.trackId?.let { deleteTrackFiles(it) }
            m.rawUbx?.let { rawFile(it).delete() }
        }
        db.points().delete(p.id)
        return true
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
     * @param inTrack měření uvnitř běžícího záznamu (test Kontrolní okruh): trasa se nezakládá ani neukončuje
     */
    fun startMeasurement(p: Point, seconds: Int, inTrack: Long? = null) = scope.launch {
        if (_measure.value != null) return@launch
        if (speedBlocks()) return@launch
        if (link.state.value !is LinkState.Connected) {
            _messages.tryEmit("Rover není připojený")
            return@launch
        }
        if (inTrack != null) {
            if (_recording.value?.trackId != inTrack) {
                _messages.tryEmit("Záznam testu neběží – měření nelze spustit")
                return@launch
            }
        } else if (_recording.value != null) {
            _messages.tryEmit("Nejdřív ukonči záznam trasy")
            return@launch
        } else {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            startRecording("M ${p.name} $stamp").join()
        }
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
        _measure.value = MeasureState(id, r.trackId, p, now, seconds, antH, staticModel = static, raw = ubxName != null,
            ownTrack = inTrack == null)
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
        if (speedBlocks()) return@launch
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

    // ------------------------------------------------------------------ měření bodu tlačítkem REC (0.10.0)
    private val signals by lazy { Signals(context, prefs) }
    @Volatile private var pointEval: PointEvaluator? = null
    private var pointTicker: kotlinx.coroutines.Job? = null

    /** Vyzkoušení signalizace (Nastavení). */
    fun testSignal(k: Signals.Kind) = signals.play(k)

    /** Název dalšího bodu v lokalitě: B1, B2, … (nejvyšší číslo B + 1). */
    suspend fun nextPointName(jobId: Long): String {
        val nums = db.points().listByJob(jobId).mapNotNull { Regex("^B(\\d+)$").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
        return "B${(nums.maxOrNull() ?: 0) + 1}"
    }

    /**
     * REC: měření nového bodu [name] v lokalitě [jobId] (nebo nové lokalitě [newJob]). Záznam se surovým proudem
     * (hlavička JSON na začátku, souhrn na konci), statický model, průběžné vyhodnocení fixu, zvuky; konec sám
     * při splnění cíle z profilu, „nejde změřit“ po limitu (rozhoduje uživatel), ručně kdykoli.
     */
    fun startPointMeasure(jobId: Long?, newJob: String?, name: String, objPlace: Pair<Long, ObjPlace>? = null) = scope.launch {
        if (_measure.value != null) {
            _messages.tryEmit("Probíhá jiné měření")
            return@launch
        }
        if (speedBlocks()) return@launch
        if (link.state.value !is LinkState.Connected) {
            _messages.tryEmit("Rover není připojený")
            return@launch
        }
        if (_recording.value != null) {
            _messages.tryEmit(if (test.run.value != null) "Běží test – měření bodu nelze spustit" else "Běží záznam – nejdřív ho ukonči")
            return@launch
        }
        // lokalita: nová, vybraná, nebo aktivní
        val nj = newJob?.trim()?.takeIf { it.isNotEmpty() }
        val job = when {
            nj != null -> db.jobs().insert(Job(name = nj)).let { db.jobs().get(it) }
            jobId != null -> db.jobs().get(jobId)
            else -> null
        } ?: ensureJob()
        prefs.activeJobId = job.id
        _activeJob.value = job
        val n = name.trim().ifEmpty { nextPointName(job.id) }
        if (db.points().listByJob(job.id).any { it.name == n }) {
            _messages.tryEmit("Bod $n v lokalitě ${job.name} už je")
            return@launch
        }
        val u = prefs.user
        val goal = u.pointGoal
        val now = System.currentTimeMillis()
        val pid = db.points().insert(
            Point(jobId = job.id, name = n, kind = "REC", source = PointSource.MEASURED, quality = PointQuality.APPROX,
                note = "měří se…")
        )
        val point = db.points().get(pid) ?: return@launch
        // měření spuštěné z náčrtu objektu: bod se hned umístí do náčrtu
        objPlace?.let { (oid, pl) ->
            // nové umístění se přidá, připravené (pomocný bod P) se doplní o změřený bod
            updateObject(oid) { s -> s.copy(places = s.places.filter { it.id != pl.id } + pl.copy(pointId = pid)) }
        }
        val header = JSONObject()
            .put("rg", "hlavicka").put("aplikace", appVersion()).put("lokalita", job.name).put("bod", n)
            .put("zacatek_utc", isoUtc(now)).put("vyska_anteny_m", u.antennaH).put("profil", u.name)
            .put("prijimac", _sta.value?.profileName ?: "").put("rover_fw", _info.value?.fw ?: "")
            .put("cil", JSONObject().put("fix_s", goal.minFixSec).put("sigma_h_m", goal.sigmaH).put("sigma_u_m", goal.sigmaU)
                .put("skok_h_m", goal.jumpH).put("skok_v_m", goal.jumpV).put("limit_s", goal.timeoutSec)
                .put("overeni", goal.verify))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
        val trackId = startRecordingNow("M $n $stamp", forceRaw = true, header = header.toString())
        if (trackId == null) {
            db.points().delete(pid)
            _messages.tryEmit("Záznam nelze spustit")
            return@launch
        }
        val id = db.measurements().insert(
            Measurement(
                pointId = pid, trackId = trackId, startMs = now, plannedSec = 0, antennaH = u.antennaH,
                profile = _sta.value?.profileName ?: "", note = "REC",
            )
        )
        synchronized(measEpochs) { measEpochs.clear() }
        measLastCalc = 0
        autoStopping = false
        pointEval = PointEvaluator(goal) { periodMs() / 1000.0 }
        val (static, _) = roverMeasStart(u.measStatic, false)
        db.measurements().get(id)?.let { db.measurements().update(it.copy(staticModel = static)) }
        _measure.value = MeasureState(
            id, trackId, point, now, 0, u.antennaH, staticModel = static, mode = MeasureMode.POINT,
            goal = goal, jobName = job.name, fromObject = objPlace != null,
        )
        EventLog.log("REC: měření bodu $n (${job.name})")
        pointTicker = scope.launch { pointLoop() }
    }

    /** 1× za sekundu: vyhodnocení, zvuky, ověření fixu, konec po splnění cíle. */
    private suspend fun pointLoop() {
        var prevFix = false
        var prevJumps = 0
        var prevClusters = 0
        var lastFixSignalMs = 0L
        while (true) {
            delay(1000)
            val m = _measure.value ?: return
            if (m.mode != MeasureMode.POINT) return
            val ev = pointEval ?: return
            val el = (System.currentTimeMillis() - m.startMs) / 1000.0
            val st = ev.status(el, m.verifyRequested)
            var next = m.copy(status = st)
            // zvuky přechodů
            val nowMs = System.currentTimeMillis()
            if (st.phase != PointPhase.DONE) {
                if (st.inFix && !prevFix && nowMs - lastFixSignalMs > 3000) {
                    signals.play(Signals.Kind.FIX)
                    lastFixSignalMs = nowMs
                } else if (!st.inFix && prevFix) signals.play(Signals.Kind.LOST)
                if (st.jumps > prevJumps || (st.clusters >= 2 && st.clusters > prevClusters)) {
                    signals.play(Signals.Kind.SUSPECT)
                    EventLog.log("REC ${m.point.name}: podezřelý fix (skoky ${st.jumps}, shluky ${st.clusters})")
                }
            }
            prevFix = st.inFix
            prevJumps = st.jumps
            prevClusters = st.clusters
            if (st.phase == PointPhase.FAILED && !m.failedNotified) {
                signals.play(Signals.Kind.FAILED)
                next = next.copy(failedNotified = true)
                EventLog.log("REC ${m.point.name}: nejde změřit – ${st.failReason}")
            }
            // ověření fixu: jednou vynutit nové určení fixu (horký start X20)
            if (st.phase == PointPhase.VERIFY && !m.verifyRequested) {
                next = next.copy(verifyRequested = true)
                _messages.tryEmit("Ověření fixu: X20 určí fix znovu (horký start)")
                scope.launch { resetX20Now("hot") }
            }
            if (!_measure.compareAndSet(m, next)) continue
            if (st.phase == PointPhase.DONE) {
                signals.play(Signals.Kind.DONE)
                stopMeasurement()
                return
            }
        }
    }

    /** Uložení (nebo zahození) měření bodu REC: bod, souhrn měření, souhrn na konec surového souboru. */
    private suspend fun finishPoint(m: MeasureState, eps: List<MeasureEpoch>, save: Boolean) {
        val ev = pointEval
        pointEval = null
        if (!save) {
            db.measurements().delete(m.measurementId)
            deleteTrackFiles(m.trackId)
            db.points().delete(m.point.id)
            _messages.tryEmit("Měření bodu ${m.point.name} zrušeno")
            return
        }
        val now = System.currentTimeMillis()
        val el = (now - m.startMs) / 1000.0
        val st = ev?.status(el, m.verifyRequested)
        val result = when {
            st?.phase == PointPhase.DONE -> PointResult.OK
            m.failedNotified || (st != null && el >= (m.goal?.timeoutSec ?: Int.MAX_VALUE)) -> PointResult.FAILED
            else -> PointResult.NOT_REACHED
        }
        // poloha: shluk fixu; bez fixu nejlepší dostupná kvalita (označeno)
        val fb = MeasureCalc.compute(eps)
        val lat = st?.lat ?: fb.lat
        val lon = st?.lon ?: fb.lon
        val h = st?.h ?: fb.h
        val fromFix = st?.lat != null
        val verify = when {
            m.goal?.verify != true || !m.verifyRequested -> 0
            (st?.clusters ?: 0) >= 2 -> 2
            (st?.independent ?: 0) >= 2 -> 1
            else -> 0
        }
        val mount = (ntrip.state.value as? NtripState.Streaming)?.mount
        val corr = when {
            fb.epochs > 0 && fb.nRtcm * 2 > fb.epochs -> "RTCM" + (mount?.let { " $it" } ?: "")
            fb.nHas > 0 -> "HAS"
            else -> "bez korekcí"
        }
        val sig = st?.sMeanH
        val note = PointResult.name(result) + (if (fromFix) ", RTK fix" else if (lat != null) ", bez fixu (${if (fb.usedQuality == 0) "smíšená" else Quality.of(fb.usedQuality).label})" else ", bez polohy") +
            (sig?.let { ", σ průměru ${PointEvaluator.cm(it)} cm" } ?: "") +
            (st?.fixPct?.let { String.format(Locale.US, ", fix %.0f %%", it) } ?: "") +
            (if ((st?.clusters ?: 0) >= 2) ", PODEZŘELÝ FIX" else "") +
            (st?.failReason?.let { " ($it)" } ?: "")
        val point = db.points().get(m.point.id) ?: m.point
        val p2 = PointCoords.complete(
            point.copy(
                lat = lat, lon = lon, hEll = h, y = null, x = null, bpv = null, etrsDerived = false, jtskDerived = false,
                quality = if (result == PointResult.OK && fromFix && (st?.clusters ?: 0) < 2) PointQuality.EXACT else PointQuality.APPROX,
                note = note,
            )
        )
        db.points().update(p2)
        val old = db.measurements().get(m.measurementId)
        if (old != null) db.measurements().update(
            old.copy(
                endMs = now, epochs = st?.epochs ?: fb.epochs, used = st?.nFix ?: fb.used,
                usedQuality = if (fromFix) Quality.FIX.code else fb.usedQuality,
                nFix = st?.nFix ?: fb.nFix, nFloat = st?.nFloat ?: fb.nFloat, nHas = fb.nHas, nDgnss = fb.nDgnss,
                nOther = fb.nOther, corr = corr, lat = lat, lon = lon, hEll = h,
                sN = st?.sN ?: fb.sN, sE = st?.sE ?: fb.sE, sU = st?.sU ?: fb.sU,
                result = result, fixSec = st?.fixSec, fixPct = st?.fixPct, sMeanH = st?.sMeanH, sMeanU = st?.sMeanU,
                maxDev = st?.maxDev, segments = st?.segments ?: 0, jumps = st?.jumps ?: 0, clusters = st?.clusters ?: 0,
                clusterDiffH = st?.clusterDiffH, verify = verify, note = note,
            )
        )
        // souhrn na konec surového souboru (záznam je už zavřený – nic se nepromíchá s daty z roveru)
        try {
            db.tracks().get(m.trackId)?.rawFile?.let { f ->
                val segs = JSONArray()
                ev?.segmentSummary()?.forEach { a ->
                    segs.put(JSONObject().put("od_utc", isoUtc(a[0].toLong())).put("do_utc", isoUtc(a[1].toLong()))
                        .put("epoch", a[2].toInt()).put("dN_m", r4(a[3])).put("dE_m", r4(a[4])).put("dU_m", r4(a[5])))
                }
                val j = if (lat != null && lon != null) cz.antonin.rovergnss.geo.Sjtsk.fromEtrs(lat, lon, h ?: 400.0) else null
                val o = JSONObject()
                    .put("rg", "souhrn").put("lokalita", m.jobName).put("bod", m.point.name)
                    .put("vysledek", PointResult.name(result)).put("duvod", st?.failReason ?: "")
                    .put("zacatek_utc", isoUtc(m.startMs)).put("konec_utc", isoUtc(now)).put("doba_s", r4(el))
                    .put("epoch", st?.epochs ?: 0).put("fix", st?.nFix ?: 0).put("float", st?.nFloat ?: 0)
                    .put("jine", st?.nOther ?: 0).put("fix_s", r4(st?.fixSec)).put("fix_pct", r4(st?.fixPct))
                    .put("poloha_z_fixu", fromFix)
                    .put("lat_etrs89", lat ?: JSONObject.NULL).put("lon_etrs89", lon ?: JSONObject.NULL)
                    .put("h_ell_m", r4(h)).put("y_jtsk", r4(j?.y)).put("x_jtsk", r4(j?.x)).put("h_bpv", r4(j?.bpv))
                    .put("sN_m", r4(st?.sN)).put("sE_m", r4(st?.sE)).put("sU_m", r4(st?.sU))
                    .put("s_prumer_h_m", r4(st?.sMeanH)).put("s_prumer_u_m", r4(st?.sMeanU)).put("max_odchylka_m", r4(st?.maxDev))
                    .put("useky", segs).put("skoky", st?.jumps ?: 0).put("shluky", st?.clusters ?: 0)
                    .put("rozdil_shluku_h_m", r4(st?.clusterDiffH)).put("rozdil_shluku_v_m", r4(st?.clusterDiffV))
                    .put("overeni", when (verify) { 1 -> "OK"; 2 -> "nesouhlasí"; else -> "ne" })
                    .put("vyska_anteny_m", m.antennaH).put("staticky_model", m.staticModel)
                FileOutputStream(rawFile(f), true).use { it.write(("\r\n" + o.toString() + "\r\n").toByteArray(Charsets.UTF_8)) }
            }
        } catch (e: IOException) {
            _messages.tryEmit("Souhrn do souboru nelze zapsat: ${e.message}")
        }
        EventLog.log("REC ${m.point.name}: $note")
        _messages.tryEmit("Bod ${m.point.name}: $note")
    }

    private fun r4(v: Double?): Any = v?.let { Math.round(it * 10000) / 10000.0 } ?: JSONObject.NULL

    private fun isoUtc(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    // ------------------------------------------------------------------ objekty (náčrt půdorysu, 0.11.0)
    fun createObject(jobId: Long, name: String, onCreated: (Long) -> Unit = {}) = scope.launch {
        val n = name.trim().ifEmpty { return@launch }
        val id = db.objects().insert(SiteObject(jobId = jobId, name = n))
        withContext(Dispatchers.Main) { onCreated(id) }
    }

    fun renameObject(o: SiteObject, name: String) = scope.launch {
        val n = name.trim().ifEmpty { return@launch }
        db.objects().update(o.copy(name = n))
    }

    fun deleteObject(o: SiteObject) = scope.launch { db.objects().delete(o.id) }

    /**
     * Přesun / kopie objektu do jiné lokality (0.11.16): náčrt, kóty, vazby, pomocné body. Změřené body zůstávají
     * v původní lokalitě – v náčrtu z nich jsou nezměřené body se stejným jménem, připravené k novému změření.
     */
    fun moveObject(o: SiteObject, toJob: Long, copy: Boolean, onDone: (Long) -> Unit = {}) = scope.launch {
        if (toJob == o.jobId) return@launch
        val names = db.points().listByJob(o.jobId).associate { it.id to it.name }
        val sk = o.sketch
        val nsk = sk.copy(places = sk.places.map { p ->
            if (p.pointId == 0L) p else p.copy(pointId = 0L, label = p.label.ifEmpty { names[p.pointId] ?: "" })
        })
        val target = db.jobs().get(toJob)?.name ?: "?"
        val id = if (copy) db.objects().insert(SiteObject(jobId = toJob, name = o.name, data = nsk.toJson()))
        else {
            db.objects().update(o.copy(jobId = toJob, data = nsk.toJson()))
            o.id
        }
        synchronized(undoStacks) {
            undoStacks.remove(o.id)
            redoStacks.remove(o.id)
        }
        postMessage(if (copy) "Objekt ${o.name} zkopírován do lokality $target" else "Objekt ${o.name} přesunut do lokality $target")
        withContext(Dispatchers.Main) { onDone(id) }
    }

    /** Uloží náčrt objektu. */
    fun saveSketch(objectId: Long, sk: ObjSketch) = scope.launch {
        db.objects().get(objectId)?.let { db.objects().update(it.copy(data = sk.toJson())) }
    }

    // Zpět / Znovu náčrtu: v paměti, pro každý objekt posledních [UNDO_MAX] stavů (JSON)
    private val undoStacks = HashMap<Long, ArrayDeque<String>>()
    private val redoStacks = HashMap<Long, ArrayDeque<String>>()
    private val _undoVersion = MutableStateFlow(0)
    /** Mění se při každé změně historie (pro překreslení tlačítek Zpět / Znovu). */
    val undoVersion: StateFlow<Int> = _undoVersion.asStateFlow()

    fun canUndo(objectId: Long) = synchronized(undoStacks) { undoStacks[objectId]?.isNotEmpty() == true }
    fun canRedo(objectId: Long) = synchronized(undoStacks) { redoStacks[objectId]?.isNotEmpty() == true }

    /** Náčrt objektu přes celé okno (skryje lištu lokality, podzáložky a spodní lištu, 0.11.6). */
    val objFull = MutableStateFlow(false)

    /** Úprava náčrtu z UI (vždy nad aktuálním stavem v databázi), uloží krok pro Zpět. */
    fun editObject(objectId: Long, f: (ObjSketch) -> ObjSketch) = scope.launch { saveSketchStep(objectId, f) }

    /** Uloží náčrt [sk] (stav zobrazený v UI) jako jeden krok pro Zpět. */
    fun setSketch(objectId: Long, sk: ObjSketch) = scope.launch { saveSketchStep(objectId) { sk } }

    private suspend fun saveSketchStep(objectId: Long, f: (ObjSketch) -> ObjSketch) {
        try {
            objLock.withLock {
                val o = db.objects().get(objectId)
                if (o == null) {
                    _messages.tryEmit("Náčrt nelze uložit: objekt $objectId nenalezen")
                    return@withLock
                }
                val nw = f(o.sketch).toJson()
                if (nw == o.data) return@withLock
                synchronized(undoStacks) {
                    val u = undoStacks.getOrPut(objectId) { ArrayDeque() }
                    u.addLast(o.data)
                    while (u.size > UNDO_MAX) u.removeFirst()
                    redoStacks[objectId]?.clear()
                }
                db.objects().update(o.copy(data = nw))
            }
        } catch (e: Exception) {
            Log.e(TAG, "náčrt", e)
            _messages.tryEmit("Náčrt nelze uložit: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
        _undoVersion.value++
    }

    /** Zpět (true) / Znovu (false) poslední změny náčrtu. */
    fun undoObject(objectId: Long, undo: Boolean = true) = scope.launch {
        objLock.withLock {
            val o = db.objects().get(objectId) ?: return@withLock
            val prev = synchronized(undoStacks) {
                val from = (if (undo) undoStacks else redoStacks)[objectId]
                val to = (if (undo) redoStacks else undoStacks).getOrPut(objectId) { ArrayDeque() }
                val p = from?.removeLastOrNull() ?: return@synchronized null
                to.addLast(o.data)
                p
            } ?: return@withLock
            db.objects().update(o.copy(data = prev))
        }
        _undoVersion.value++
    }

    private val objLock = kotlinx.coroutines.sync.Mutex()

    /** Změna náčrtu podle aktuálního stavu v databázi (souběh s UI). */
    suspend fun updateObject(objectId: Long, f: (ObjSketch) -> ObjSketch) = objLock.withLock {
        val o = db.objects().get(objectId) ?: return@withLock
        db.objects().update(o.copy(data = f(o.sketch).toJson()))
    }

    /** Vytyčení: krátké průměrování polohy na vytyčovaném bodě, uloží odchylku a pokračuje dalším bodem. */
    fun startStakeCheck(p: Point) = scope.launch {
        if (_measure.value != null) return@launch
        if (speedBlocks()) return@launch
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
        pointTicker?.cancel()
        pointTicker = null
        if ((m.mode == MeasureMode.CONTROL || m.mode == MeasureMode.POINT) && m.ownTrack) stopRecording("konec měření bodu").join()
        val eps = synchronized(measEpochs) { ArrayList(measEpochs).also { measEpochs.clear() } }
        if (m.mode == MeasureMode.POINT) {
            finishPoint(m, eps, save)
            return@launch
        }
        if (!save) {
            if (m.mode == MeasureMode.CONTROL) {
                if (m.raw) rawFile("meas_${m.measurementId}.ubx").delete()
                db.measurements().delete(m.measurementId)
                if (m.ownTrack) deleteTrackFiles(m.trackId)
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

    fun deleteMeasurement(m: Measurement) = scope.launch { deleteMeasurementNow(m) }

    /** Hromadné smazání měření (i s trasou a soubory). */
    fun deleteMeasurements(list: List<Measurement>) = scope.launch {
        var n = 0
        list.forEach { if (deleteMeasurementNow(it)) n++ }
        _messages.tryEmit("Smazáno $n měření")
    }

    private suspend fun deleteMeasurementNow(m: Measurement): Boolean {
        if (_measure.value?.measurementId == m.id && m.id != 0L) return false
        m.rawUbx?.let { rawFile(it).delete() }
        db.measurements().delete(m.id)
        m.trackId?.let { deleteTrackFiles(it) }
        return true
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
        // IMU z roveru (0xF5): orientace v autě, zrychlení, dopočet, měření rychlosti
        if (frame.size >= 4 && (frame[2].toInt() and 0xFF) == ImuFrames.CLS) {
            ImuFrames.parse(frame)?.forEach { s ->
                vehicleImu.onImu(s)
                dr.onImu(s)
                if (_speedActive.value) autoMeter.onImu(s)
            }
            return
        }
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
    /** DGNSS při RTK (po výpadku, výška až o 6 m mimo – 22. 10.) se v měření bodu a průměru nepoužije (0.9.0). */
    private fun dgnssInRtk(p: Pos) = p.quality == Quality.DGNSS && p.rtcm

    private fun measureEpoch(p: Pos) {
        val m = _measure.value ?: return
        if (m.mode == MeasureMode.POINT && p.timeMs != null) {
            val ev = pointEval
            if (ev != null) {
                if (p.hasFix) {
                    val g0 = etrsOf(p)
                    ev.add(EvalEpoch(p.timeMs, if (dgnssInRtk(p)) 0 else p.quality.code, g0.lat, g0.lon, g0.h - m.antennaH, p.moving))
                } else ev.add(EvalEpoch(p.timeMs, 0, 0.0, 0.0, 0.0, p.moving))
            }
        }
        if (!p.hasFix || p.timeMs == null || dgnssInRtk(p)) return
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
        if (!p.hasFix || p.timeMs == null || dgnssInRtk(p)) return
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

    /** Index profilu v roveru, který určuje aktivní profil aplikace (podle názvu; -1 = neměnit / v roveru není). */
    private fun userRoverIndex(): Int {
        val u = prefs.user
        return if (u.roverName.isNotEmpty()) _roverProfiles.value.indexOf(u.roverName) else u.roverProfile
    }

    private fun applyUserRoverProfile() {
        if (link.state.value !is LinkState.Connected) return
        val rp = userRoverIndex()
        if (rp < 0) return
        if (_info.value?.profileIndex == rp) return
        setProfile(rp)
    }

    /** Přepnutí profilu tlačítkem na roveru: vybrat profil aplikace se stejným názvem přijímače. */
    private fun followRoverProfile(name: String) {
        val l = prefs.userProfiles
        val i = l.indexOfFirst { it.roverName.isNotEmpty() && it.roverName == name }
        if (i < 0 || i == prefs.userIndex) return
        prefs.userIndex = i
        refreshUsers()
    }

    private suspend fun loadRoverProfiles() {
        val r = command(JSONObject().put("cmd", "get").put("what", "profiles")) ?: return
        val a = r.optJSONObject("data")?.optJSONArray("profiles") ?: return
        _roverProfiles.value = (0 until a.length()).map { a.getJSONObject(it).optString("name", "Profil ${it + 1}") }
    }

    /**
     * Při prvním připojení roveru zapíše profily Automat, Chůze, Měření, Auto (výchozí Automat) – stejné názvy
     * jako výchozí profily aplikace. Masky, SBAS/HAS, bzučák a NMEA převezme z dosavadního prvního profilu.
     */
    private suspend fun provisionRover() {
        val addr = (link.state.value as? LinkState.Connected)?.address ?: return
        if (prefs.roverProvisioned(addr)) return
        val i = _info.value ?: return
        if (!i.canSetup) return
        val d = roverProfilesJson() ?: return
        val a = d.optJSONArray("profiles") ?: JSONArray()
        val base = if (a.length() > 0) a.getJSONObject(0).toString() else "{}"
        val autoDyn = if ("motion" in i.caps) "auto" else "pedestrian"
        fun prof(name: String, rate: Int, dyn: String) = JSONObject(base).put("name", name).put("rate", rate).put("dyn", dyn)
        val profiles = JSONArray()
            .put(prof("Automat", 5, autoDyn))
            .put(prof("Chůze", 5, "pedestrian"))
            .put(prof("Měření", 1, "stationary"))
            .put(prof("Auto", 5, "automotive"))
        prefs.setRoverProvisioned(addr, true)  // před zápisem: uložení vyvolá nové načtení info (bez opakování)
        val err = saveRoverProfiles(JSONObject().put("default", 0).put("active", 0).put("profiles", profiles))
        if (err != null) {
            prefs.setRoverProvisioned(addr, false)
            _messages.tryEmit("Zápis profilů do roveru selhal: $err")
        } else {
            _messages.tryEmit("Do roveru zapsány profily Automat, Chůze, Měření, Auto (výchozí Automat)")
        }
    }

    fun takeCuzkPoint(c: CuzkPoint) = scope.launch {
        val j = ensureJob()
        db.points().insert(c.toPoint(j.id))
        _messages.tryEmit("Bod ${c.name} převzat do lokality ${j.name}")
    }

    init {
        rtkUsage.state  // spustí počítadlo RTK (započte i spojení přerušené pádem aplikace)
        scope.launch {
            ntrip.state.collect { s ->
                EventLog.log("NTRIP: " + when (s) {
                    NtripState.Off -> "vypnuto"
                    is NtripState.Connecting -> "připojuji (${s.attempt}) ${s.lastError ?: ""}"
                    is NtripState.Streaming -> "příjem ${s.mount}"
                    is NtripState.Failed -> "chyba ${s.error}"
                })
            }
        }
        // GNSS telefonu: polohy do porovnání a testu doby do fixu; zdroje zapnuté minule
        phone.onFix = { f ->
            phoneCompare.onPhone(f)
            phoneTtff.onFix(f)
        }
        phone.setAll(prefs.phoneSources.mapNotNull { PhoneSource.of(it) }.toSet())
        scope.launch { ensureJob() }
        // 0.10.0: záznam trasy jen pro testy – dosavadní trasy (mimo testy a měření bodů) se smažou
        if (!prefs.tracksPurged010) scope.launch(Dispatchers.IO) {
            val keep = db.measurements().allTrackIds().toSet()
            var n = 0
            db.tracks().listAll().filter { it.id !in keep && !it.name.startsWith("TEST ") }.forEach { t ->
                t.rawFile?.let { rawFile(it).delete() }
                db.tracks().delete(t.id)
                n++
            }
            prefs.tracksPurged010 = true
            EventLog.log("0.10.0: smazáno $n dosavadních tras")
        }
        // Po připojení zjistit, co je na druhé straně
        scope.launch {
            link.state.collect { st ->
                if (st is LinkState.Connected) {
                    autoPending = false
                    delay(300)
                    requestInfo().join()
                    fetchRoverBuffer()
                } else {
                    EventLog.log("rover: ${st.javaClass.simpleName}")
                    linkLost()
                    resetFrame()
                    if (_speedActive.value) {
                        speedJob?.cancel()
                        speedJob = null
                        _speedActive.value = false
                        _messages.tryEmit("Měření rychlosti přerušeno (odpojení) – rover se do 30 s vrátí na svůj profil")
                    }
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
        autoPending = false
        ntrip.stop()
        link.disconnect()
    }

    private var autoTried = false
    @Volatile private var autoPending = false

    /**
     * Po startu aplikace (s oprávněním Bluetooth) připojí naposledy použitý rover. Zkouší nejdéle 5 min,
     * pak přestane. Po ručním odpojení se znovu nepřipojuje (až po dalším startu aplikace).
     */
    fun autoConnect() {
        if (autoTried) return
        autoTried = true
        val a = prefs.roverAddress ?: return
        if (!link.isEnabled || link.state.value !is LinkState.Idle) return
        autoPending = true
        connect(a)
        scope.launch {
            delay(AUTO_CONNECT_MS)
            if (autoPending && link.state.value is LinkState.Connecting) {
                autoPending = false
                link.disconnect()
                _messages.tryEmit("Rover se do 5 min nepřipojil – automatické připojení zastaveno")
            }
        }
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
        scope.launch {
            provisionRover()
            loadRoverProfiles()
            applyUserRoverProfile()
        }
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
    suspend fun resetX20Now(kind: String): Boolean {
        resetFrame()
        EventLog.log("restart X20: $kind")
        return command(JSONObject().put("cmd", "x20").put("op", "reset").put("kind", kind))?.optBoolean("ok") == true
    }

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

    /** Výsledek kalibrace gyra na pozadí (FW 0.15.0, událost "cal_gyro"). */
    @Volatile private var calWaiter: CompletableDeferred<JSONObject>? = null

    /**
     * Kalibrace baterie změřeným napětím [V] nebo gyra ([volts] = null). Odpověď roveru (ok, err, nové hodnoty).
     * Gyro: FW 0.15.0+ kalibruje 30 s na pozadí – odpověď "pending", výsledek přijde událostí "cal_gyro".
     */
    suspend fun calibrateRover(volts: Double?): JSONObject? {
        val c = JSONObject().put("cmd", "cal")
        if (volts != null) return command(c.put("what", "battery").put("v", volts), 8000)
        val w = CompletableDeferred<JSONObject>()
        calWaiter = w
        val r = command(c.put("what", "gyro").put("ms", 30000), 8000)
        if (r == null || !r.optBoolean("pending")) {
            calWaiter = null
            return r
        }
        val ev = withTimeoutOrNull(r.optLong("ms", 30000) + 15000) { w.await() }
        calWaiter = null
        return ev
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
     * Spustí záznam a vrátí id trasy (null = záznam už běží). [forceRaw]: true = surový proud vždy (test pro EKF),
     * false = nikdy (lehký test), null = podle uživatelského profilu.
     */
    suspend fun startRecordingNow(
        name: String? = null, includeAverage: Boolean = false, forceRaw: Boolean? = null, header: String? = null,
    ): Long? {
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
        if (forceRaw ?: prefs.recordRaw) {
            val name = "track_$id.log"
            try {
                val f = rawFile(name)
                f.parentFile?.mkdirs()
                synchronized(rawLock) {
                    rawOut = BufferedOutputStream(FileOutputStream(f), 65536).also { o ->
                        // hlavička (JSON na prvním řádku) – před prvními daty z roveru
                        header?.let { h -> o.write((h + "\r\n").toByteArray(Charsets.UTF_8)) }
                    }
                }
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
        EventLog.log("začátek záznamu $id „$n“")
        return id
    }

    /**
     * Konec záznamu trasy. [reason] se zapíše do deníku událostí (0.9.0 – konec záznamu bez zásahu 25. 10.).
     * Tlačítka v Živě a Trasách ([fromUi]) záznam testu neukončí – test se ukončuje na kartě Test.
     */
    fun stopRecording(reason: String = "?", fromUi: Boolean = false) = scope.launch {
        val r = _recording.value ?: return@launch
        if (fromUi && test.run.value != null) {
            _messages.tryEmit("Běží test – záznam se ukončí koncem testu na kartě Test")
            EventLog.log("konec záznamu odmítnut (běží test): $reason")
            return@launch
        }
        EventLog.log("konec záznamu ${r.trackId} – $reason")
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
        // měření bodu a testovací záznam ukládají každou epochu (lehký test každou 5.)
        val every = when {
            _measure.value != null -> 1
            test.run.value != null -> if (test.run.value?.scenario?.light == true) 5 else 1
            else -> prefs.recordEvery.coerceAtLeast(1)
        }
        if (recCounter % every != 0) return
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
            "POS" -> RoverProtocol.parsePos(f)?.let { p0 ->
                val p = frameOf(p0)
                if (p.hasFix) p.timeMs?.let { ImuFrames.syncFromUtc(it) }
                vehicleImu.onPos(p)
                if (_speedActive.value) autoMeter.onPos(p)
                val drOk = _sta.value?.motion == 3 || _speedActive.value
                val d = dr.onPos(p, drOk)
                _drPos.value = d
                if (d != null) {
                    record(d)
                    trailAdd(d.lat!!, d.lon!!, d.quality.code)
                }
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
                    trailAdd(g?.lat ?: p.lat!!, g?.lon ?: p.lon!!, p.quality.code)
                }
            }
            "BUF" -> RoverProtocol.parsePos(f)?.let { onBufEpoch(it) }
            "BUFEND" -> onBufEnd()
            "STA" -> RoverProtocol.parseSta(f)?.let {
                val prevName = _sta.value?.profileName
                _sta.value = it
                if (prevName != null && prevName != it.profileName) followRoverProfile(it.profileName)
                recordDiag(it)
                test.onSta(it)
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
        } else if (o.optString("ev") == "cal_gyro") {
            calWaiter?.complete(o)
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
        // bez fixu není čas GNSS platný (hodiny X20) – zpoždění a ztráty by byly nesmyslné (0.9.0); spojení žije
        if (!p.hasFix) {
            synchronized(epochTimes) {
                lastPosRxMs = p.rxMs
                noFixEpoch = true
                epochTimes.clear()
                epochLatency.clear()
                lastEpochMs = 0
            }
            return
        }
        val t = p.timeMs ?: return
        synchronized(epochTimes) {
            noFixEpoch = false
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
        val hz = _sta.value?.rateHz?.takeIf { it > 0 } ?: _info.value?.rateHz ?: 0
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
                noFixEpoch -> if ((backlog ?: 0L) > 1024L) 3 else 4
                (lagNow ?: 0L) > 2000L || (loss ?: 0.0) > 10 -> 2
                (lat ?: 0L) > 600L || (loss ?: 0.0) > 2 || (backlog ?: 0L) > 1024L -> 3
                else -> 4
            }
            q = if (noFixEpoch) LinkQuality(bars, age, null, null, backlog, lostTotal)
            else LinkQuality(bars, age, lat, loss, backlog, lostTotal)
        }
        _linkQuality.value = q
    }

    fun postMessage(text: String) {
        _messages.tryEmit(text)
    }

    private fun trailAdd(lat: Double, lon: Double, q: Int) {
        val t = _trail.value
        _trail.value = (if (t.size >= TRAIL_MAX) t.drop(t.size - TRAIL_MAX + 1) else t) + listOf(doubleArrayOf(lat, lon, q.toDouble()))
    }

    /**
     * Dočasné pravidlo (0.9.0, test 25. 10.): HAS po RTK v téže relaci zůstává v systému RTK (ETRS89), když surová
     * poloha při přechodu neskočí (< 0,3 m po odečtení posunu z rychlosti). Platí do ztráty fixu na > 30 s,
     * odpojení roveru nebo restartu X20. HAS bez předchozího RTK se dál přepočítává z ITRF2020.
     */
    private fun frameOf(p: Pos): Pos {
        val now = System.currentTimeMillis()
        if (!p.hasFix) {
            if (noFixSinceMs == 0L) noFixSinceMs = now
            if (now - noFixSinceMs > 30_000) {
                hasAnchoredToRtk = false
                lastRtkPos = null
            }
            return p
        }
        noFixSinceMs = 0
        if (p.rtcm) {
            if (p.carrSoln > 0) lastRtkPos = p
            return p
        }
        val r = lastRtkPos
        if (!hasAnchoredToRtk && r != null && r.timeMs != null && p.timeMs != null && p.timeMs - r.timeMs < 2000) {
            val dt = (p.timeMs - r.timeMs) / 1000.0
            val dN = (p.lat!! - r.lat!!) * Math.PI / 180 * 6_371_000.0
            val dE = (p.lon!! - r.lon!!) * Math.PI / 180 * 6_371_000.0 * Math.cos(Math.toRadians(p.lat!!))
            val hd = Math.toRadians(r.heading)
            val pN = r.speed * Math.cos(hd) * dt
            val pE = r.speed * Math.sin(hd) * dt
            val jump = Math.hypot(dN - pN, dE - pE)
            hasAnchoredToRtk = jump < 0.3
            EventLog.log("RTK → HAS: skok surové polohy ${"%.2f".format(Locale.US, jump)} m – " +
                if (hasAnchoredToRtk) "HAS v systému RTK (ETRS89)" else "HAS přepočet z ITRF2020")
            lastRtkPos = null
        }
        return if (hasAnchoredToRtk) p.copy(frameRtk = true) else p
    }

    /** Konec relace (odpojení, restart X20): HAS se zase přepočítává z ITRF2020. */
    private fun resetFrame() {
        hasAnchoredToRtk = false
        lastRtkPos = null
        noFixSinceMs = 0
        dr.reset()
        vehicleImu.reset()
    }

    /** Během měření rychlosti (automotive, bez HPPOSLLH a COV) nejde měřit bod. */
    private fun speedBlocks(): Boolean {
        if (!_speedActive.value) return false
        _messages.tryEmit("Běží měření rychlosti (záložka Auto) – měření bodu nejde, nejdřív ho ukonči")
        return true
    }

    // ------------------------------------------------------------------ měření rychlosti (záložka Auto, FW 0.16.0+)
    /** Rover umí měření rychlosti (vnitřní profil Rychlost). */
    fun roverSupportsSpeed(): Boolean = _info.value?.caps?.contains("speed") == true

    /**
     * Aktivace měření: rover přepne do vnitřního profilu Rychlost (10 Hz, IMU 120 Hz), aplikace mu každých 5 s
     * posílá „žiju“. Ukončení (nebo 30 s bez „žiju“ – odpojení, pád aplikace) vrátí rover na předchozí profil.
     */
    fun startSpeed() = scope.launch {
        if (_speedActive.value) return@launch
        if (link.state.value !is LinkState.Connected) {
            _messages.tryEmit("Rover není připojený")
            return@launch
        }
        if (!roverSupportsSpeed()) {
            _messages.tryEmit("Měření rychlosti potřebuje FW roveru 0.16.0 nebo novější")
            return@launch
        }
        val r = command(JSONObject().put("cmd", "speed").put("op", "start"))
        if (r?.optBoolean("speed") != true) {
            _messages.tryEmit("Rover měření rychlosti nezapnul")
            return@launch
        }
        autoMeter.activate()
        _speedActive.value = true
        EventLog.log("měření rychlosti ZAP")
        speedJob?.cancel()
        speedJob = scope.launch {
            while (true) {
                delay(5000)
                if (link.state.value is LinkState.Connected) command(JSONObject().put("cmd", "speed").put("op", "alive"), 2000)
            }
        }
    }

    fun stopSpeed() = scope.launch {
        if (!_speedActive.value) return@launch
        speedJob?.cancel()
        speedJob = null
        _speedActive.value = false
        autoMeter.arm(null)
        EventLog.log("měření rychlosti VYP")
        if (link.state.value is LinkState.Connected) command(JSONObject().put("cmd", "speed").put("op", "stop"))
    }

    fun clearTrail() {
        _trail.value = emptyList()
    }

    /** GGA pro NTRIP: poslední z roveru, pokud není starší než 10 s. */
    private fun currentGga(): String? {
        val g = lastGga ?: return null
        if (System.currentTimeMillis() - lastGgaMs >= 10_000) return null
        // GGA bez fixu (kvalita 0) caster nepřijme – NTRIP čeká na polohu (0.9.0)
        val q = g.split(',').getOrNull(6)?.toIntOrNull() ?: 0
        return if (q > 0) g else null
    }

    companion object {
        private const val AUTO_CONNECT_MS = 5 * 60_000L
        private const val TAG = "RoverController"
        const val TRAIL_MAX = 7200  // 2 h při 1 Hz
        const val UNDO_MAX = 50     // kroků Zpět u náčrtu objektu
        const val LIVE_MAX = 50_000       // průběžný průměr: nejvýš posledních 50 000 epoch (14 h při 1 Hz)
        const val LIVE_SPEED = 0.3        // [m/s] rychlost GNSS, nad kterou se počítá pohyb
        const val LIVE_FAST_EPOCHS = 5    // tolik epoch po sobě nad rychlostí = pohyb
    }
}
