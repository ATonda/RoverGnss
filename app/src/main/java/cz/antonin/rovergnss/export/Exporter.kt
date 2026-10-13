package cz.antonin.rovergnss.export

import cz.antonin.rovergnss.data.Measurement
import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.data.Track
import cz.antonin.rovergnss.data.TrackDiag
import cz.antonin.rovergnss.data.TrackPoint
import cz.antonin.rovergnss.geo.Etrs
import cz.antonin.rovergnss.geo.Geodetic
import cz.antonin.rovergnss.geo.Sjtsk
import cz.antonin.rovergnss.link.Quality
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
 * z ETRS89 do S-JTSK a Bpv přesným převodem ČÚZK ([Sjtsk]). Výška se snižuje o výšku antény.
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
        w.write("cas_utc;gnss_ms;kvalita;carr_soln;zdroj_ramec;lat_etrs89;lon_etrs89;h_ell_etrs89;y_jtsk;x_jtsk;h_bpv;jtsk_presne;h_msl_ublox;" +
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
            val j = Sjtsk.fromEtrs(g.lat, g.lon, h)
            w.write(
                listOf(
                    fmt.format(p.timeMs), p.timeMs.toString(), p.quality.toString(), p.carrSoln.toString(), if (p.itrf) "ITRF2020" else "ETRS89",
                    f(g.lat, 9), f(g.lon, 9), f(h, 4), f(j.y, 3), f(j.x, 3), f(j.bpv, 3), if (j.precise) "1" else "0",
                    f(p.hMsl - track.antennaH, 3),
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
            "bt_rx_b;bt_tx_b;bt_tx_zahozeno;bt_spojeni;spatny_soucet;usekle_radky;ntrip;ntrip_b;ntrip_ramce;ntrip_crc_chyb;ntrip_stari;" +
            "popredi;sit;sit_rx_b;spojeni_kvalita;latence_ms;ztraty_pct;ztraty_celkem;bt_fronta_rover_b;hp_epoch;rezim_app\r\n")
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
                    if (d.foreground) "1" else "0", d.network, d.netRxBytes.toString(), d.linkBars.toString(),
                    d.latencyMs?.toString() ?: "", f(d.lossPct, 1), d.lostTotal.toString(), d.btBacklog?.toString() ?: "",
                    d.hpCount?.toString() ?: "", if (d.appMode) "1" else "0",
                ).joinToString(";")
            )
            w.write("\r\n")
        }
        w.flush()
        return diag.size
    }

    /** Měření bodů: souhrn každé relace, odchylky = měřeno − dané. */
    fun measurementsCsv(points: Map<Long, Point>, list: List<Measurement>, out: OutputStream): Int {
        val fmt = iso()
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("bod;druh;zacatek_utc;konec_utc;delka_s;epoch;v_prumeru;kvalita_prumeru;fix;float;has;sbas;gnss3d;korekce;profil;" +
            "vyska_anteny;lat_etrs89;lon_etrs89;h_ell_etrs89;sN;sE;sU;dN;dE;d2D;dU;dY;dX;dH_bpv;dane_etrs_dopocteno;staticky_model\r\n")
        var n = 0
        for (m in list) {
            if (m.endMs == null) continue
            val p = points[m.pointId]
            val d2 = if (m.dN != null && m.dE != null) Math.hypot(m.dN, m.dE) else null
            w.write(
                listOf(
                    p?.name ?: m.pointId.toString(), p?.kind ?: "", fmt.format(m.startMs), fmt.format(m.endMs),
                    ((m.endMs - m.startMs) / 1000).toString(), m.epochs.toString(), m.used.toString(),
                    if (m.usedQuality == 0) "smisena" else Quality.of(m.usedQuality).label,
                    m.nFix.toString(), m.nFloat.toString(), m.nHas.toString(), m.nDgnss.toString(), m.nOther.toString(),
                    m.corr, m.profile, f(m.antennaH, 3), f(m.lat, 9), f(m.lon, 9), f(m.hEll, 4),
                    f(m.sN, 4), f(m.sE, 4), f(m.sU, 4), f(m.dN, 4), f(m.dE, 4), f(d2, 4), f(m.dU, 4), f(m.dY, 4), f(m.dX, 4),
                    f(m.dH, 4), if (m.refDerived) "1" else "0", if (m.staticModel) "1" else "0",
                ).joinToString(";") { it.replace(";", ",") }
            )
            w.write("\r\n")
            n++
        }
        w.flush()
        return n
    }

    /** Body do KML (Google Earth, mapy): ETRS89, výška elipsoidu; v popisu S-JTSK a Bpv. */
    fun pointsKml(name: String, points: List<Point>, out: OutputStream): Int {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>\n")
        w.write("<name>${xml(name)}</name>\n")
        var n = 0
        for (p in points) {
            if (p.lat == null || p.lon == null) continue
            val desc = listOfNotNull(
                p.kind.ifEmpty { null },
                if (p.y != null && p.x != null) "Y ${f(p.y, 3)} X ${f(p.x, 3)}" else null,
                p.bpv?.let { "Bpv ${f(it, 3)}" },
                p.note.ifEmpty { null },
            ).joinToString(", ")
            w.write("<Placemark><name>${xml(p.name)}</name><description>${xml(desc)}</description>")
            w.write("<Point><coordinates>${f(p.lon, 9)},${f(p.lat, 9)},${f(p.hEll ?: 0.0, 3)}</coordinates></Point></Placemark>\n")
            n++
        }
        w.write("</Document></kml>\n")
        w.flush()
        return n
    }

    /** Protokol vytyčení: dané souřadnice, vytyčená poloha a odchylka vytyčeno − dané. */
    fun stakeCsv(points: List<Point>, out: OutputStream): Int {
        val fmt = iso()
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("bod;druh;y_jtsk;x_jtsk;bpv;lat_etrs89;lon_etrs89;vytyceno_utc;lat_vytyceno;lon_vytyceno;h_ell_vytyceno;" +
            "dN;dE;d2D;dY;dX;dH\r\n")
        var n = 0
        for (p in points) {
            val d2 = if (p.stakeDN != null && p.stakeDE != null) Math.hypot(p.stakeDN, p.stakeDE) else null
            w.write(
                listOf(
                    p.name, p.kind, f(p.y, 3), f(p.x, 3), f(p.bpv, 3), f(p.lat, 9), f(p.lon, 9),
                    p.stakeMs?.let { fmt.format(it) } ?: "", f(p.stakeLat, 9), f(p.stakeLon, 9), f(p.stakeH, 4),
                    f(p.stakeDN, 4), f(p.stakeDE, 4), f(d2, 4), f(p.stakeDY, 4), f(p.stakeDX, 4), f(p.stakeDH, 4),
                ).joinToString(";") { it.replace(";", ",") }
            )
            w.write("\r\n")
            n++
        }
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
