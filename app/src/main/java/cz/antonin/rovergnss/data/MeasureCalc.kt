package cz.antonin.rovergnss.data

import cz.antonin.rovergnss.geo.Krovak
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Epocha měření v ETRS89 (anténa už odečtena). [q] = kód kvality (Quality.code). */
data class MeasureEpoch(val lat: Double, val lon: Double, val h: Double, val q: Int, val moving: Boolean, val rtcm: Boolean)

/** Výsledek měření bodu. */
data class MeasureResult(
    val epochs: Int,          // epochy s polohou bez pohybu
    val used: Int,            // epochy v průměru
    val usedQuality: Int,     // kvalita epoch v průměru (0 = smíšené, málo epoch jedné kvality)
    val nFix: Int,
    val nFloat: Int,
    val nHas: Int,
    val nDgnss: Int,
    val nOther: Int,
    val nRtcm: Int,
    val lat: Double?,
    val lon: Double?,
    val h: Double?,
    val sN: Double?,
    val sE: Double?,
    val sU: Double?,
)

/** Odchylka měření od daného bodu (měřeno − dané). */
data class MeasureOffset(
    val dN: Double?,
    val dE: Double?,
    val dU: Double?,
    val dY: Double?,
    val dX: Double?,
    val refDerived: Boolean,
) {
    val d2: Double? get() = if (dN != null && dE != null) sqrt(dN * dN + dE * dE) else null
}

/**
 * Výpočet měření bodu. Do průměru jdou jen epochy bez pohybu a jen nejlepší kvalita, které je aspoň [MIN_EPOCHS]
 * (RTK fix > float > HAS > SBAS/DGNSS > 3D); jinak všechny epochy. Rozptyl = výběrová směrodatná odchylka epoch.
 */
object MeasureCalc {
    const val MIN_EPOCHS = 10
    private val PRIORITY = listOf(4, 5, 3, 2, 1)

    // GRS80
    private const val A = 6378137.0
    private const val F = 1.0 / 298.257222101
    private const val E2 = F * (2 - F)

    /** Metrů na stupeň šířky a délky v dané šířce. */
    fun metersPerDeg(latDeg: Double): Pair<Double, Double> {
        val s = sin(Math.toRadians(latDeg))
        val w = sqrt(1 - E2 * s * s)
        val m = A * (1 - E2) / (w * w * w)
        val n = A / w
        return Math.toRadians(1.0) * m to Math.toRadians(1.0) * n * cos(Math.toRadians(latDeg))
    }

    fun compute(all: List<MeasureEpoch>): MeasureResult {
        val ok = all.filter { !it.moving && it.q in PRIORITY }
        val count = { k: Int -> ok.count { it.q == k } }
        val best = PRIORITY.firstOrNull { count(it) >= MIN_EPOCHS }
        val sel = if (best != null) ok.filter { it.q == best } else ok
        var lat: Double? = null
        var lon: Double? = null
        var h: Double? = null
        var sN: Double? = null
        var sE: Double? = null
        var sU: Double? = null
        if (sel.isNotEmpty()) {
            // průměr přes odchylky od první epochy (přesnost double u stupňů)
            val o = sel[0]
            val mLat = sel.sumOf { it.lat - o.lat } / sel.size
            val mLon = sel.sumOf { it.lon - o.lon } / sel.size
            val mH = sel.sumOf { it.h - o.h } / sel.size
            lat = o.lat + mLat
            lon = o.lon + mLon
            h = o.h + mH
            if (sel.size >= 2) {
                val (kN, kE) = metersPerDeg(lat)
                val n1 = (sel.size - 1).toDouble()
                sN = sqrt(sel.sumOf { val d = (it.lat - lat) * kN; d * d } / n1)
                sE = sqrt(sel.sumOf { val d = (it.lon - lon) * kE; d * d } / n1)
                sU = sqrt(sel.sumOf { val d = it.h - h; d * d } / n1)
            }
        }
        return MeasureResult(
            epochs = ok.size, used = sel.size, usedQuality = best ?: 0,
            nFix = count(4), nFloat = count(5), nHas = count(3), nDgnss = count(2), nOther = count(1),
            nRtcm = ok.count { it.rtcm },
            lat = lat, lon = lon, h = h, sN = sN, sE = sE, sU = sU,
        )
    }

    /**
     * Odchylka od daného bodu. ETRS89: z daných φ, λ (případně dopočtených ze S-JTSK – [MeasureOffset.refDerived]),
     * výška jen s daným h elipsoidu. S-JTSK: měření převedené 7 parametry proti daným Y, X.
     */
    fun offset(lat: Double?, lon: Double?, h: Double?, p: Point): MeasureOffset {
        if (lat == null || lon == null) return MeasureOffset(null, null, null, null, null, p.etrsDerived)
        var dN: Double? = null
        var dE: Double? = null
        if (p.lat != null && p.lon != null) {
            val (kN, kE) = metersPerDeg(p.lat)
            dN = (lat - p.lat) * kN
            dE = (lon - p.lon) * kE
        }
        // h dopočtené ze S-JTSK vychází z Bpv bez kvazigeoidu – pro výšku nepoužitelné
        val dU = if (h != null && p.hEll != null && !p.etrsDerived) h - p.hEll else null
        var dY: Double? = null
        var dX: Double? = null
        if (p.y != null && p.x != null && !p.jtskDerived) {
            val j = Krovak.fromEtrs(lat, lon, h ?: (p.hEll ?: 400.0))
            dY = j.y - p.y
            dX = j.x - p.x
        }
        return MeasureOffset(dN, dE, dU, dY, dX, p.etrsDerived)
    }
}
