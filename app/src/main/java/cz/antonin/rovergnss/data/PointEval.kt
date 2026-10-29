package cz.antonin.rovergnss.data

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Cíl měření bodu tlačítkem REC (0.10.0, z profilu). Délky v sekundách, odchylky v metrech.
 * [minFixSec] aspoň tolik s RTK fix v úseku výsledku, [sigmaH] / [sigmaU] σ průměru vodorovně / výšky,
 * [jumpH] / [jumpV] práh skoku polohy během fixu, [timeoutSec] po této době bez splnění cíle „nejde změřit“,
 * [verify] ověření fixu (vynucené nové určení fixu a porovnání úseků).
 */
data class PointGoal(
    val minFixSec: Int = 30,
    val sigmaH: Double = 0.02,
    val sigmaU: Double = 0.03,
    val jumpH: Double = 0.05,
    val jumpV: Double = 0.10,
    val timeoutSec: Int = 180,
    val verify: Boolean = false,
)

/** Výsledek měření bodu (sloupec measurement.result). */
object PointResult {
    const val NONE = 0         // starší měření (bez vyhodnocení)
    const val OK = 1           // bod změřen (cíl splněn)
    const val NOT_REACHED = 2  // ukončeno ručně, cíl nesplněn
    const val FAILED = 3       // nejde změřit (po limitu bez splnění cíle)
    val NAMES = listOf("–", "změřen", "cíl nesplněn", "nejde změřit")
    fun name(r: Int) = NAMES.getOrElse(r) { "–" }
}

/** Stav vyhodnocení (pro UI a zvuky). */
object PointPhase {
    const val WAIT_FIX = 0     // čeká na RTK fix
    const val CONTINUE = 1     // fix je, cíl nesplněn
    const val VERIFY = 2       // cíl splněn, čeká se na ověření fixu (nové určení)
    const val DONE = 3         // bod změřen
    const val SUSPECT = 4      // podezřelý fix (úseky se liší)
    const val FAILED = 5       // nejde změřit (po limitu)
}

/** Epocha pro vyhodnocení: ETRS89, anténa odečtena. [t] čas GNSS [ms], [q] kód kvality. */
data class EvalEpoch(val t: Long, val q: Int, val lat: Double, val lon: Double, val h: Double, val moving: Boolean)

/** Úsek nepřerušeného fixu bez skoku (místní souřadnice N, E, U v metrech od počátku). */
class FixSegment(val startT: Long) {
    val n = ArrayList<Double>()
    val e = ArrayList<Double>()
    val u = ArrayList<Double>()
    var endT = startT
    val size get() = n.size
    fun meanN() = n.average()
    fun meanE() = e.average()
    fun meanU() = u.average()
    fun add(t: Long, dn: Double, de: Double, du: Double) {
        n.add(dn); e.add(de); u.add(du)
        endT = t
    }
}

/**
 * Průběžný stav měření bodu.
 * Souřadnice [lat], [lon], [h] = průměr shluku úseků s nejvíce epochami (jen RTK fix); null = zatím bez fixu.
 * [sH], [sU] σ epoch, [sMeanH], [sMeanU] σ průměru (korelace epoch: efektivní počet = doba fixu / [PointEvaluator.TAU_S]).
 */
data class PointStatus(
    val phase: Int,
    val elapsedSec: Double,
    val inFix: Boolean,
    val lastQuality: Int,
    val epochs: Int,
    val nFix: Int,
    val nFloat: Int,
    val nOther: Int,
    val fixSec: Double,          // všechny epochy s fixem
    val clusterFixSec: Double,   // fix v shluku výsledku
    val fixPct: Double?,         // podíl epoch s fixem [%]
    val lat: Double?,
    val lon: Double?,
    val h: Double?,
    val sN: Double?,
    val sE: Double?,
    val sU: Double?,
    val sMeanH: Double?,
    val sMeanU: Double?,
    val maxDev: Double?,         // největší vodorovná odchylka epochy od průměru [m]
    val segments: Int,           // úseky fixu (nové určení fixu nebo skok)
    val jumps: Int,              // skoky během fixu
    val clusters: Int,           // významné shluky úseků (≥ 5 s); > 1 = podezřelé
    val clusterDiffH: Double?,   // rozdíl dvou největších shluků vodorovně [m]
    val clusterDiffV: Double?,
    val independent: Int,        // nezávislá určení fixu (úseky) ve shluku výsledku
    val progress: Double,        // splnění cíle 0–1
    val missing: String,         // co chybí („chybí 12 s fixu“ …)
    val failReason: String?,     // důvod „nejde změřit“
)

/**
 * Vyhodnocení měření bodu (REC). Úsek = nepřerušený RTK fix; nový úsek začne po ztrátě fixu, po pohybu (IMU)
 * a po skoku (3 epochy po sobě dál než práh od průměru úseku). Úseky, které se shodují do prahu skoku, tvoří shluk
 * (nezávislá určení fixu = ověření); výsledek je shluk s nejvíce epochami. Dva významné shluky = podezřelý fix.
 */
class PointEvaluator(val goal: PointGoal, private val periodSec: () -> Double) {
    private var lat0 = Double.NaN
    private var lon0 = Double.NaN
    private var h0 = 0.0
    private var kN = 0.0
    private var kE = 0.0
    private val segs = ArrayList<FixSegment>()
    private var cur: FixSegment? = null
    private val pending = ArrayList<DoubleArray>()   // kandidáti skoku [t, n, e, u]
    private var lastT = 0L
    var epochs = 0
        private set
    var nFix = 0
        private set
    var nFloat = 0
        private set
    var nOther = 0
        private set
    var jumps = 0
        private set
    var lastJumpT = 0L
        private set
    var lastQuality = 0
        private set
    var lastFixT = 0L
        private set
    /** Čas GNSS poslední epochy. */
    val lastTime: Long get() = lastT

    @Synchronized
    fun add(ep: EvalEpoch) {
        if (ep.t <= lastT) return   // epochy ze zásobníku roveru mimo pořadí jen do souboru, ne do vyhodnocení
        lastT = ep.t
        epochs++
        lastQuality = ep.q
        when (ep.q) {
            FIX -> nFix++
            FLOAT -> nFloat++
            else -> nOther++
        }
        if (ep.q != FIX || ep.moving) {
            closeSegment()
            return
        }
        lastFixT = ep.t
        if (lat0.isNaN()) {
            lat0 = ep.lat
            lon0 = ep.lon
            h0 = ep.h
            val (a, b) = MeasureCalc.metersPerDeg(ep.lat)
            kN = a
            kE = b
        }
        val dn = (ep.lat - lat0) * kN
        val de = (ep.lon - lon0) * kE
        val du = ep.h - h0
        val s = cur
        if (s == null) {
            cur = FixSegment(ep.t).also { it.add(ep.t, dn, de, du); segs.add(it) }
            return
        }
        if (s.size >= 5) {
            val off = hypot(dn - s.meanN(), de - s.meanE())
            val offV = abs(du - s.meanU())
            if (off > goal.jumpH || offV > goal.jumpV) {
                pending.add(doubleArrayOf(ep.t.toDouble(), dn, de, du))
                if (pending.size >= JUMP_EPOCHS) {
                    // skok: nový úsek z čekajících epoch
                    val ns = FixSegment(pending[0][0].toLong())
                    pending.forEach { ns.add(it[0].toLong(), it[1], it[2], it[3]) }
                    pending.clear()
                    segs.add(ns)
                    cur = ns
                    jumps++
                    lastJumpT = ep.t
                }
                return
            }
        }
        // jednotlivé odlehlé epochy (ne skok) zůstávají v úseku
        pending.forEach { s.add(it[0].toLong(), it[1], it[2], it[3]) }
        pending.clear()
        s.add(ep.t, dn, de, du)
    }

    private fun closeSegment() {
        cur?.let { s -> pending.forEach { s.add(it[0].toLong(), it[1], it[2], it[3]) } }
        pending.clear()
        cur = null
    }

    /** Shluk úseků se shodnou polohou (do prahu skoku). */
    private class Cluster {
        val segs = ArrayList<FixSegment>()
        var n = 0
        var sn = 0.0
        var se = 0.0
        var su = 0.0
        fun mean() = doubleArrayOf(sn / n, se / n, su / n)
        fun add(s: FixSegment) {
            segs.add(s)
            n += s.size
            sn += s.n.sum()
            se += s.e.sum()
            su += s.u.sum()
        }
    }

    private fun clusters(): List<Cluster> {
        val out = ArrayList<Cluster>()
        for (s in segs.filter { it.size > 0 }.sortedByDescending { it.size }) {
            val sm = doubleArrayOf(s.meanN(), s.meanE(), s.meanU())
            val c = out.firstOrNull {
                val m = it.mean()
                hypot(sm[0] - m[0], sm[1] - m[1]) <= goal.jumpH && abs(sm[2] - m[2]) <= goal.jumpV
            }
            (c ?: Cluster().also { out.add(it) }).add(s)
        }
        return out.sortedByDescending { it.n }
    }

    /** Vyhodnocení k okamžiku [elapsedSec] od začátku měření; [verifyRequested] už bylo vynuceno nové určení fixu. */
    @Synchronized
    fun status(elapsedSec: Double, verifyRequested: Boolean): PointStatus {
        val per = periodSec().takeIf { it > 0 } ?: 0.2
        val cl = clusters()
        val best = cl.firstOrNull()
        val signif = cl.filter { it.n * per >= SIGNIF_SEC }
        val fixSec = nFix * per
        val clusterFixSec = (best?.n ?: 0) * per
        var lat: Double? = null
        var lon: Double? = null
        var h: Double? = null
        var sN: Double? = null
        var sE: Double? = null
        var sU: Double? = null
        var sMeanH: Double? = null
        var sMeanU: Double? = null
        var maxDev: Double? = null
        if (best != null && best.n > 0) {
            val m = best.mean()
            lat = lat0 + m[0] / kN
            lon = lon0 + m[1] / kE
            h = h0 + m[2]
            if (best.n >= 2) {
                var qn = 0.0
                var qe = 0.0
                var qu = 0.0
                var md = 0.0
                best.segs.forEach { s ->
                    for (i in 0 until s.size) {
                        val a = s.n[i] - m[0]
                        val b = s.e[i] - m[1]
                        val c = s.u[i] - m[2]
                        qn += a * a
                        qe += b * b
                        qu += c * c
                        md = max(md, hypot(a, b))
                    }
                }
                val n1 = (best.n - 1).toDouble()
                sN = sqrt(qn / n1)
                sE = sqrt(qe / n1)
                sU = sqrt(qu / n1)
                maxDev = md
                val neff = max(1.0, clusterFixSec / TAU_S)
                sMeanH = hypot(sN, sE) / sqrt(neff)
                sMeanU = sU / sqrt(neff)
            }
        }
        var diffH: Double? = null
        var diffV: Double? = null
        if (signif.size >= 2) {
            val a = signif[0].mean()
            val b = signif[1].mean()
            diffH = hypot(a[0] - b[0], a[1] - b[1])
            diffV = abs(a[2] - b[2])
        }
        val suspect = signif.size >= 2
        val inFix = lastQuality == FIX
        val recentJump = lastJumpT > 0 && (lastT - lastJumpT) / 1000.0 < JUMP_QUIET_S
        val independent = best?.segs?.size ?: 0
        val newestInBest = best?.segs?.maxByOrNull { it.endT }
        val newestSec = (newestInBest?.size ?: 0) * per

        // splnění jednotlivých podmínek (0–1)
        val pTime = (clusterFixSec / goal.minFixSec).coerceIn(0.0, 1.0)
        val pH = sMeanH?.let { (goal.sigmaH / max(it, 1e-6)).coerceIn(0.0, 1.0) } ?: 0.0
        val pU = sMeanU?.let { (goal.sigmaU / max(it, 1e-6)).coerceIn(0.0, 1.0) } ?: 0.0
        val baseOk = pTime >= 1.0 && pH >= 1.0 && pU >= 1.0 && inFix && !recentJump && !suspect
        val verified = !goal.verify || (independent >= 2 && newestSec >= VERIFY_MIN_S)
        var progress = minOf(pTime, pH, pU)
        if (goal.verify && !verified) progress = minOf(progress, 0.9)
        if (suspect || recentJump) progress = minOf(progress, 0.5)

        val miss = ArrayList<String>()
        if (!inFix) miss += if (lastQuality == FLOAT) "čekám na fix (float)" else "čekám na fix"
        if (pTime < 1.0) miss += "chybí ${Math.ceil(goal.minFixSec - clusterFixSec).toInt()} s fixu"
        if (sMeanH != null && pH < 1.0) miss += "rozptyl ${cm(sMeanH)} → cíl ${cm(goal.sigmaH)} cm"
        if (sMeanU != null && pU < 1.0) miss += "výška ${cm(sMeanU)} → cíl ${cm(goal.sigmaU)} cm"
        if (recentJump) miss += "po skoku čekám ${Math.ceil(JUMP_QUIET_S - (lastT - lastJumpT) / 1000.0).toInt()} s"
        if (suspect) miss += "úseky fixu se liší o ${cm(diffH ?: 0.0)} cm (výška ${cm(diffV ?: 0.0)} cm)"
        if (baseOk && !verified) miss += if (!verifyRequested) "ověření fixu" else "ověřuji – čekám na nový fix"

        val done = baseOk && verified
        val timeout = elapsedSec >= goal.timeoutSec
        val phase = when {
            done -> PointPhase.DONE
            timeout -> PointPhase.FAILED
            suspect -> PointPhase.SUSPECT
            baseOk && !verified -> PointPhase.VERIFY
            !inFix && nFix == 0 -> PointPhase.WAIT_FIX
            !inFix -> PointPhase.WAIT_FIX
            else -> PointPhase.CONTINUE
        }
        val failReason = if (timeout && !done) when {
            nFix == 0 && nFloat > 0 -> "jen float, fix nenaskočil"
            nFix == 0 -> "bez korekcí / bez fixu"
            suspect -> "nestabilní fix – úseky se liší o ${cm(diffH ?: 0.0)} cm"
            recentJump || jumps > 0 -> "nestabilní fix (skoky)"
            pTime < 1.0 -> "fix jen ${Math.round(clusterFixSec)} s z ${goal.minFixSec} s"
            pH < 1.0 || pU < 1.0 -> "velký rozptyl"
            !verified -> "fix se nepodařilo ověřit"
            else -> "cíl nesplněn"
        } else null

        return PointStatus(
            phase = phase, elapsedSec = elapsedSec, inFix = inFix, lastQuality = lastQuality,
            epochs = epochs, nFix = nFix, nFloat = nFloat, nOther = nOther,
            fixSec = fixSec, clusterFixSec = clusterFixSec,
            fixPct = if (epochs > 0) 100.0 * nFix / epochs else null,
            lat = lat, lon = lon, h = h, sN = sN, sE = sE, sU = sU, sMeanH = sMeanH, sMeanU = sMeanU, maxDev = maxDev,
            segments = segs.count { it.size > 0 }, jumps = jumps, clusters = signif.size,
            clusterDiffH = diffH, clusterDiffV = diffV, independent = independent,
            progress = if (done) 1.0 else progress, missing = miss.joinToString(", "), failReason = failReason,
        )
    }

    /** Úseky pro souhrn: [začátek, konec, epoch, dN, dE, dU od výsledku]. */
    @Synchronized
    fun segmentSummary(): List<DoubleArray> {
        val best = clusters().firstOrNull() ?: return emptyList()
        val m = best.mean()
        return segs.filter { it.size > 0 }.map {
            doubleArrayOf(it.startT.toDouble(), it.endT.toDouble(), it.size.toDouble(),
                it.meanN() - m[0], it.meanE() - m[1], it.meanU() - m[2])
        }
    }

    companion object {
        const val FIX = 4
        const val FLOAT = 5
        const val JUMP_EPOCHS = 3      // tolik epoch po sobě mimo práh = skok
        const val JUMP_QUIET_S = 30.0  // po skoku aspoň tolik s klidu
        const val SIGNIF_SEC = 5.0     // shluk kratší než 5 s se do podezření nepočítá
        const val VERIFY_MIN_S = 10.0  // nové určení fixu aspoň 10 s
        const val TAU_S = 10.0         // korelační doba epoch pro σ průměru [s]

        fun cm(m: Double): String = String.format(java.util.Locale.US, "%.1f", m * 100).replace('.', ',')
    }
}
