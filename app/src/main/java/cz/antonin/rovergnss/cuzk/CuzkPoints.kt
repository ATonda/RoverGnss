package cz.antonin.rovergnss.cuzk

import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.data.PointCoords
import cz.antonin.rovergnss.data.PointQuality
import cz.antonin.rovergnss.data.PointSource
import cz.antonin.rovergnss.geo.Angles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Bod z bodových polí ČÚZK (výsledek dotazu). */
data class CuzkPoint(
    val layer: String,           // druh vrstvy (ZhB, ZPBP, PPBP, ZVBP, PVBP)
    val name: String,            // číslo bodu
    val kind: String,            // DRUH ze služby
    val lat: Double,             // poloha z geometrie (WGS84/ETRS89, ~m)
    val lon: Double,
    val y: Double?,
    val x: Double?,
    val bpv: Double?,
    val etrsLat: Double?,        // ETRS89 z geodetických údajů (bod určený GNSS)
    val etrsLon: Double?,
    val etrsH: Double?,
    val url: String?,
    val place: String,           // katastrální území / okres
    val attributes: Map<String, String>,  // všechna pole (kontrola, ladění)
) {
    fun toPoint(jobId: Long): Point = PointCoords.complete(
        Point(
            jobId = jobId, name = name, kind = listOf(layer, kind).filter { it.isNotEmpty() }.joinToString(" "),
            note = place, lat = etrsLat, lon = etrsLon, hEll = etrsH, y = y, x = x, bpv = bpv,
            quality = if (y != null || etrsLat != null) PointQuality.EXACT else PointQuality.APPROX,
            source = PointSource.CUZK, url = url,
        )
    )
}

/**
 * Dotaz na bodová pole ČÚZK (ArcGIS REST, služba BodovaPole). Pole a jejich formát nejsou zdokumentované –
 * čtení je tolerantní a všechny atributy se uloží do [CuzkPoint.attributes] pro kontrolu.
 */
object CuzkPoints {
    const val BASE = "https://ags.cuzk.gov.cz/arcgis/rest/services/BodovaPole/MapServer"

    /** Rastrová vrstva pro MapLibre (dlaždice v Web Mercator). */
    const val TILES = "$BASE/export?bbox={bbox-epsg-3857}&bboxSR=3857&imageSR=3857&size=256,256" +
        "&format=png32&transparent=true&dpi=96&f=image"

    /**
     * Body do [radiusM] od místa. Operace „identify“ prohledá všechny vrstvy služby najednou
     * (body ZPBP/ZhB jsou podle způsobu určení rozdělené do mnoha vrstev, dotaz na vybrané vrstvy je míjel).
     */
    suspend fun near(lat: Double, lon: Double, radiusM: Int = 30): List<CuzkPoint> = withContext(Dispatchers.IO) {
        // výřez 4×R kolem místa na 200 px → tolerance 50 px ≈ R
        val dLat = 2.0 * radiusM / 111_132.95
        val dLon = 2.0 * radiusM / (111_319.49 * kotlin.math.cos(Math.toRadians(lat)))
        val q = mapOf(
            "geometry" to String.format(Locale.US, "%.8f,%.8f", lon, lat),
            "geometryType" to "esriGeometryPoint",
            "sr" to "4326",
            "layers" to "all",
            "tolerance" to "50",
            "mapExtent" to String.format(Locale.US, "%.8f,%.8f,%.8f,%.8f", lon - dLon, lat - dLat, lon + dLon, lat + dLat),
            "imageDisplay" to "200,200,96",
            "returnGeometry" to "true",
            "f" to "json",
        ).entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val conn = URL("$BASE/identify?$q").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "RoverGnss/0.2")
        val list = try {
            val text = conn.inputStream.bufferedReader().readText()
            val root = JSONObject(text)
            root.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "chyba služby")) }
            val res = root.optJSONArray("results") ?: return@withContext emptyList()
            (0 until res.length()).mapNotNull { i ->
                val r = res.getJSONObject(i)
                parse(layerShort(r.optString("layerName", "")), r)
            }
        } finally {
            conn.disconnect()
        }
        // stejný bod bývá ve vrstvě bodu i ve vrstvě čísel – nechat jeden (přednost má záznam s víc údaji)
        list.groupBy { it.name + String.format(Locale.US, "%.6f,%.6f", it.lat, it.lon) }
            .map { (_, v) -> v.maxBy { it.attributes.size + (if (it.y != null) 10 else 0) + (if (it.etrsLat != null) 10 else 0) } }
            .filter { Angles.distanceBearing(lat, lon, it.lat, it.lon).first <= radiusM * 1.5 }
            .sortedBy { Angles.distanceBearing(lat, lon, it.lat, it.lon).first }
    }

    /** Zkratka z názvu vrstvy (např. „Bod ZPBP určený v ETRS89“ → ZPBP). */
    private fun layerShort(n: String): String = when {
        n.contains("ZPBP") -> "ZPBP"
        n.contains("ZhB", ignoreCase = true) -> "ZhB"
        n.contains("PPBP") -> "PPBP"
        n.contains("ZVBP") -> "ZVBP"
        n.contains("PVBP") -> "PVBP"
        n.contains("ZTBP") -> "ZTBP"
        n.contains("CZEPOS") -> "CZEPOS"
        else -> n
    }

    private fun parse(layer: String, f: JSONObject): CuzkPoint? {
        val g = f.optJSONObject("geometry") ?: return null
        val gx = g.optDouble("x", Double.NaN)
        val gy = g.optDouble("y", Double.NaN)
        if (gx.isNaN() || gy.isNaN()) return null
        val a = f.optJSONObject("attributes") ?: JSONObject()
        val attrs = LinkedHashMap<String, String>()
        a.keys().forEach { k ->
            if (!a.isNull(k)) {
                val v = a.get(k).toString().trim()
                if (v.isNotEmpty() && !v.equals("Null", ignoreCase = true)) attrs[k] = v
            }
        }
        fun get(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            attrs.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }
        }
        fun num(vararg keys: String): Double? = get(*keys)?.let { Angles.parseNumber(it) }

        // číslo bodu: list (ZTLTL) + číslo, jinak pole s „CISLO“ / „OZNAC“ / „NAZEV“
        val list = get("ZTLTL")
        val cislo = get("CISLO", "CISLO_BODU", "CISLOBODU")
        val name = when {
            list != null && cislo != null -> "$list-${cislo.padStart(3, '0')}"
            cislo != null -> cislo
            else -> attrs.entries.firstOrNull { (k, _) ->
                val u = k.uppercase(Locale.ROOT)
                u.contains("CISLO") || u.contains("OZNAC") || u == "NAZEV"
            }?.value ?: "?"
        }
        // ETRS89: B, L ve stupních (případně ve formátu DDMMSS.sss), HEL [m]
        val b = num("B")?.let { deg(it) }
        val l = num("L")?.let { deg(it) }
        val url = get("GEODETICKE_UDAJE", "GU", "URL")?.let { u ->
            Regex("https?://\\S+").find(u)?.value ?: u.takeIf { it.startsWith("http") }
        }
        val place = listOfNotNull(get("NAZEV_KU"), get("NAZEV_OKRES")).joinToString(", ")
        return CuzkPoint(
            layer = layer, name = name, kind = get("DRUH") ?: "",
            lat = gy, lon = gx,
            y = num("Y")?.let { kotlin.math.abs(it) }, x = num("X")?.let { kotlin.math.abs(it) },
            bpv = num("VYSKA", "H", "BPV"),
            etrsLat = b?.takeIf { it in 47.0..52.0 }, etrsLon = l?.takeIf { it in 11.0..20.0 },
            etrsH = num("HEL"),
            url = url, place = place, attributes = attrs,
        )
    }

    /** Stupně z desetinných stupňů nebo z tvaru DDMMSS.sss. */
    private fun deg(v: Double): Double {
        if (v in 0.0..90.0) return v
        if (v > 10000) {
            val d = (v / 10000).toInt()
            val m = ((v - d * 10000) / 100).toInt()
            val s = v - d * 10000 - m * 100
            return d + m / 60.0 + s / 3600.0
        }
        return v
    }
}
