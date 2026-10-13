package cz.antonin.rovergnss.geo

import java.io.InputStream
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Převod ETRS89 (ETRF2000) ↔ S-JTSK a výška Bpv podle metodiky ČÚZK / VÚGTK
 * „Metodika převodu mezi ETRF2000 a S-JTSK, varianta 2“ (2010):
 *  ETRS89 → 7 parametrů → Bessel → modifikované Křovákovo zobrazení (S-JTSK/05) → oprava z tabulky → S-JTSK,
 *  Bpv = h elipsoidu − výška kvazigeoidu CR-2005.
 * Tabulky: table_yx_3_v1710.dat (S-JTSK/05 − S-JTSK v síti 2 × 2 km, měření od 2018) a CR-2005_v1005.dat
 * (kvazigeoid 1′ × 1,5′). Interpolace bilineární (ověřeno na TB 1115-010: ΔY 0,001, ΔX 0,012, ΔBpv 0,015 m).
 * Bez načtených tabulek nebo mimo jejich rozsah se použije 7 parametrů ([Krovak], ~0,4 m) a [Jtsk.precise] = false.
 */
object Sjtsk {
    // ---------------------------------------------------------------- tabulky
    private class Table(val map: HashMap<Long, DoubleArray>)
    private class Geoid(val b0: Double, val l0: Double, val rows: Int, val cols: Int, val v: DoubleArray)

    @Volatile private var table: Table? = null
    @Volatile private var geoid: Geoid? = null

    val ready: Boolean get() = table != null && geoid != null

    private fun key(y: Int, x: Int): Long = y.toLong() * 10_000_000L + x

    /** Načte opravnou tabulku (řádky: Y X dY dX). */
    fun loadTable(input: InputStream) {
        val m = HashMap<Long, DoubleArray>(32_000)
        input.bufferedReader().forEachLine { line ->
            val a = line.trim().split(Regex("\\s+"))
            if (a.size >= 4) {
                val y = a[0].toDoubleOrNull()
                val x = a[1].toDoubleOrNull()
                val dy = a[2].toDoubleOrNull()
                val dx = a[3].toDoubleOrNull()
                if (y != null && x != null && dy != null && dx != null) m[key(y.toInt(), x.toInt())] = doubleArrayOf(dy, dx)
            }
        }
        if (m.isNotEmpty()) table = Table(m)
    }

    /** Načte kvazigeoid (1. řádek: sloupců řádků krokB krokL, 2. nepodstatný, dále B L výška). */
    fun loadGeoid(input: InputStream) {
        val lines = input.bufferedReader().readLines()
        if (lines.size < 3) return
        val h = lines[0].trim().split(Regex("\\s+"))
        val cols = h[0].toInt()
        val rows = h[1].toInt()
        val v = DoubleArray(rows * cols) { Double.NaN }
        var b0 = Double.NaN
        var l0 = Double.NaN
        for (i in 2 until lines.size) {
            val a = lines[i].trim().split(Regex("\\s+"))
            if (a.size < 3) continue
            val b = a[0].toDouble()
            val l = a[1].toDouble()
            if (b0.isNaN()) {
                b0 = b
                l0 = l
            }
            val r = Math.round((b - b0) * 60).toInt()
            val c = Math.round((l - l0) * 40).toInt()
            if (r in 0 until rows && c in 0 until cols) v[r * cols + c] = a[2].toDouble()
        }
        if (!b0.isNaN()) geoid = Geoid(b0, l0, rows, cols, v)
    }

    /** Výška kvazigeoidu CR-2005 [m] (h elipsoidu − Bpv), null = mimo síť nebo nenačteno. */
    fun zeta(lat: Double, lon: Double): Double? {
        val g = geoid ?: return null
        val fr = (lat - g.b0) * 60
        val fc = (lon - g.l0) * 40
        val r = floor(fr).toInt()
        val c = floor(fc).toInt()
        if (r < 0 || c < 0 || r + 1 >= g.rows || c + 1 >= g.cols) return null
        val tr = fr - r
        val tc = fc - c
        val v00 = g.v[r * g.cols + c]
        val v01 = g.v[r * g.cols + c + 1]
        val v10 = g.v[(r + 1) * g.cols + c]
        val v11 = g.v[(r + 1) * g.cols + c + 1]
        if (v00.isNaN() || v01.isNaN() || v10.isNaN() || v11.isNaN()) return null
        return v00 * (1 - tr) * (1 - tc) + v01 * (1 - tr) * tc + v10 * tr * (1 - tc) + v11 * tr * tc
    }

    /** Výška Bpv z elipsoidické výšky ETRS89. */
    fun bpv(lat: Double, lon: Double, h: Double): Double? = zeta(lat, lon)?.let { h - it }

    /** Oprava S-JTSK/05 − S-JTSK [dY, dX] v bodě S-JTSK/05 (bez 5 000 000), null = mimo tabulku. */
    private fun correction(y05: Double, x05: Double): DoubleArray? {
        val t = table ?: return null
        val y0 = (floor(y05 / 2000) * 2000).toInt()
        val x0 = (floor(x05 / 2000) * 2000).toInt()
        val ty = (y05 - y0) / 2000
        val tx = (x05 - x0) / 2000
        val c00 = t.map[key(y0, x0)] ?: return null
        val c01 = t.map[key(y0, x0 + 2000)] ?: return null
        val c10 = t.map[key(y0 + 2000, x0)] ?: return null
        val c11 = t.map[key(y0 + 2000, x0 + 2000)] ?: return null
        return DoubleArray(2) { k ->
            c00[k] * (1 - ty) * (1 - tx) + c01[k] * (1 - ty) * tx + c10[k] * ty * (1 - tx) + c11[k] * ty * tx
        }
    }

    // ---------------------------------------------------------------- 7 parametrů ETRF2000 ↔ S-JTSK/05
    private const val RHO_SEC = 206264.806
    private val BESSEL = Ellipsoid(6377397.155, 0.0066743722306)
    private val GRS80 = Ellipsoid(6378137.0, 0.0066943800229)

    private fun helmert(p: Ecef, t: DoubleArray): Ecef {
        val m = 1 + t[3] * 1e-6
        val r5 = t[4] / RHO_SEC
        val r6 = t[5] / RHO_SEC
        val r7 = t[6] / RHO_SEC
        return Ecef(
            m * (p.x + r5 * p.y - r6 * p.z) + t[0],
            m * (-r5 * p.x + p.y + r7 * p.z) + t[1],
            m * (r6 * p.x - r7 * p.y + p.z) + t[2],
        )
    }

    private val TO_05 = doubleArrayOf(-572.203, -85.328, -461.934, -3.5393, 5.24832714, 1.52900087, 4.97311727)

    // ---------------------------------------------------------------- modifikované Křovákovo zobrazení
    private val E = sqrt(0.0066743722306)
    private val PHI0 = Math.toRadians(49.5)
    private val ALFA = sqrt(1 + 0.0066743722306 * cos(PHI0).pow(4) / (1 - 0.0066743722306))
    private val UQ = Math.toRadians(59.0 + 42.0 / 60 + 42.69689 / 3600)
    private val U0 = asin(sin(PHI0) / ALFA)
    private val G0 = ((1 + E * sin(PHI0)) / (1 - E * sin(PHI0))).pow(ALFA * E / 2)
    private val KK = tan(U0 / 2 + PI / 4) / tan(PHI0 / 2 + PI / 4).pow(ALFA) * G0
    private val N0 = 6377397.155 * sqrt(1 - 0.0066743722306) / (1 - 0.0066743722306 * sin(PHI0) * sin(PHI0))
    private val S0 = Math.toRadians(78.5)
    private val NN = sin(S0)
    private val RHO0 = 0.9999 * N0 / tan(S0)
    private val A = doubleArrayOf(
        0.2946529277e-01, 0.2515965696e-01, 0.1193845912e-06, -0.4668270147e-06, 0.9233980362e-11,
        0.1523735715e-11, 0.1696780024e-17, 0.4408314235e-17, -0.8331083518e-23, -0.3689471323e-23,
    )

    /** Křovák: B, L na Besselu [°] → Y′, X′ (kladné, bez dotransformace). */
    private fun krovak(bDeg: Double, lDeg: Double): DoubleArray {
        val b = Math.toRadians(bDeg)
        val l = Math.toRadians(lDeg)
        val g = ((1 + E * sin(b)) / (1 - E * sin(b))).pow(ALFA * E / 2)
        val u = 2 * (atan(KK * tan(b / 2 + PI / 4).pow(ALFA) / g) - PI / 4)
        val lam = l + Math.toRadians(17.0 + 40.0 / 60)
        val dv = ALFA * (Math.toRadians(42.5) - lam)
        val a1 = PI / 2 - UQ
        val s = asin(cos(a1) * sin(u) + sin(a1) * cos(u) * cos(dv))
        val d = asin(cos(u) * sin(dv) / cos(s))
        val eps = NN * d
        val rho = RHO0 * tan(S0 / 2 + PI / 4).pow(NN) / tan(s / 2 + PI / 4).pow(NN)
        return doubleArrayOf(rho * sin(eps), rho * cos(eps))
    }

    /** Dotransformace ΔY, ΔX (výrazy (2) metodiky) z Y′, X′. */
    private fun dot(yp: Double, xp: Double): DoubleArray {
        val y = yp - 654000.0
        val x = xp - 1089000.0
        val a1 = A[0]
        val a2 = A[1]
        val a3 = A[2]
        val a4 = A[3]
        val a5 = A[4]
        val a6 = A[5]
        val a7 = A[6]
        val a8 = A[7]
        val a9 = A[8]
        val a10 = A[9]
        val x2 = x * x
        val y2 = y * y
        val q4 = x2 * x2 + y2 * y2 - 6 * x2 * y2
        val dY = a2 + a3 * y + a4 * x + 2 * a5 * y * x + a6 * (x2 - y2) + a8 * x * (x2 - 3 * y2) -
            a7 * y * (3 * x2 - y2) - 4 * a10 * y * x * (x2 - y2) + a9 * q4
        val dX = a1 + a3 * x - a4 * y - 2 * a6 * y * x + a5 * (x2 - y2) + a7 * x * (x2 - 3 * y2) -
            a8 * y * (3 * x2 - y2) + 4 * a9 * y * x * (x2 - y2) + a10 * q4
        return doubleArrayOf(dY, dX)
    }

    /** ETRS89 → S-JTSK/05 (bez 5 000 000) a výška na Besselu. */
    private fun to05(lat: Double, lon: Double, h: Double): DoubleArray {
        val bes = BESSEL.fromEcef(helmert(GRS80.toEcef(lat, lon, h), TO_05))
        val k = krovak(bes.lat, bes.lon)
        val d = dot(k[0], k[1])
        return doubleArrayOf(k[0] - d[0], k[1] - d[1], bes.h)
    }

    // ---------------------------------------------------------------- veřejné převody
    /** ETRS89 → S-JTSK (přesně s tabulkami, jinak 7 parametrů) a Bpv (jen s kvazigeoidem). */
    fun fromEtrs(lat: Double, lon: Double, h: Double): Jtsk {
        if (table != null) {
            val s = to05(lat, lon, h)
            val c = correction(s[0], s[1])
            if (c != null) return Jtsk(s[0] - c[0], s[1] - c[1], s[2], bpv(lat, lon, h), true)
        }
        val j = Krovak.fromEtrs(lat, lon, h)
        return j.copy(bpv = bpv(lat, lon, h))
    }

    /**
     * S-JTSK → ETRS89. Poloha iterací přesného převodu (start ze 7 parametrů), výška:
     * s [bpv] a kvazigeoidem h = Bpv + kvazigeoid, jinak přibližně (Bpv nebo 400 m jako výška na Besselu).
     * @return poloha a příznak, zda šlo o přesný převod
     */
    fun toEtrs(y: Double, x: Double, bpv: Double?): Pair<Geodetic, Boolean> {
        var g = Krovak.toEtrs(y, x, bpv ?: 400.0)
        if (table == null) return g to false
        var h = g.h
        var ok = true
        for (iter in 0 until 6) {
            h = bpv?.let { b -> zeta(g.lat, g.lon)?.let { b + it } } ?: g.h
            val f = fromEtrs(g.lat, g.lon, h)
            if (!f.precise) {
                ok = false
                break
            }
            val dy = y - f.y
            val dx = x - f.x
            if (hypot(dy, dx) < 1e-5) break
            // parciální derivace numericky (Y roste k západu, X k jihu)
            val e = 1e-6
            val fl = fromEtrs(g.lat + e, g.lon, h)
            val fo = fromEtrs(g.lat, g.lon + e, h)
            val j11 = (fl.y - f.y) / e
            val j12 = (fo.y - f.y) / e
            val j21 = (fl.x - f.x) / e
            val j22 = (fo.x - f.x) / e
            val det = j11 * j22 - j12 * j21
            val dLat = (dy * j22 - j12 * dx) / det
            val dLon = (j11 * dx - j21 * dy) / det
            g = Geodetic(g.lat + dLat, g.lon + dLon, h)
        }
        return Geodetic(g.lat, g.lon, h) to ok
    }
}
