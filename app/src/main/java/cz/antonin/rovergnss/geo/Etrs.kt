package cz.antonin.rovergnss.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Bod v geocentrických souřadnicích [m]. */
data class Ecef(val x: Double, val y: Double, val z: Double)

/** Zeměpisné souřadnice [°] a elipsoidická výška [m]. */
data class Geodetic(val lat: Double, val lon: Double, val h: Double)

/** Elipsoid. */
class Ellipsoid(val a: Double, val e2: Double) {
    fun toEcef(latDeg: Double, lonDeg: Double, h: Double): Ecef {
        val la = Math.toRadians(latDeg)
        val lo = Math.toRadians(lonDeg)
        val s = sin(la)
        val n = a / sqrt(1 - e2 * s * s)
        return Ecef((n + h) * cos(la) * cos(lo), (n + h) * cos(la) * sin(lo), (n * (1 - e2) + h) * s)
    }

    fun fromEcef(p: Ecef): Geodetic {
        val r = hypot(p.x, p.y)
        var la = atan2(p.z, r * (1 - e2))
        var h = 0.0
        repeat(8) {
            val s = sin(la)
            val n = a / sqrt(1 - e2 * s * s)
            h = r / cos(la) - n
            la = atan2(p.z, r * (1 - e2 * n / (n + h)))
        }
        return Geodetic(Math.toDegrees(la), Math.toDegrees(atan2(p.y, p.x)), h)
    }

    companion object {
        private const val F_GRS80 = 1.0 / 298.257222101
        val GRS80 = Ellipsoid(6378137.0, F_GRS80 * (2 - F_GRS80))
        val BESSEL = Ellipsoid(6377397.15508, 0.081696831215303 * 0.081696831215303)
    }
}

/**
 * ITRF2020 -> ETRS89 (ETRF2020): rotace euroasijské desky od epochy 1989,0.
 * V ČR v roce 2026 posun ≈ N −0,57 m, E −0,74 m. Stejný postup jako firmware (geo.cpp).
 */
object Etrs {
    private const val MAS = Math.PI / 180.0 / 3_600_000.0
    private const val R1 = 0.086  // [mas/rok]
    private const val R2 = 0.519
    private const val R3 = -0.753

    fun itrfToEtrs(p: Ecef, epochYear: Double): Ecef {
        val dt = epochYear - 1989.0
        val r1 = R1 * MAS * dt
        val r2 = R2 * MAS * dt
        val r3 = R3 * MAS * dt
        return Ecef(p.x - r3 * p.y + r2 * p.z, p.y + r3 * p.x - r1 * p.z, p.z - r2 * p.x + r1 * p.y)
    }

    fun itrfToEtrs(lat: Double, lon: Double, h: Double, epochYear: Double): Geodetic =
        Ellipsoid.GRS80.fromEcef(itrfToEtrs(Ellipsoid.GRS80.toEcef(lat, lon, h), epochYear))

    /** Epocha v letech (např. 2026.78) z času UTC [ms]. */
    fun epochYear(utcMs: Long): Double = 1970.0 + utcMs / (365.2425 * 86_400_000.0)
}
