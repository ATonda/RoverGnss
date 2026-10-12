package cz.antonin.rovergnss.geo

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** Zadávání a výpis úhlů a čísel (desetinná čárka i tečka). */
object Angles {
    /** Číslo s desetinnou čárkou nebo tečkou, mezery ignoruje. Prázdné = null. */
    fun parseNumber(s: String): Double? {
        val t = s.trim().replace(" ", "").replace(' '.toString(), "").replace(',', '.')
        return if (t.isEmpty()) null else t.toDoubleOrNull()
    }

    /**
     * Úhel ve stupních: „49.919399“, „49,919399“, „49°55′9,8368″“, „49 55 9.8368“, „49°55'9.8368"“,
     * s volitelným N/S/E/W na konci. Vrací null, když nejde přečíst.
     */
    fun parseAngle(s: String): Double? {
        var t = s.trim().uppercase(Locale.ROOT)
        if (t.isEmpty()) return null
        var sign = 1.0
        if (t.endsWith("S") || t.endsWith("W")) sign = -1.0
        t = t.trimEnd('N', 'S', 'E', 'W', ' ')
        if (t.startsWith("-")) {
            sign = -sign
            t = t.substring(1)
        }
        t = t.replace(',', '.')
        val parts = t.split(Regex("[°º'′\"″\\s]+")).filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 3) return null
        val n = parts.map { it.toDoubleOrNull() ?: return null }
        var v = n[0]
        if (n.size > 1) {
            if (n[1] < 0 || n[1] >= 60) return null
            v += n[1] / 60.0
        }
        if (n.size > 2) {
            if (n[2] < 0 || n[2] >= 60) return null
            v += n[2] / 3600.0
        }
        return sign * v
    }

    /** 49°55′09,8368″ */
    fun dms(deg: Double, decimals: Int = 4): String {
        var a = abs(deg)
        var d = a.toInt()
        a = (a - d) * 60
        var m = a.toInt()
        var s = (a - m) * 60
        // zaokrouhlení nesmí dát 60″
        val lim = 60 - Math.pow(10.0, -decimals.toDouble()) / 2
        if (s >= lim) {
            s = 0.0
            m++
            if (m == 60) {
                m = 0
                d++
            }
        }
        val sec = String.format(Locale.US, "%0${decimals + 3}.${decimals}f", s).replace('.', ',')
        return (if (deg < 0) "-" else "") + String.format(Locale.US, "%d°%02d′", d, m) + sec + "″"
    }

    /** Vodorovná vzdálenost [m] a směrník [°] z bodu 1 do bodu 2 (krátké vzdálenosti, ETRS89). */
    fun distanceBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        val dn = (lat2 - lat1) * 111_132.95
        val de = (lon2 - lon1) * 111_319.49 * cos(Math.toRadians((lat1 + lat2) / 2))
        val az = (Math.toDegrees(atan2(de, dn)) + 360) % 360
        return hypot(dn, de) to az
    }
}
