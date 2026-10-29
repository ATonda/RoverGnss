package cz.antonin.rovergnss.geo

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/** ETRS89 (GRS80) → UTM, Krügerovy řady (přesnost < 1 mm v zóně). Pro export EPSG:25833 (zóna 33N). */
object Utm {
    private const val A = 6378137.0
    private const val F = 1.0 / 298.257222101
    private const val K0 = 0.9996

    /** [E, N] v metrech; [zone] 33 = poledník 15° v. d. */
    fun fromEtrs(latDeg: Double, lonDeg: Double, zone: Int = 33): DoubleArray {
        val n = F / (2 - F)
        val aa = A / (1 + n) * (1 + n * n / 4 + n * n * n * n / 64)
        val al = doubleArrayOf(
            n / 2 - 2 * n * n / 3 + 5 * n * n * n / 16,
            13 * n * n / 48 - 3 * n * n * n / 5,
            61 * n * n * n / 240,
        )
        val phi = Math.toRadians(latDeg)
        val dl = Math.toRadians(lonDeg - (zone * 6 - 183))
        val c = 2 * sqrt(n) / (1 + n)
        val t = sinh(atanh(sin(phi)) - c * atanh(c * sin(phi)))
        val xi = atan(t / cos(dl))
        val eta = atanh(sin(dl) / sqrt(1 + t * t))
        var e = eta
        var nn = xi
        for (j in 1..3) {
            e += al[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
            nn += al[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
        }
        return doubleArrayOf(500000.0 + K0 * aa * e, K0 * aa * nn)
    }

    private fun atanh(x: Double) = 0.5 * Math.log((1 + x) / (1 - x))
}
