package cz.antonin.rovergnss.export

import cz.antonin.rovergnss.data.Track
import cz.antonin.rovergnss.data.TrackDiag
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
        w.write("cas_utc;gnss_ms;kvalita;carr_soln;zdroj_ramec;lat_etrs89;lon_etrs89;h_ell_etrs89;y_jtsk;x_jtsk;h_msl_ublox;" +
            "sN;sE;sU;hAcc;vAcc;sv;pdop;rychlost;kurz;pohyb;stari_kor;rtcm;hp;latence_ms;mezera\r\n")
        val periodMs = periodMs(track, points)
        var n = 0
        var prevMs: Long? = null
        for (p in points) {
            // mezera = chybějící epochy před tímto bodem (počítá se ze všech bodů, i vyfiltrovaných)
            val gap = prevMs?.let { (((p.timeMs - it).toDouble() / periodMs).let { r -> Math.round(r) } - 1).coerceAtLeast(0) } ?: 0
            prevMs = p.timeMs
            if (!filter.accept(p)) continue
            val g = etrs(p)
            val h = g.h - track.antennaH
            val j = Krovak.fromEtrs(g.lat, g.lon, h)
            w.write(
                listOf(
                    fmt.format(p.timeMs), p.timeMs.toString(), p.quality.toString(), p.carrSoln.toString(), if (p.itrf) "ITRF2020" else "ETRS89",
                    f(g.lat, 9), f(g.lon, 9), f(h, 4), f(j.y, 3), f(j.x, 3), f(p.hMsl - track.antennaH, 3),
                    f(p.sN, 4), f(p.sE, 4), f(p.sU, 4), f(p.hAcc, 4), f(p.vAcc, 4),
                    p.numSv.toString(), f(p.pdop, 2), f(p.speed, 3), f(p.heading, 1), if (p.moving) "1" else "0",
                    f(p.corrAge, 1), if (p.rtcm) "1" else "0", if (p.hp) "1" else "0",
                    (p.rxMs - p.timeMs).toString(), gap.toString(),
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

    /** Perioda epoch [ms]: z frekvence při záznamu, jinak medián rozdílů časů. */
    private fun periodMs(track: Track, points: List<TrackPoint>): Double {
        if (track.rateHz > 0) return 1000.0 / track.rateHz
        val d = points.zipWithNext { a, b -> b.timeMs - a.timeMs }.filter { it > 0 }.sorted()
        return if (d.isEmpty()) 200.0 else d[d.size / 2].toDouble()
    }

    /** Diagnostika 1 Hz: stav roveru, satelity, čítače telefonu a NTRIP. */
    fun diagCsv(diag: List<TrackDiag>, out: OutputStream): Int {
        val fmt = iso()
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("cas_telefon;cas_gnss;baterie_v;profil;rtcm_ok;rtcm_chyb;rtcm_stari;rtcm_typ;ubx_chyb;volna_ram;" +
            "bt_zahozeno_rover;sat_sled;sat_pouz;gps;gal;bds;glo;ostatni;cn0_prumer;cn0_max;" +
            "bt_rx_b;bt_tx_b;bt_tx_zahozeno;bt_spojeni;spatny_soucet;usekle_radky;ntrip;ntrip_b;ntrip_ramce;ntrip_crc_chyb;ntrip_stari\r\n")
        for (d in diag) {
            w.write(
                listOf(
                    fmt.format(d.rxMs), d.gnssMs?.let { fmt.format(it) } ?: "", f(d.batV, 2), d.profile.toString(),
                    d.rtcmOk.toString(), d.rtcmBad.toString(), f(d.rtcmAge, 1), d.rtcmType?.toString() ?: "",
                    d.ubxErr.toString(), d.freeHeap.toString(), d.btDropped.toString(),
                    d.satTracked.toString(), d.satUsed.toString(), d.usedGps.toString(), d.usedGal.toString(),
                    d.usedBds.toString(), d.usedGlo.toString(), d.usedOther.toString(), f(d.cnoMeanUsed, 1), d.cnoMax.toString(),
                    d.btRxBytes.toString(), d.btTxBytes.toString(), d.btTxDropped.toString(), d.btReconnects.toString(),
                    d.badChecksum.toString(), d.droppedLines.toString(), d.ntripState, d.ntripBytes.toString(),
                    d.ntripFrames.toString(), d.ntripBadCrc.toString(), f(d.ntripAge, 1),
                ).joinToString(";")
            )
            w.write("\r\n")
        }
        w.flush()
        return diag.size
    }

    private fun gpxFix(q: Int) = when (q) {
        2, 3, 4, 5 -> "dgps"
        1 -> "3d"
        else -> "none"
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
