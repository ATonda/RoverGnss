package cz.antonin.rovergnss.cuzk

import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.data.PointCoords
import cz.antonin.rovergnss.data.PointQuality
import cz.antonin.rovergnss.data.PointSource
import cz.antonin.rovergnss.geo.Angles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    /** Tolerance klepnutí [dp] */
    private const val TOL_DP = 15.0

    /** Vzdálenost na mapě [m] odpovídající [px] dp při přiblížení [zoom] (MapLibre: 512 dp × 2^zoom na obvod). */
    fun metersPerDp(zoom: Double, lat: Double): Double =
        40_075_016.686 * kotlin.math.cos(Math.toRadians(lat)) / (512.0 * Math.pow(2.0, zoom))

    /** Poloměr hledání kolem klepnutí [m]: ~15 dp na mapě, 3–100 m. */
    fun searchRadius(zoom: Double, lat: Double): Double = (TOL_DP * metersPerDp(zoom, lat)).coerceIn(3.0, 100.0)

    /**
     * Body v okolí klepnutí. Dva dotazy souběžně, výsledky se spojí:
     * - „identify“ ve stejném měřítku, v jakém je vrstva na mapě (výřez a velikost mapy v dp, 96 dpi jako dlaždice),
     *   takže najde přesně to, co je vidět;
     * - „query“ se vzdáleností na každou bodovou vrstvu zvlášť – nezávisí na měřítkových omezeních vrstev
     *   (body vyššího řádu se v „identify“ neukazovaly).
     * @param widthDp, heightDp velikost mapy v dp
     */
    suspend fun near(lat: Double, lon: Double, zoom: Double, widthDp: Int, heightDp: Int): List<CuzkPoint> = coroutineScope {
        val radius = searchRadius(zoom, lat)
        val idf = async(Dispatchers.IO) { runCatching { identify(lat, lon, zoom, widthDp, heightDp) } }
        val qry = async(Dispatchers.IO) { runCatching { queryLayers(lat, lon, radius) } }
        val a = idf.await()
        val b = qry.await()
        if (a.isFailure && b.isFailure) throw a.exceptionOrNull()!!
        val list = a.getOrDefault(emptyList()) + b.getOrDefault(emptyList())
        // stejný bod bývá ve více vrstvách a v obou dotazech – nechat jeden (přednost má záznam s víc údaji)
        list.groupBy { it.name + String.format(Locale.US, "%.6f,%.6f", it.lat, it.lon) }
            .map { (_, v) -> v.maxBy { it.attributes.size + (if (it.y != null) 10 else 0) + (if (it.etrsLat != null) 10 else 0) } }
            .filter { Angles.distanceBearing(lat, lon, it.lat, it.lon).first <= radius * 1.5 }
            .sortedBy { Angles.distanceBearing(lat, lon, it.lat, it.lon).first }
    }

    private fun identify(lat: Double, lon: Double, zoom: Double, widthDp: Int, heightDp: Int): List<CuzkPoint> {
        val mpd = metersPerDp(zoom, lat)
        val dLat = heightDp / 2.0 * mpd / 111_132.95
        val dLon = widthDp / 2.0 * mpd / (111_319.49 * kotlin.math.cos(Math.toRadians(lat)))
        val root = get(
            "identify", mapOf(
                "geometry" to String.format(Locale.US, "%.8f,%.8f", lon, lat),
                "geometryType" to "esriGeometryPoint",
                "sr" to "4326",
                "layers" to "all",
                "tolerance" to TOL_DP.toInt().toString(),
                "mapExtent" to String.format(Locale.US, "%.8f,%.8f,%.8f,%.8f", lon - dLon, lat - dLat, lon + dLon, lat + dLat),
                "imageDisplay" to "$widthDp,$heightDp,96",
                "returnGeometry" to "true",
                "f" to "json",
            )
        )
        val res = root.optJSONArray("results") ?: return emptyList()
        return (0 until res.length()).mapNotNull { i ->
            val r = res.getJSONObject(i)
            parse(r.optString("layerName", ""), r)
        }
    }

    /** Bodové vrstvy služby (id → název), načtené jednou ze seznamu vrstev. */
    @Volatile private var pointLayers: Map<Int, String>? = null

    private fun layers(): Map<Int, String> {
        pointLayers?.let { return it }
        val root = get("layers", mapOf("f" to "json"))
        val arr = root.optJSONArray("layers") ?: return emptyMap()
        val m = LinkedHashMap<Int, String>()
        for (i in 0 until arr.length()) {
            val l = arr.getJSONObject(i)
            if (l.optString("type") == "Feature Layer" && l.optString("geometryType") == "esriGeometryPoint") {
                m[l.optInt("id")] = l.optString("name")
            }
        }
        if (m.isNotEmpty()) pointLayers = m
        return m
    }

    private suspend fun queryLayers(lat: Double, lon: Double, radiusM: Double): List<CuzkPoint> = coroutineScope {
        val sem = Semaphore(6)
        layers().map { (id, name) ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    runCatching {
                        val root = get(
                            "$id/query", mapOf(
                                "geometry" to String.format(Locale.US, "%.8f,%.8f", lon, lat),
                                "geometryType" to "esriGeometryPoint",
                                "inSR" to "4326",
                                "spatialRel" to "esriSpatialRelIntersects",
                                "distance" to String.format(Locale.US, "%.1f", radiusM),
                                "units" to "esriSRUnit_Meter",
                                "outFields" to "*",
                                "returnGeometry" to "true",
                                "outSR" to "4326",
                                "f" to "json",
                            )
                        )
                        val feats = root.optJSONArray("features") ?: return@runCatching emptyList()
                        (0 until feats.length()).mapNotNull { i -> parse(name, feats.getJSONObject(i)) }
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten()
    }

    private fun get(op: String, params: Map<String, String>): JSONObject {
        val q = params.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val conn = URL("$BASE/$op?$q").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "RoverGnss/0.3")
        try {
            val root = JSONObject(conn.inputStream.bufferedReader().readText())
            root.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "chyba služby")) }
            return root
        } finally {
            conn.disconnect()
        }
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

    private fun parse(layerName: String, f: JSONObject): CuzkPoint? {
        val layer = layerShort(layerName)
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
            layer = layer, name = name,
            kind = listOfNotNull(
                if (layerName.contains("zruš", ignoreCase = true)) "zrušený" else null, get("DRUH"),
            ).joinToString(" "),
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
