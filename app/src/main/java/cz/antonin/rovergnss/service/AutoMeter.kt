package cz.antonin.rovergnss.service

import cz.antonin.rovergnss.link.Pos
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** Druh testu na záložce Auto. */
enum class AutoTestKind { ACCEL, ROLL, BRAKE, DIST }

/** Test: rozjezd v1 = 0 → v2, průjezd v1 → v2, brzdění v1 → 0, vzdálenost z místa [dist] m. Rychlosti [km/h]. */
data class AutoTestDef(val kind: AutoTestKind, val v1: Int = 0, val v2: Int = 0, val dist: Int = 0) {
    val label: String
        get() = when (kind) {
            AutoTestKind.ACCEL -> "0–$v2 km/h"
            AutoTestKind.ROLL -> "$v1–$v2 km/h"
            AutoTestKind.BRAKE -> "$v1–0 km/h"
            AutoTestKind.DIST -> "$dist m"
        }
}

/** Výsledek testu. */
data class AutoResult(
    val atMs: Long,             // čas konce (telefon)
    val label: String,
    val timeS: Double,          // doba [s]
    val distM: Double,          // dráha [m]
    val vEndKmh: Double,        // rychlost na konci [km/h] (u vzdálenosti výstupní rychlost)
    val maxG: Double,           // největší podélné zrychlení / zpomalení [g]
    val gradePct: Double?,      // sklon podle převýšení [%] (+ do kopce)
    val startFromImu: Boolean,  // začátek určen z IMU (jinak z rychlosti GNSS)
    val valid: Boolean,
    val note: String,
) {
    fun toJson(): JSONObject = JSONObject().put("at", atMs).put("label", label).put("t", timeS).put("d", distM)
        .put("v", vEndKmh).put("g", maxG).put("grade", gradePct ?: JSONObject.NULL).put("imu", startFromImu)
        .put("ok", valid).put("note", note)

    companion object {
        fun of(o: JSONObject) = AutoResult(
            o.optLong("at"), o.optString("label"), o.optDouble("t"), o.optDouble("d"), o.optDouble("v"),
            o.optDouble("g"), if (o.isNull("grade")) null else o.optDouble("grade"), o.optBoolean("imu"),
            o.optBoolean("ok"), o.optString("note"),
        )
    }
}

/** Živé hodnoty na záložce Auto. */
data class AutoLive(
    val speedKmh: Double?,
    val maxKmh: Double,
    val avgKmh: Double?,        // za jízdu (dráha / doba od aktivace)
    val avg10Kmh: Double?,      // klouzavý průměr 10 s
    val longG: Double?,         // podélně [g]: + zrychlení, − brzdění
    val latG: Double?,          // příčně [g]: + doprava
    val maxAccG: Double,
    val maxBrakeG: Double,
    val maxLatG: Double,
    val distM: Double,
    val imuCalibrated: Boolean, // určen směr jízdy (zrychlení z IMU)
    val armed: AutoTestDef?,
    val armedState: String,     // „čekám na zastavení“, „připraveno“, „měřím…“
)

/**
 * Měření rychlosti a testy (0.9.0, záložka Auto). Rychlost z X20 (doppler, 10 Hz v profilu Rychlost),
 * okamžik rozjezdu z IMU (podélné zrychlení stoupne nad 0,03 g – rozlišení 8 ms při 120 Hz), okamžiky překročení
 * rychlostí lineární interpolací mezi epochami. Dráha integrací rychlosti (ne z poloh). Jen metrické jednotky.
 * Test se „nabije“ a sám spustí i ukončí – za jízdy se na nic nesahá.
 */
class AutoMeter(private val imu: VehicleImu, private val save: (List<AutoResult>) -> Unit, initial: List<AutoResult>) {
    private val _live = MutableStateFlow(empty())
    val live: StateFlow<AutoLive> = _live.asStateFlow()
    private val _results = MutableStateFlow(initial)
    val results: StateFlow<List<AutoResult>> = _results.asStateFlow()

    private var activeSince = 0L
    private var maxKmh = 0.0
    private var dist = 0.0
    private var maxAcc = 0.0
    private var maxBrake = 0.0
    private var maxLat = 0.0
    private val last10 = ArrayDeque<Pair<Long, Double>>()   // (čas GNSS, rychlost)
    private var prev: Pos? = null

    // IMU: podélné zrychlení posledních 10 s (čas GNSS, m/s²)
    private val imuBuf = ArrayDeque<Pair<Double, Double>>()

    // test
    private var armed: AutoTestDef? = null
    private var phase = 0             // 0 čeká na výchozí podmínku, 1 připraveno, 2 měří
    private var stillSinceMs = 0L
    private var tStart = 0.0          // [ms GNSS]
    private var startImu = false
    private var tDist = 0.0
    private var tMaxG = 0.0
    private var hStart: Double? = null
    private var gap = false

    private fun empty() = AutoLive(null, 0.0, null, null, null, null, 0.0, 0.0, 0.0, 0.0, false, null, "")

    fun activate() {
        activeSince = System.currentTimeMillis()
        maxKmh = 0.0; dist = 0.0; maxAcc = 0.0; maxBrake = 0.0; maxLat = 0.0
        last10.clear(); prev = null; imuBuf.clear()
        armed = null; phase = 0
        _live.value = empty()
    }

    fun arm(def: AutoTestDef?) {
        armed = def
        phase = 0
        stillSinceMs = 0
        publish()
    }

    fun clearResults() {
        _results.value = emptyList()
        save(emptyList())
    }

    fun onImu(s: ImuSample) {
        val t = s.gnssMs ?: return
        val a = imu.longAcc ?: return
        imuBuf.addLast(t to a)
        while (imuBuf.isNotEmpty() && t - imuBuf.first().first > 10_000) imuBuf.removeFirst()
        if (phase == 2) tMaxG = max(tMaxG, abs(a) / VehicleImu.G)
        a.let { if (it > 0) maxAcc = max(maxAcc, it / VehicleImu.G) else maxBrake = max(maxBrake, -it / VehicleImu.G) }
        imu.latAcc?.let { maxLat = max(maxLat, abs(it) / VehicleImu.G) }
    }

    fun onPos(p: Pos) {
        if (!p.hasFix || p.deadReckoned) {
            if (phase == 2) gap = true
            publish(); return
        }
        val t = p.timeMs ?: return
        val kmh = p.speed * 3.6
        maxKmh = max(maxKmh, kmh)
        val pv = prev
        prev = p
        var vPrev: Double? = null
        var tPrev = 0L
        if (pv?.timeMs != null && t > pv.timeMs) {
            val dt = (t - pv.timeMs) / 1000.0
            if (dt < 1.0) {
                dist += (p.speed + pv.speed) / 2 * dt
                vPrev = pv.speed
                tPrev = pv.timeMs
            } else if (phase == 2) gap = true
        }
        last10.addLast(t to p.speed)
        while (last10.isNotEmpty() && t - last10.first().first > 10_000) last10.removeFirst()
        if (imu.longAcc == null && vPrev != null) {
            val a = (p.speed - vPrev) / ((t - tPrev) / 1000.0)
            if (a > 0) maxAcc = max(maxAcc, a / VehicleImu.G) else maxBrake = max(maxBrake, -a / VehicleImu.G)
        }
        runTest(p, t, vPrev, tPrev)
        publish()
    }

    private fun cross(v0: Double, t0: Long, v1: Double, t1: Long, x: Double): Double =
        if (v1 == v0) t1.toDouble() else t0 + (x - v0) / (v1 - v0) * (t1 - t0)

    private fun runTest(p: Pos, t: Long, vPrev: Double?, tPrev: Long) {
        val d = armed ?: return
        val v = p.speed
        val v1 = d.v1 / 3.6
        val v2 = d.v2 / 3.6
        when (phase) {
            0 -> {  // výchozí podmínka
                val ready = when (d.kind) {
                    AutoTestKind.ACCEL, AutoTestKind.DIST -> {
                        if (v < 0.3) {
                            if (stillSinceMs == 0L) stillSinceMs = t
                            t - stillSinceMs > 1000
                        } else {
                            stillSinceMs = 0; false
                        }
                    }
                    AutoTestKind.ROLL -> v < v1 - 5 / 3.6
                    AutoTestKind.BRAKE -> v > v1 + 3 / 3.6
                }
                if (ready) phase = 1
            }
            1 -> {
                val vp = vPrev ?: return
                when (d.kind) {
                    AutoTestKind.ACCEL, AutoTestKind.DIST -> if (v >= 1.0) {
                        // začátek: zpětně v IMU poslední okamžik, kdy podélné zrychlení bylo pod 0,03 g
                        val tCross = cross(vp, tPrev, v, t, 1.0)
                        val lim = 0.03 * VehicleImu.G
                        val low = imuBuf.lastOrNull { it.first <= tCross && it.second < lim }
                        if (low != null && tCross - low.first < 5000) {
                            tStart = low.first; startImu = true
                        } else {
                            tStart = cross(vp, tPrev, v, t, 0.3); startImu = false
                        }
                        begin(p)
                        // dráha od začátku do teď
                        tDist = (t - tStart) / 1000.0 * v / 2
                    }
                    AutoTestKind.ROLL -> if (vp < v1 && v >= v1) {
                        tStart = cross(vp, tPrev, v, t, v1); startImu = false; begin(p)
                        tDist = (t - tStart) / 1000.0 * (v + v1) / 2
                    }
                    AutoTestKind.BRAKE -> if (vp > v1 && v <= v1) {
                        tStart = cross(vp, tPrev, v, t, v1); startImu = false; begin(p)
                        tDist = (t - tStart) / 1000.0 * (v + v1) / 2
                    }
                }
            }
            2 -> {
                val vp = vPrev ?: return
                val segStart = max(tPrev.toDouble(), tStart)
                tDist += (v + vp) / 2 * (t - segStart) / 1000.0
                if (imu.longAcc == null) {  // bez IMU (směr jízdy ještě neurčen) největší zrychlení z GNSS
                    val dtS = (t - tPrev) / 1000.0
                    if (dtS > 0) tMaxG = max(tMaxG, abs(v - vp) / dtS / VehicleImu.G)
                }
                when (d.kind) {
                    AutoTestKind.ACCEL, AutoTestKind.ROLL -> if (v >= v2 && vp < v2) {
                        val te = cross(vp, tPrev, v, t, v2)
                        finish(p, te, tDist - (t - te) / 1000.0 * (v + v2) / 2, v2 * 3.6)
                    } else if (v < 0.5 && d.kind == AutoTestKind.ACCEL) abort()
                    AutoTestKind.BRAKE -> if (v < 0.3) {
                        val te = cross(vp, tPrev, v, t, 0.3)
                        finish(p, te, tDist, 0.0)
                    } else if (v > v1 + 5 / 3.6) abort()
                    AutoTestKind.DIST -> if (tDist >= d.dist) {
                        val over = tDist - d.dist
                        val dtBack = if (v > 0.1) over / ((v + vp) / 2) else 0.0
                        val te = t - dtBack * 1000.0
                        val vEnd = vp + (v - vp) * ((te - tPrev) / (t - tPrev))
                        finish(p, te, d.dist.toDouble(), vEnd * 3.6)
                    } else if (v < 0.5 && (t - tStart) > 3000) abort()
                }
            }
        }
    }

    private fun begin(p: Pos) {
        phase = 2
        tMaxG = 0.0
        hStart = p.hEll
        gap = false
    }

    private fun abort() {
        phase = 0
        stillSinceMs = 0
    }

    private fun finish(p: Pos, te: Double, distM: Double, vEndKmh: Double) {
        val d = armed ?: return
        val time = (te - tStart) / 1000.0
        val grade = hStart?.let { h0 -> p.hEll?.let { h1 -> if (distM > 20) (h1 - h0) / distM * 100 else null } }
        val notes = ArrayList<String>()
        if (gap) notes += "výpadek GNSS"
        if (grade != null && abs(grade) > 1.0) notes += if (grade > 0) "do kopce" else "z kopce"
        if (!startImu && (d.kind == AutoTestKind.ACCEL || d.kind == AutoTestKind.DIST)) notes += "začátek z GNSS"
        val r = AutoResult(
            System.currentTimeMillis(), d.label, time, distM, vEndKmh, tMaxG, grade, startImu,
            valid = !gap && time > 0, note = notes.joinToString(", "),
        )
        val l = (_results.value + r).takeLast(MAX_RESULTS)
        _results.value = l
        save(l)
        phase = 0
        stillSinceMs = 0
    }

    private fun publish() {
        val pv = prev
        val since = if (activeSince > 0) (System.currentTimeMillis() - activeSince) / 1000.0 else 0.0
        val avg10 = if (last10.size >= 2) last10.map { it.second }.average() * 3.6 else null
        val st = when {
            armed == null -> ""
            phase == 0 -> when (armed!!.kind) {
                AutoTestKind.ACCEL, AutoTestKind.DIST -> "zastav – po 1 s stání připraveno"
                AutoTestKind.ROLL -> "zpomal pod ${armed!!.v1 - 5} km/h"
                AutoTestKind.BRAKE -> "zrychli nad ${armed!!.v1 + 3} km/h"
            }
            phase == 1 -> "připraveno – start sám"
            else -> "měřím…"
        }
        _live.value = AutoLive(
            speedKmh = pv?.speed?.times(3.6), maxKmh = maxKmh,
            avgKmh = if (since > 5) dist / since * 3.6 else null, avg10Kmh = avg10,
            longG = imu.longAcc?.div(VehicleImu.G), latG = imu.latAcc?.div(VehicleImu.G),
            maxAccG = maxAcc, maxBrakeG = maxBrake, maxLatG = maxLat, distM = dist,
            imuCalibrated = imu.calibrated, armed = armed, armedState = st,
        )
    }

    companion object {
        const val MAX_RESULTS = 200
        val TESTS = listOf(
            AutoTestDef(AutoTestKind.ACCEL, 0, 50), AutoTestDef(AutoTestKind.ACCEL, 0, 100),
            AutoTestDef(AutoTestKind.ROLL, 60, 100), AutoTestDef(AutoTestKind.ROLL, 80, 120),
            AutoTestDef(AutoTestKind.BRAKE, 100), AutoTestDef(AutoTestKind.BRAKE, 50),
            AutoTestDef(AutoTestKind.DIST, dist = 100), AutoTestDef(AutoTestKind.DIST, dist = 200),
            AutoTestDef(AutoTestKind.DIST, dist = 400), AutoTestDef(AutoTestKind.DIST, dist = 1000),
        )

        fun listFromJson(s: String): List<AutoResult> = try {
            val a = JSONArray(s)
            (0 until a.length()).map { AutoResult.of(a.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }

        fun listToJson(l: List<AutoResult>): String = JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }.toString()
    }
}
