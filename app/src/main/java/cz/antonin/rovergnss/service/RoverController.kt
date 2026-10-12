package cz.antonin.rovergnss.service

import android.content.Context
import android.util.Log
import cz.antonin.rovergnss.data.AppDb
import cz.antonin.rovergnss.data.Job
import cz.antonin.rovergnss.data.Prefs
import cz.antonin.rovergnss.data.Track
import cz.antonin.rovergnss.data.TrackDiag
import cz.antonin.rovergnss.data.TrackPoint
import cz.antonin.rovergnss.geo.Etrs
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

/** Stav záznamu trasy. */
data class RecordingState(val trackId: Long, val name: String, val points: Int, val startMs: Long)

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
)

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

    /** Stopa posledních poloh pro mapu (ETRS89, 1 Hz, max. [TRAIL_MAX]). */
    private val _trail = MutableStateFlow<List<DoubleArray>>(emptyList())
    val trail: StateFlow<List<DoubleArray>> = _trail.asStateFlow()

    /** Události a hlášky pro UI (jednorázové). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Řádky protokolu pro diagnostiku (poslední) */
    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console.asStateFlow()

    @Volatile private var lastGga: String? = null
    @Volatile private var lastGgaMs = 0L

    // ------------------------------------------------------------------ spojení
    private val satParts = ArrayList<Sat>()
    private val parser = StreamParser(onLine = ::onLine)
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

    init {
        // Po připojení zjistit, co je na druhé straně
        scope.launch {
            link.state.collect { st ->
                if (st is LinkState.Connected) {
                    delay(300)
                    requestInfo()
                } else if (st is LinkState.Idle) {
                    _info.value = null
                }
            }
        }
        // Služba na popředí běží, dokud je něco aktivní
        scope.launch {
            combine(link.state, ntrip.state, _recording) { l, n, r ->
                (l !is LinkState.Idle && l !is LinkState.Failed) || n !is NtripState.Off || r != null
            }.distinctUntilChanged().collect { active ->
                if (active) RoverService.start(context) else RoverService.stop(context)
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
        )
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

    // ------------------------------------------------------------------ záznam trasy
    fun startRecording(name: String? = null) = scope.launch {
        if (_recording.value != null) return@launch
        val job = db.jobs().first() ?: Job(name = "Výchozí").let { it.copy(id = db.jobs().insert(it)) }
        val now = System.currentTimeMillis()
        val n = name ?: ("Trasa " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(now)))
        val id = db.tracks().insert(
            Track(jobId = job.id, name = n, startMs = now, antennaH = prefs.antennaH, rateHz = _info.value?.rateHz ?: 0)
        )
        if (prefs.recordRaw) {
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
        _recording.value = RecordingState(id, n, 0, now)
    }

    fun stopRecording() = scope.launch {
        val r = _recording.value ?: return@launch
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
        )
        synchronized(recBuffer) { diagBuffer.add(d) }
    }

    private fun record(p: Pos) {
        val r = _recording.value ?: return
        if (!p.hasFix || p.timeMs == null) return
        recCounter++
        if (recCounter % prefs.recordEvery.coerceAtLeast(1) != 0) return
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
                _pos.value = p
                record(p)
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
            "STA" -> RoverProtocol.parseSta(f)?.let {
                _sta.value = it
                recordDiag(it)
            }
            "SAT" -> RoverProtocol.parseSat(f)?.let { part ->
                if (part.part == 1) satParts.clear()
                satParts.addAll(part.sats)
                if (part.part == part.parts) _sats.value = ArrayList(satParts)
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
        const val TRAIL_MAX = 3600
    }
}
