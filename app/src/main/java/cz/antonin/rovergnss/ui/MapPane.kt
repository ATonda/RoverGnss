package cz.antonin.rovergnss.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import androidx.compose.runtime.rememberUpdatedState
import cz.antonin.rovergnss.cuzk.CuzkPoints
import cz.antonin.rovergnss.data.Point as JobPoint

/** Mapové podklady. */
object MapStyles {
    val NAMES = listOf("Mapa OSM", "Ortofoto ČÚZK", "ZTM ČÚZK")

    private const val OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    private const val ORTO = "https://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO_WM/MapServer/tile/{z}/{y}/{x}"
    private const val ZTM = "https://ags.cuzk.gov.cz/arcgis1/rest/services/ZTM_WM/MapServer/tile/{z}/{y}/{x}"

    // písma pro popisky bodů (rastrové podklady vlastní písma nemají)
    private const val GLYPHS = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"

    private fun raster(url: String, maxZoom: Int, attribution: String) = """
        {"version":8,
         "glyphs":"$GLYPHS",
         "sources":{"r":{"type":"raster","tiles":["$url"],"tileSize":256,"maxzoom":$maxZoom,
                         "attribution":"$attribution"}},
         "layers":[{"id":"r","type":"raster","source":"r"}]}
    """.trimIndent()

    fun builder(i: Int): Style.Builder = when (i) {
        // nad nejvyšší úrovní dlaždic se poslední úroveň zvětšuje (zoom až 22)
        1 -> Style.Builder().fromJson(raster(ORTO, 20, "© ČÚZK"))
        2 -> Style.Builder().fromJson(raster(ZTM, 18, "© ČÚZK"))
        else -> Style.Builder().fromJson(raster(OSM, 19, "© OpenStreetMap"))
    }
}

/** Klepnutí do mapy: místo, přiblížení a velikost mapy v dp (pro dotaz ČÚZK ve stejném měřítku). */
data class MapTap(val lat: Double, val lon: Double, val zoom: Double, val widthDp: Int, val heightDp: Int)

/** Natočení mapy. */
object MapRotate {
    const val NORTH = 0   // sever nahoře
    const val COURSE = 1  // směr pohybu nahoře
    const val FREE = 2    // volně (otáčení prsty)
}

private class MapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var centered = false
}

/**
 * Mapa MapLibre s polohou roveru (barva podle kvality), stopou, body lokality a vrstvou bodových polí ČÚZK.
 * @param pos [lat, lon] v ETRS89, null = bez polohy
 * @param onClick klepnutí a bod lokality pod prstem (nebo null)
 * @param rotate natočení ([MapRotate]), [course] = kurz pro „směr pohybu nahoře“ (null = držet poslední)
 * @param navTarget [lat, lon] cíle navigace – čára od roveru a značka cíle
 * @param fitTrail při změně (> 0) přiblíží mapu na celou stopu
 */
@Composable
fun MapPane(
    styleIndex: Int,
    pos: DoubleArray?,
    quality: Int,
    trail: List<DoubleArray>,
    follow: Boolean,
    modifier: Modifier = Modifier,
    points: List<JobPoint> = emptyList(),
    showCuzk: Boolean = false,
    onClick: (tap: MapTap, point: JobPoint?) -> Unit = { _, _ -> },
    initialCamera: DoubleArray? = null,   // [lat, lon, zoom] poslední pohled; null = celá ČR a vycentrovat na první fix
    onCamera: (lat: Double, lon: Double, zoom: Double, bearing: Double, idle: Boolean) -> Unit = { _, _, _, _, _ -> },
    rotate: Int = MapRotate.NORTH,
    course: Double? = null,
    navTarget: DoubleArray? = null,
    fitTrail: Int = 0,
) {
    val clickCb by rememberUpdatedState(onClick)
    val cameraCb by rememberUpdatedState(onCamera)
    val pointsNow by rememberUpdatedState(points)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { MapHolder() }
    var mapReady by remember { mutableStateOf(false) }
    var styleVersion by remember { mutableStateOf(0) }

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                holder.map = m
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                m.setMaxZoomPreference(MAX_ZOOM)
                if (initialCamera != null) {
                    // návrat na mapu: poslední pohled, bez automatického vycentrování
                    m.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(initialCamera[0], initialCamera[1])).zoom(initialCamera[2]).build()
                    holder.centered = true
                } else {
                    m.cameraPosition = CameraPosition.Builder().target(LatLng(49.8, 15.5)).zoom(6.5).build()
                }
                val camUpdate = { idle: Boolean ->
                    val cp = m.cameraPosition
                    cp.target?.let { cameraCb(it.latitude, it.longitude, cp.zoom, cp.bearing, idle) }
                }
                m.addOnCameraMoveListener { camUpdate(false) }
                m.addOnCameraIdleListener { camUpdate(true) }
                m.addOnMapClickListener { ll ->
                    val sp = m.projection.toScreenLocation(ll)
                    // bod zakázky v okolí prstu (±20 px)
                    val r = android.graphics.RectF(sp.x - 20, sp.y - 20, sp.x + 20, sp.y + 20)
                    val f = m.queryRenderedFeatures(r, LYR_POINTS).firstOrNull()
                    val id = f?.getNumberProperty("id")?.toLong()
                    val dens = resources.displayMetrics.density
                    val tap = MapTap(ll.latitude, ll.longitude, m.cameraPosition.zoom, (width / dens).toInt(), (height / dens).toInt())
                    clickCb(tap, pointsNow.firstOrNull { it.id == id })
                    true
                }
                mapReady = true
            }
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)  // přehraje i ON_START/ON_RESUME, pokud už aktivita běží
        onDispose {
            lifecycle.removeObserver(obs)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    // načtení podkladu a vlastních vrstev
    LaunchedEffect(mapReady, styleIndex) {
        val m = holder.map ?: return@LaunchedEffect
        holder.style = null
        m.setStyle(MapStyles.builder(styleIndex)) { st ->
            // bodová pole ČÚZK (rastr ze služby, od přiblížení 13)
            st.addSource(RasterSource(SRC_CUZK, TileSet("2.2.0", CuzkPoints.TILES), 256))
            st.addLayer(
                RasterLayer(SRC_CUZK, SRC_CUZK).withProperties(
                    PropertyFactory.visibility(if (showCuzk) Property.VISIBLE else Property.NONE),
                ).also { it.minZoom = 13f }
            )
            st.addSource(GeoJsonSource(SRC_POINTS))
            st.addLayer(
                CircleLayer(LYR_POINTS, SRC_POINTS).withProperties(
                    PropertyFactory.circleRadius(6f),
                    PropertyFactory.circleColor(android.graphics.Color.rgb(106, 27, 154)),
                    PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                    PropertyFactory.circleStrokeWidth(2f),
                )
            )
            st.addLayer(
                SymbolLayer(LYR_LABELS, SRC_POINTS).withProperties(
                    PropertyFactory.textField(Expression.get("name")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textOffset(arrayOf(0f, 1.2f)),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
                    PropertyFactory.textHaloWidth(1.5f),
                    PropertyFactory.textAllowOverlap(false),
                ).also { it.minZoom = 15f }
            )
            st.addSource(GeoJsonSource(SRC_TRAIL))
            st.addSource(GeoJsonSource(SRC_POS))
            st.addSource(GeoJsonSource(SRC_NAV))
            st.addLayer(
                LineLayer(SRC_TRAIL, SRC_TRAIL).withProperties(
                    PropertyFactory.lineColor(android.graphics.Color.rgb(255, 193, 7)),
                    PropertyFactory.lineWidth(3f),
                )
            )
            // navigace: čárkovaná čára rover → cíl a kroužek na cíli
            st.addLayer(
                LineLayer(LYR_NAV_LINE, SRC_NAV).withProperties(
                    PropertyFactory.lineColor(android.graphics.Color.rgb(198, 40, 40)),
                    PropertyFactory.lineWidth(2.5f),
                    PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
                ).withFilter(Expression.eq(Expression.geometryType(), Expression.literal("LineString")))
            )
            st.addLayer(
                CircleLayer(LYR_NAV_TARGET, SRC_NAV).withProperties(
                    PropertyFactory.circleRadius(11f),
                    PropertyFactory.circleOpacity(0f),
                    PropertyFactory.circleStrokeColor(android.graphics.Color.rgb(198, 40, 40)),
                    PropertyFactory.circleStrokeWidth(3f),
                ).withFilter(Expression.eq(Expression.geometryType(), Expression.literal("Point")))
            )
            st.addLayer(
                CircleLayer(SRC_POS, SRC_POS).withProperties(
                    PropertyFactory.circleRadius(8f),
                    PropertyFactory.circleColor(qualityColorArgb(quality)),
                    PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                    PropertyFactory.circleStrokeWidth(2f),
                )
            )
            holder.style = st
            styleVersion++
        }
    }

    LaunchedEffect(pos?.get(0), pos?.get(1), quality, styleVersion, follow) {
        val st = holder.style ?: return@LaunchedEffect
        val m = holder.map ?: return@LaunchedEffect
        val src = st.getSourceAs<GeoJsonSource>(SRC_POS) ?: return@LaunchedEffect
        if (pos == null) {
            src.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
            return@LaunchedEffect
        }
        src.setGeoJson(Point.fromLngLat(pos[1], pos[0]))
        (st.getLayer(SRC_POS) as? CircleLayer)?.setProperties(PropertyFactory.circleColor(qualityColorArgb(quality)))
        val cp = m.cameraPosition
        val bearing = when (rotate) {
            MapRotate.COURSE -> course ?: cp.bearing
            MapRotate.NORTH -> 0.0
            else -> cp.bearing
        }
        if (!holder.centered) {
            holder.centered = true
            m.cameraPosition = CameraPosition.Builder().target(LatLng(pos[0], pos[1])).zoom(START_ZOOM).bearing(bearing).build()
        } else if (follow) {
            m.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(pos[0], pos[1])).zoom(cp.zoom).bearing(bearing).build()
                ), 300,
            )
        } else if (rotate == MapRotate.COURSE && course != null && kotlin.math.abs(cp.bearing - course) > 1.0) {
            m.easeCamera(CameraUpdateFactory.bearingTo(course), 300)
        }
    }

    // natočení: gesta jen ve volném režimu, sever nahoře srovná mapu hned
    LaunchedEffect(rotate, mapReady) {
        val m = holder.map ?: return@LaunchedEffect
        m.uiSettings.isRotateGesturesEnabled = rotate == MapRotate.FREE
        if (rotate == MapRotate.NORTH && m.cameraPosition.bearing != 0.0) m.easeCamera(CameraUpdateFactory.bearingTo(0.0), 300)
        if (rotate == MapRotate.COURSE && course != null) m.easeCamera(CameraUpdateFactory.bearingTo(course), 300)
    }

    LaunchedEffect(navTarget?.get(0), navTarget?.get(1), pos?.get(0), pos?.get(1), styleVersion) {
        val st = holder.style ?: return@LaunchedEffect
        val src = st.getSourceAs<GeoJsonSource>(SRC_NAV) ?: return@LaunchedEffect
        if (navTarget == null) {
            src.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
            return@LaunchedEffect
        }
        val t = Point.fromLngLat(navTarget[1], navTarget[0])
        val feats = arrayListOf(Feature.fromGeometry(t))
        if (pos != null) feats += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(pos[1], pos[0]), t)))
        src.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    // přiblížit na stopu (s polohou roveru a cílem navigace)
    LaunchedEffect(fitTrail) {
        if (fitTrail == 0) return@LaunchedEffect
        val m = holder.map ?: return@LaunchedEffect
        val pts = trail.map { LatLng(it[0], it[1]) } + listOfNotNull(pos?.let { LatLng(it[0], it[1]) })
        when {
            pts.isEmpty() -> return@LaunchedEffect
            pts.size == 1 -> m.easeCamera(CameraUpdateFactory.newLatLngZoom(pts[0], START_ZOOM), 500)
            else -> {
                val b = LatLngBounds.Builder().includes(pts).build()
                val pad = (48 * mapView.resources.displayMetrics.density).toInt()
                m.easeCamera(CameraUpdateFactory.newLatLngBounds(b, pad), 500)
            }
        }
    }

    LaunchedEffect(showCuzk, styleVersion) {
        val st = holder.style ?: return@LaunchedEffect
        st.getLayer(SRC_CUZK)?.setProperties(PropertyFactory.visibility(if (showCuzk) Property.VISIBLE else Property.NONE))
    }

    LaunchedEffect(points, styleVersion) {
        val st = holder.style ?: return@LaunchedEffect
        val src = st.getSourceAs<GeoJsonSource>(SRC_POINTS) ?: return@LaunchedEffect
        val feats = points.filter { it.lat != null && it.lon != null }.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon!!, p.lat!!)).also {
                it.addNumberProperty("id", p.id)
                it.addStringProperty("name", p.name)
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    LaunchedEffect(trail.size, styleVersion) {
        val st = holder.style ?: return@LaunchedEffect
        val src = st.getSourceAs<GeoJsonSource>(SRC_TRAIL) ?: return@LaunchedEffect
        if (trail.size < 2) {
            src.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
        } else {
            src.setGeoJson(LineString.fromLngLats(trail.map { Point.fromLngLat(it[1], it[0]) }))
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/**
 * Měřítko: délka 1–2–5×10ⁿ m, aby úsečka měla nejvýš ~100 dp.
 * Rozlišení MapLibre: svět má 512 dp × 2^zoom, m/dp = obvod·cos φ / (512·2^zoom).
 */
@Composable
fun ScaleBar(zoom: Double, lat: Double, modifier: Modifier = Modifier) {
    val mPerDp = 40_075_016.686 * kotlin.math.cos(Math.toRadians(lat)) / (512.0 * Math.pow(2.0, zoom))
    if (mPerDp <= 0 || mPerDp.isNaN()) return
    val maxM = mPerDp * 100
    var nice = 1.0
    val p = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(maxM)))
    for (k in listOf(5.0, 2.0, 1.0)) if (k * p <= maxM) { nice = k * p; break }
    val widthDp = (nice / mPerDp).toFloat()
    val label = when {
        nice >= 1000 -> "${(nice / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} km"
        nice >= 1 -> "${nice.toInt()} m"
        else -> "${(nice * 100).toInt()} cm"
    }
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Black,
            modifier = Modifier.background(Color.White.copy(alpha = 0.7f)).padding(horizontal = 3.dp),
        )
        Box(
            Modifier
                .width(widthDp.dp)
                .height(6.dp)
                .background(Color.White.copy(alpha = 0.7f))
                .border(1.5.dp, Color.Black)
        )
    }
}

private const val SRC_POS = "rover-pos"
private const val SRC_CUZK = "cuzk-bodova-pole"
private const val SRC_POINTS = "job-points"
private const val LYR_POINTS = "job-points"
private const val LYR_LABELS = "job-points-labels"
private const val START_ZOOM = 21.0  // po prvním fixu
private const val MAX_ZOOM = 22.0
private const val SRC_TRAIL = "rover-trail"
private const val SRC_NAV = "nav"
private const val LYR_NAV_LINE = "nav-line"
private const val LYR_NAV_TARGET = "nav-target"
