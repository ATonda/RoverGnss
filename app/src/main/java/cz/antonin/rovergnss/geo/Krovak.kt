package cz.antonin.rovergnss.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Rovinné souřadnice S-JTSK [m] (Y, X kladné), elipsoidická výška na Besselu,
 * výška Bpv (s kvazigeoidem CR-2005, jinak null) a příznak přesného převodu ČÚZK ([Sjtsk]).
 */
data class Jtsk(val y: Double, val x: Double, val hBessel: Double, val bpv: Double? = null, val precise: Boolean = false)

/**
 * S-JTSK (Křovákovo zobrazení) <-> ETRS89 přes 7prvkovou Helmertovu transformaci.
 * Přesnost ~0,4 m (ověřeno na TB Holý kopec: 0,35 m). Transformace ČÚZK s opravnou mřížkou – etapa 2.
 * Zeměpisná délka na Besselu je od Greenwiche (posun Ferro je v konstantách sinVQ/cosVQ).
 */
object Krovak {
    private const val E = 0.081696831215303
    private const val N = 0.97992470462083
    private const val RHO0 = 12310230.12797036
    private const val SIN_UQ = 0.863499969506341
    private const val COS_UQ = 0.504348889819882
    private const val SIN_VQ = 0.420215144586493
    private const val COS_VQ = 0.907424504992097
    private const val ALFA = 1.000597498371542
    private const val K = 1.003419163966575

    // S-JTSK (Bessel) -> ETRS89: posun [m], rotace ["], měřítko [ppm]
    private const val AS = PI / 648000.0
    private const val TX = 570.8285
    private const val TY = 85.6769
    private const val TZ = 462.842
    private const val WX = 4.9984 * AS
    private const val WY = 1.5867 * AS
    private const val WZ = 5.2611 * AS
    private const val M = 1 + 3.5623e-6

    private fun helmert(p: Ecef) = Ecef(
        TX + M * (p.x - WZ * p.y + WY * p.z),
        TY + M * (WZ * p.x + p.y - WX * p.z),
        TZ + M * (-WY * p.x + WX * p.y + p.z),
    )

    /** S-JTSK -> ETRS89 (h = elipsoidická výška na Besselu; pro polohu stačí přibližná). */
    fun toEtrs(y: Double, x: Double, hBessel: Double = 300.0): Geodetic {
        val ro = hypot(x, y)
        val d = 2 * atan(y / (ro + x)) / N
        val s = 2 * atan(exp(ln(RHO0 / ro) / N)) - PI / 2
        val sinS = sin(s)
        val cosS = cos(s)
        val sinU = SIN_UQ * sinS - COS_UQ * cosS * cos(d)
        val cosU = sqrt(1 - sinU * sinU)
        val sinDV = sin(d) * cosS / cosU
        val cosDV = sqrt(1 - sinDV * sinDV)
        val sinV = SIN_VQ * cosDV - COS_VQ * sinDV
        val cosV = COS_VQ * cosDV + SIN_VQ * sinDV
        val l = 2 * atan(sinV / (1 + cosV)) / ALFA
        val t = exp(2 / ALFA * ln((1 + sinU) / cosU / K))
        var p = (t - 1) / (t + 1)
        var q: Double
        do {
            q = p
            p = t * exp(E * ln((1 + E * q) / (1 - E * q)))
            p = (p - 1) / (p + 1)
        } while (abs(p - q) > 1e-15)
        val b = atan(p / sqrt(1 - p * p))
        val bes = Ellipsoid.BESSEL.toEcef(Math.toDegrees(b), Math.toDegrees(l), hBessel)
        return Ellipsoid.GRS80.fromEcef(helmert(bes))
    }

    /** ETRS89 -> S-JTSK. */
    fun fromEtrs(lat: Double, lon: Double, h: Double): Jtsk {
        val target = Ellipsoid.GRS80.toEcef(lat, lon, h)
        // inverzní Helmert iterací (rotace jsou malé, 3–4 kroky stačí na 0,1 mm)
        var x = Ecef((target.x - TX) / M, (target.y - TY) / M, (target.z - TZ) / M)
        repeat(5) {
            val f = helmert(x)
            x = Ecef(x.x + (target.x - f.x) / M, x.y + (target.y - f.y) / M, x.z + (target.z - f.z) / M)
        }
        val g = Ellipsoid.BESSEL.fromEcef(x)
        val b = Math.toRadians(g.lat)
        val l = Math.toRadians(g.lon)
        val sB = sin(b)
        val t = (1 + sB) / (1 - sB) * ((1 - E * sB) / (1 + E * sB)).pow(E)
        val w = K * t.pow(ALFA / 2)
        val u = 2 * atan(w) - PI / 2
        val dv = atan2(SIN_VQ, COS_VQ) - ALFA * l
        val sinS = SIN_UQ * sin(u) + COS_UQ * cos(u) * cos(dv)
        val cosS = sqrt(1 - sinS * sinS)
        val sinD = sin(dv) * cos(u) / cosS
        val cosD = (SIN_UQ * sinS - sin(u)) / (COS_UQ * cosS)
        val d = atan2(sinD, cosD)
        val s = asin(sinS)
        val ro = RHO0 * tan(s / 2 + PI / 4).pow(-N)
        val eps = N * d
        return Jtsk(ro * sin(eps), ro * cos(eps), g.h)
    }
}
