package cz.antonin.rovergnss.export

import cz.antonin.rovergnss.data.Track
import cz.antonin.rovergnss.data.TrackPoint
import cz.antonin.rovergnss.geo.Etrs
import cz.antonin.rovergnss.geo.Geodetic
import cz.antonin.rovergnss.geo.Krovak
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Filtr bodů při exportu. */
data class ExportFilter(
    val minQuality: Set<Int> = setOf(1, 2, 3, 4, 5),  // povolené kvality (0 bez fixu a 6 dopočet vynechány)
    val maxHAcc: Double = 99.0,                        // [m]
    val skipMoving: Boolean = false,                   // vynechat epochy s pohybem (IMU)
) {
    fun accept(p: TrackPoint) = p.quality in minQuality && p.hAcc <= maxHAcc && !(skipMoving && p.moving)
}

/**
 * Export trasy. Souřadnice se převádějí do ETRS89 (HAS/SBAS z ITRF2020 podle epochy),
 * z ETRS89 do S-JTSK (7 parametrů, ~0,4 m). Výška se snižuje o výšku antény.
 * CSV: oddělovač ';', desetinná tečka (stejně jako exporty z roveru).
 */
object Exporter {
    private fun iso() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun etrs(p: TrackPoint): Geodetic =
        if (p.itrf) Etrs.itrfToEtrs(p.lat, p.lon, p.hEll, Etrs.epochYear(p.timeMs))
        else Geodetic(p.lat, p.lon, p.hEll)

    private fun f(v: Double?, dec: Int) = if (v == null) "" else String.format(Locale.US, "%.${dec}f", v)

    fun csv(track: Track, points: List<TrackPoint>, filter: ExportFilter, out: OutputStream): Int {
        val fmt = iso()
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("cas_utc;kvalita;carr_soln;zdroj_ramec;lat_etrs89;lon_etrs89;h_ell_etrs89;y_jtsk;x_jtsk;h_msl_ublox;" +
            "sN;sE;sU;hAcc;vAcc;sv;pdop;rychlost;kurz;pohyb\r\n")
        var n = 0
        for (p in points) {
            if (!filter.accept(p)) continue
            val g = etrs(p)
            val h = g.h - track.antennaH
            val j = Krovak.fromEtrs(g.lat, g.lon, h)
            w.write(
                listOf(
                    fmt.format(p.timeMs), p.quality.toString(), p.carrSoln.toString(), if (p.itrf) "ITRF2020" else "ETRS89",
                    f(g.lat, 9), f(g.lon, 9), f(h, 4), f(j.y, 3), f(j.x, 3), f(p.hMsl - track.antennaH, 3),
                    f(p.sN, 4), f(p.sE, 4), f(p.sU, 4), f(p.hAcc, 4), f(p.vAcc, 4),
                    p.numSv.toString(), f(p.pdop, 2), f(p.speed, 3), f(p.heading, 1), if (p.moving) "1" else "0",
                ).joinToString(";")
            )
            w.write("\r\n")
            n++
        }
        w.flush()
        return n
    }

    fun gpx(track: Track, points: List<TrackPoint>, filter: ExportFilter, out: OutputStream): Int {
        val fmt = iso()
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        w.write("<gpx version=\"1.1\" creator=\"RoverGnss\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        w.write("<trk><name>${xml(track.name)}</name><trkseg>\n")
        var n = 0
        for (p in points) {
            if (!filter.accept(p)) continue
            val g = etrs(p)
            // GPX: výška nad mořem (u-blox geoid), ne elipsoidická
            w.write("<trkpt lat=\"${f(g.lat, 9)}\" lon=\"${f(g.lon, 9)}\"><ele>${f(p.hMsl - track.antennaH, 3)}</ele>")
            w.write("<time>${fmt.format(p.timeMs)}</time><sat>${p.numSv}</sat>")
            w.write("<fix>${gpxFix(p.quality)}</fix><pdop>${f(p.pdop, 2)}</pdop></trkpt>\n")
            n++
        }
        w.write("</trkseg></trk>\n</gpx>\n")
        w.flush()
        return n
    }

    private fun gpxFix(q: Int) = when (q) {
        2, 3, 4, 5 -> "dgps"
        1 -> "3d"
        else -> "none"
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
