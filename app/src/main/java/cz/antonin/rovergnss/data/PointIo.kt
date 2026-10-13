package cz.antonin.rovergnss.data

import cz.antonin.rovergnss.geo.Angles
import cz.antonin.rovergnss.geo.Sjtsk
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Doplnění druhého systému souřadnic přesným převodem ČÚZK ([Sjtsk], ~1–2 cm; bez tabulek 7 parametrů ~0,4 m).
 * h elipsoidu z Bpv přes kvazigeoid CR-2005, Bpv z h elipsoidu jen když chybí.
 */
object PointCoords {
    fun complete(p: Point): Point {
        var q = p
        if (q.lat == null && q.y != null && q.x != null) {
            val (g, _) = Sjtsk.toEtrs(q.y!!, q.x!!, q.bpv)
            q = q.copy(lat = g.lat, lon = g.lon, hEll = q.hEll ?: if (q.bpv != null) g.h else null, etrsDerived = true)
        }
        if (q.y == null && q.lat != null && q.lon != null) {
            val j = Sjtsk.fromEtrs(q.lat!!, q.lon!!, q.hEll ?: 400.0)
            q = q.copy(y = j.y, x = j.x, jtskDerived = true, bpv = q.bpv ?: if (q.hEll != null) j.bpv else null)
        }
        return q
    }
}

/**
 * CSV bodů. Export: oddělovač ';', desetinná tečka. Import: oddělovač ';' nebo ',', hlavička s názvy sloupců
 * (nazev/cislo, druh, y, x, bpv/h/vyska, lat/b/phi, lon/l/lambda, h_ell/hel, kvalita, poznamka, url).
 * Bez hlavičky: nazev;y;x;bpv.
 */
object PointIo {
    private fun f(v: Double?, dec: Int) = if (v == null) "" else String.format(Locale.US, "%.${dec}f", v)

    fun export(points: List<Point>, out: OutputStream): Int {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("nazev;druh;y_jtsk;x_jtsk;bpv;lat_etrs89;lon_etrs89;h_ell;kvalita;zdroj;jtsk_dopocteno;etrs_dopocteno;poznamka;geodeticke_udaje\r\n")
        for (p in points) {
            w.write(
                listOf(
                    csv(p.name), csv(p.kind), f(p.y, 3), f(p.x, 3), f(p.bpv, 3), f(p.lat, 9), f(p.lon, 9), f(p.hEll, 3),
                    PointQuality.NAMES.getOrElse(p.quality) { "" }, PointSource.NAMES.getOrElse(p.source) { "" },
                    if (p.jtskDerived) "1" else "0", if (p.etrsDerived) "1" else "0", csv(p.note), csv(p.url ?: ""),
                ).joinToString(";")
            )
            w.write("\r\n")
        }
        w.flush()
        return points.size
    }

    private fun csv(s: String) = if (s.contains(';') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Rozdělení řádku s uvozovkami. */
    private fun split(line: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                c == '"' -> q = !q
                !q && c == sep -> {
                    out += sb.toString().trim()
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }

    data class ImportResult(val points: List<Point>, val skipped: Int, val error: String?)

    fun import(input: InputStream, jobId: Long): ImportResult {
        val lines = input.bufferedReader(Charsets.UTF_8).readLines().map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ImportResult(emptyList(), 0, "prázdný soubor")
        val first = lines[0].removePrefix("﻿")
        val sep = if (first.count { it == ';' } >= first.count { it == ',' }) ';' else ','
        val head = split(first, sep).map { it.lowercase(Locale.ROOT) }
        val hasHeader = head.any { it.any { ch -> ch.isLetter() } } && head.none { Angles.parseNumber(it) != null && it.length > 3 }
        // shoda celého názvu, u víceznakových i s příponou (lat_etrs89); jednoznakové (y, x, h) jen přesně
        fun col(vararg names: String): Int = if (!hasHeader) -1 else
            head.indexOfFirst { h -> names.any { h == it || (it.length > 1 && h.startsWith(it + "_")) } }
        val iName = if (hasHeader) col("nazev", "název", "cislo", "číslo", "name", "bod") else 0
        val iKind = col("druh", "typ", "kind")
        val iY = if (hasHeader) col("y", "y_jtsk") else 1
        val iX = if (hasHeader) col("x", "x_jtsk") else 2
        val iBpv = if (hasHeader) col("bpv", "vyska", "výška", "h") else 3
        val iLat = col("lat", "b", "phi", "fi", "sirka")
        val iLon = col("lon", "l", "lambda", "delka")
        val iHel = col("h_ell", "hel", "h_elips")
        val iQ = col("kvalita")
        val iNote = col("poznamka", "poznámka", "note")
        val iUrl = col("geodeticke_udaje", "url")
        val out = ArrayList<Point>()
        var skipped = 0
        for (line in if (hasHeader) lines.drop(1) else lines) {
            val c = split(line, sep)
            fun s(i: Int) = if (i >= 0 && i < c.size) c[i] else ""
            val name = s(iName)
            if (name.isEmpty()) {
                skipped++
                continue
            }
            // kvůli ',' jako oddělovači s desetinnou tečkou parseNumber zvládne obojí
            val p = Point(
                jobId = jobId, name = name, kind = s(iKind), note = s(iNote), url = s(iUrl).ifEmpty { null },
                y = Angles.parseNumber(s(iY))?.let { kotlin.math.abs(it) },
                x = Angles.parseNumber(s(iX))?.let { kotlin.math.abs(it) },
                bpv = Angles.parseNumber(s(iBpv)),
                lat = Angles.parseAngle(s(iLat)), lon = Angles.parseAngle(s(iLon)), hEll = Angles.parseNumber(s(iHel)),
                quality = PointQuality.NAMES.indexOf(s(iQ).lowercase(Locale.ROOT)).takeIf { it >= 0 } ?: PointQuality.EXACT,
            )
            if ((p.y == null || p.x == null) && (p.lat == null || p.lon == null)) {
                skipped++
                continue
            }
            out += PointCoords.complete(p)
        }
        return ImportResult(out, skipped, null)
    }
}
