package cz.antonin.rovergnss.phone

import cz.antonin.rovergnss.data.AppDb
import cz.antonin.rovergnss.data.MeasureCalc
import cz.antonin.rovergnss.data.PhoneSample
import cz.antonin.rovergnss.data.PhoneSession
import cz.antonin.rovergnss.data.Prefs
import cz.antonin.rovergnss.data.d2
import cz.antonin.rovergnss.geo.Etrs
import cz.antonin.rovergnss.link.Pos
import cz.antonin.rovergnss.link.Quality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.sqrt

/** Statistika odchylek jednoho zdroje telefonu vůči roveru [m]. */
data class CompareStats(
    val n: Int,
    val meanN: Double,
    val meanE: Double,
    val meanU: Double?,
    val sN: Double?,
    val sE: Double?,
    val sU: Double?,
    val rms2d: Double,
    val cep50: Double,
    val cep95: Double,
    val max2d: Double,
    val overAccPct: Double?,  // podíl epoch, kdy 2D chyba > hlášená přesnost [%]
    val meanAcc: Double?,
)

/**
 * Porovnání poloh telefonu s roverem. Rover = reference (anténa, ETRS89); telefon WGS84 ≈ ITRF2020 → ETRS89.
 * GNSS čip se páruje podle času GNSS (nejbližší epocha roveru do 100 ms), Fused a Network podle času příjmu.
 * Odchylka = telefon − (rover + odsazení telefonu od antény).
 */
class PhoneCompare(private val scope: CoroutineScope, private val db: AppDb, private val prefs: Prefs) {
    private class Ref(val gnssMs: Long, val rxMs: Long, val lat: Double, val lon: Double, val h: Double, val q: Int, val rtcm: Boolean)

    private val refs = ArrayDeque<Ref>()

    private val _live = MutableStateFlow<Map<PhoneSource, PhoneSample>>(emptyMap())
    val live: StateFlow<Map<PhoneSource, PhoneSample>> = _live.asStateFlow()

    /** Posledních [HISTORY] vzorků každého zdroje (grafy). */
    private val _history = MutableStateFlow<Map<PhoneSource, List<PhoneSample>>>(emptyMap())
    val history: StateFlow<Map<PhoneSource, List<PhoneSample>>> = _history.asStateFlow()

    private val _stats = MutableStateFlow<Map<PhoneSource, CompareStats>>(emptyMap())
    val stats: StateFlow<Map<PhoneSource, CompareStats>> = _stats.asStateFlow()

    /** Stopy zdrojů telefonu na mapě: [lat, lon] ETRS89. */
    private val _trails = MutableStateFlow<Map<PhoneSource, List<DoubleArray>>>(emptyMap())
    val trails: StateFlow<Map<PhoneSource, List<DoubleArray>>> = _trails.asStateFlow()

    /** Polohy telefonu bez epochy roveru ke spárování (rover odpojený, bez fixu, časy mimo toleranci). */
    private val _unpaired = MutableStateFlow(0)
    val unpaired: StateFlow<Int> = _unpaired.asStateFlow()

    private val _session = MutableStateFlow<PhoneSession?>(null)
    val session: StateFlow<PhoneSession?> = _session.asStateFlow()

    // všechny vzorky od vynulování (statistika, export bez uložení)
    private val all = HashMap<PhoneSource, ArrayList<PhoneSample>>()
    private val pendingDb = ArrayList<PhoneSample>()
    private var lastStatsMs = 0L

    init {
        scope.launch {
            while (true) {
                delay(2000)
                flush()
            }
        }
    }

    /** Epocha roveru (volá se z vlákna příjmu). */
    fun onRover(p: Pos) {
        if (!p.hasFix || p.timeMs == null) return
        val g = if (p.itrf) Etrs.itrfToEtrs(p.lat!!, p.lon!!, p.hEll ?: 0.0, Etrs.epochYear(p.timeMs)) else null
        val r = Ref(p.timeMs, p.rxMs, g?.lat ?: p.lat!!, g?.lon ?: p.lon!!, g?.h ?: (p.hEll ?: 0.0), p.quality.code, p.rtcm)
        synchronized(refs) {
            refs.addLast(r)
            while (refs.isNotEmpty() && r.rxMs - refs.first().rxMs > REF_KEEP_MS) refs.removeFirst()
        }
    }

    private fun pair(f: PhoneFix): Ref? = synchronized(refs) {
        if (refs.isEmpty()) return null
        if (f.gnssMs != null) {
            val best = refs.minByOrNull { abs(it.gnssMs - f.gnssMs) } ?: return null
            if (abs(best.gnssMs - f.gnssMs) <= GNSS_TOL_MS) best else null
        } else {
            val best = refs.minByOrNull { abs(it.rxMs - f.wallMs) } ?: return null
            if (abs(best.rxMs - f.wallMs) <= WALL_TOL_MS) best else null
        }
    }

    /** 2D odchylka polohy telefonu od roveru [m] (test doby do fixu), null = nelze spárovat. */
    fun errorOf(f: PhoneFix): Double? = sample(f)?.d2

    private fun sample(f: PhoneFix): PhoneSample? {
        val r = pair(f) ?: return null
        val t = f.gnssMs ?: f.wallMs
        val g = Etrs.itrfToEtrs(f.lat, f.lon, f.hEll ?: r.h, Etrs.epochYear(t))
        val (kN, kE) = MeasureCalc.metersPerDeg(r.lat)
        return PhoneSample(
            sessionId = _session.value?.id ?: 0,
            source = f.source.code,
            timeMs = f.wallMs,
            lat = g.lat, lon = g.lon, hEll = if (f.hEll != null) g.h else null,
            acc = f.acc, vAcc = f.vAcc,
            ageMs = (f.rxMs - f.wallMs).coerceAtLeast(0),
            numSv = f.numSv,
            dN = (g.lat - r.lat) * kN - prefs.phoneOffN,
            dE = (g.lon - r.lon) * kE - prefs.phoneOffE,
            dU = if (f.hEll != null) g.h - r.h - prefs.phoneOffU else null,
            refLat = r.lat, refLon = r.lon, refH = r.h, refQuality = r.q, refRtcm = r.rtcm,
        )
    }

    /** Poloha telefonu (hlavní vlákno). */
    fun onPhone(f: PhoneFix) {
        val s = sample(f)
        if (s == null) {
            _unpaired.value = _unpaired.value + 1
            return
        }
        val src = f.source
        _live.value = _live.value + (src to s)
        val h = _history.value[src].orEmpty()
        _history.value = _history.value + (src to (if (h.size >= HISTORY) h.drop(h.size - HISTORY + 1) else h) + listOf(s))
        val tr = _trails.value[src].orEmpty()
        _trails.value = _trails.value + (src to (if (tr.size >= TRAIL) tr.drop(tr.size - TRAIL + 1) else tr) + listOf(doubleArrayOf(s.lat, s.lon)))
        synchronized(all) {
            val l = all.getOrPut(src) { ArrayList() }
            if (l.size >= ALL_MAX) l.removeAt(0)
            l.add(s)
        }
        if (_session.value != null) synchronized(pendingDb) { pendingDb.add(s) }
        val now = System.currentTimeMillis()
        if (now - lastStatsMs >= 1000) {
            lastStatsMs = now
            recomputeStats()
        }
    }

    private fun recomputeStats() {
        val m = HashMap<PhoneSource, CompareStats>()
        synchronized(all) { for ((k, v) in all) stats(v)?.let { m[k] = it } }
        _stats.value = m
    }

    /** Vynuluje statistiku, grafy a stopy (uložené porovnání to neovlivní). */
    fun reset() {
        synchronized(all) { all.clear() }
        _live.value = emptyMap()
        _history.value = emptyMap()
        _stats.value = emptyMap()
        _trails.value = emptyMap()
        _unpaired.value = 0
    }

    /** Vzorky od vynulování (export bez uložení). */
    fun currentSamples(): List<PhoneSample> = synchronized(all) { all.values.flatten().sortedWith(compareBy({ it.timeMs }, { it.source })) }

    // ------------------------------------------------------------------ uložení k zakázce
    fun startSession(jobId: Long, name: String, sources: Set<PhoneSource>) = scope.launch {
        if (_session.value != null) return@launch
        reset()
        val s = PhoneSession(
            jobId = jobId, name = name, startMs = System.currentTimeMillis(),
            offN = prefs.phoneOffN, offE = prefs.phoneOffE, offU = prefs.phoneOffU,
            sources = sources.sortedBy { it.code }.joinToString(", ") { it.label },
        )
        _session.value = s.copy(id = db.phone().insertSession(s))
    }

    fun stopSession() = scope.launch {
        val s = _session.value ?: return@launch
        flush()
        _session.value = null
        val n = db.phone().samples(s.id).size
        db.phone().updateSession(s.copy(endMs = System.currentTimeMillis(), samples = n))
    }

    private suspend fun flush() {
        val s = _session.value ?: return
        val batch = synchronized(pendingDb) { ArrayList(pendingDb).also { pendingDb.clear() } }
        if (batch.isEmpty()) return
        db.phone().insertSamples(batch.map { it.copy(sessionId = s.id) })
    }

    companion object {
        const val GNSS_TOL_MS = 100L
        const val WALL_TOL_MS = 600L
        const val REF_KEEP_MS = 120_000L
        const val HISTORY = 600       // 10 min při 1 Hz
        const val TRAIL = 1800
        const val ALL_MAX = 20_000

        fun stats(l: List<PhoneSample>): CompareStats? {
            if (l.isEmpty()) return null
            val n = l.size
            val mN = l.sumOf { it.dN } / n
            val mE = l.sumOf { it.dE } / n
            val us = l.mapNotNull { it.dU }
            val mU = if (us.isEmpty()) null else us.average()
            fun sd(v: List<Double>, m: Double): Double? =
                if (v.size < 2) null else sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
            val d2 = l.map { it.d2 }.sorted()
            fun pct(p: Double) = d2[((d2.size - 1) * p).toInt().coerceIn(0, d2.size - 1)]
            val withAcc = l.filter { it.acc != null }
            return CompareStats(
                n = n, meanN = mN, meanE = mE, meanU = mU,
                sN = sd(l.map { it.dN }, mN), sE = sd(l.map { it.dE }, mE), sU = mU?.let { sd(us, it) },
                rms2d = sqrt(d2.sumOf { it * it } / n),
                cep50 = pct(0.5), cep95 = pct(0.95), max2d = d2.last(),
                overAccPct = if (withAcc.isEmpty()) null else withAcc.count { it.d2 > it.acc!! } * 100.0 / withAcc.size,
                meanAcc = if (withAcc.isEmpty()) null else withAcc.sumOf { it.acc!! } / withAcc.size,
            )
        }

        /** CSV porovnání (oddělovač ;, desetinná tečka – jako ostatní exporty). */
        fun csv(samples: List<PhoneSample>, out: OutputStream): Int {
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            fun f(v: Double?, dec: Int) = if (v == null) "" else String.format(Locale.US, "%.${dec}f", v)
            val w = out.bufferedWriter(Charsets.UTF_8)
            w.write("cas_utc;zdroj;lat_etrs89;lon_etrs89;h_ell_etrs89;presnost_h;presnost_v;stari_ms;sv;dN;dE;dU;d2;" +
                "chyba_nad_presnost;ref_lat;ref_lon;ref_h;ref_kvalita;ref_rtcm\r\n")
            for (s in samples) {
                w.write(
                    listOf(
                        iso.format(s.timeMs), PhoneSource.of(s.source)?.label ?: s.source.toString(),
                        f(s.lat, 9), f(s.lon, 9), f(s.hEll, 3), f(s.acc, 2), f(s.vAcc, 2), s.ageMs.toString(),
                        s.numSv?.toString() ?: "", f(s.dN, 3), f(s.dE, 3), f(s.dU, 3), f(s.d2, 3),
                        s.acc?.let { if (s.d2 > it) "1" else "0" } ?: "",
                        f(s.refLat, 9), f(s.refLon, 9), f(s.refH, 3), Quality.of(s.refQuality).label, if (s.refRtcm) "1" else "0",
                    ).joinToString(";")
                )
                w.write("\r\n")
            }
            w.flush()
            return samples.size
        }
    }
}
