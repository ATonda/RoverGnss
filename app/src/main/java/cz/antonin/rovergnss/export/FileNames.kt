package cz.antonin.rovergnss.export

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Názvy exportovaných souborů (0.10.0): krátké, nejdůležitější na začátku, bez „RG_“, datum na konci.
 * Bod: `Sitiny_B1_1205_20261029.log`; lokalita: `Sitiny_body_20261029.csv`; test: `TEST_Les_1612_20261020.zip`.
 * Lokalita a název bez diakritiky, mezery a ostatní znaky → `_`, čas `HHMM`, datum `YYYYMMDD`.
 */
object FileNames {
    /** Bez diakritiky, jen písmena, číslice, „-“ a „_“ (mezery → „_“). */
    fun safe(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^A-Za-z0-9-]+"), "_").trim('_').ifEmpty { "bez_nazvu" }

    private fun hm(ms: Long) = SimpleDateFormat("HHmm", Locale.US).format(Date(ms))
    private fun ymd(ms: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))

    /** Soubor bodu: `Lokalita_Bod_HHMM_YYYYMMDD.ext`. */
    fun point(job: String, point: String, startMs: Long, ext: String): String =
        "${safe(job)}_${safe(point)}_${hm(startMs)}_${ymd(startMs)}.$ext"

    /** Soubor lokality: `Lokalita_co_YYYYMMDD.ext` (např. `Sitiny_body_20261029.csv`). */
    fun job(job: String, what: String, ms: Long, ext: String): String =
        "${safe(job)}_${safe(what)}_${ymd(ms)}.$ext"

    /** Soubor celé lokality: `Lokalita_YYYYMMDD.ext` (např. ZIP exportu bodů `Sitiny_20261029.zip`). */
    fun jobDate(job: String, ms: Long, ext: String): String = "${safe(job)}_${ymd(ms)}.$ext"

    /** Test: `TEST_scenar_HHMM_YYYYMMDD.ext`. */
    fun test(scenario: String, startMs: Long, ext: String): String =
        "TEST_${safe(scenario)}_${hm(startMs)}_${ymd(startMs)}.$ext"

    /** Obecný soubor bez lokality: `co_HHMM_YYYYMMDD.ext`. */
    fun plain(what: String, ms: Long, ext: String): String = "${safe(what)}_${hm(ms)}_${ymd(ms)}.$ext"

    /** Jedinečný název v jedné sadě (ZIP): při shodě přípona `_2`, `_3`, … před příponou souboru. */
    fun unique(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var k = 2
        while (true) {
            val n = "${base}_$k$ext"
            if (used.add(n)) return n
            k++
        }
    }
}
