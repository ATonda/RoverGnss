package cz.antonin.rovergnss.ui

import androidx.compose.runtime.Composable
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

private class MapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var centered = false
}

/**
 * Mapa MapLibre s polohou roveru (barva podle kvality), stopou, body zakázky a vrstvou bodových polí ČÚZK.
 * @param pos [lat, lon] v ETRS89, null = bez polohy
 * @param onClick klepnutí: místo, přiblížení, bod zakázky pod prstem (nebo null)
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
    onClick: (lat: Double, lon: Double, zoom: Double, point: JobPoint?) -> Unit = { _, _, _, _ -> },
) {
    val clickCb by rememberUpdatedState(onClick)
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
                m.cameraPosition = CameraPosition.Builder().target(LatLng(49.8, 15.5)).zoom(6.5).build()
                m.addOnMapClickListener { ll ->
                    val sp = m.projection.toScreenLocation(ll)
                    // bod zakázky v okolí prstu (±20 px)
                    val r = android.graphics.RectF(sp.x - 20, sp.y - 20, sp.x + 20, sp.y + 20)
                    val f = m.queryRenderedFeatures(r, LYR_POINTS).firstOrNull()
                    val id = f?.getNumberProperty("id")?.toLong()
                    clickCb(ll.latitude, ll.longitude, m.cameraPosition.zoom, pointsNow.firstOrNull { it.id == id })
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
            st.addLayer(
                LineLayer(SRC_TRAIL, SRC_TRAIL).withProperties(
                    PropertyFactory.lineColor(android.graphics.Color.rgb(255, 193, 7)),
                    PropertyFactory.lineWidth(3f),
                )
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
        if (!holder.centered) {
            holder.centered = true
            m.cameraPosition = CameraPosition.Builder().target(LatLng(pos[0], pos[1])).zoom(START_ZOOM).build()
        } else if (follow) {
            m.easeCamera(CameraUpdateFactory.newLatLng(LatLng(pos[0], pos[1])), 300)
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

private const val SRC_POS = "rover-pos"
private const val SRC_CUZK = "cuzk-bodova-pole"
private const val SRC_POINTS = "job-points"
private const val LYR_POINTS = "job-points"
private const val LYR_LABELS = "job-points-labels"
private const val START_ZOOM = 21.0  // po prvním fixu
private const val MAX_ZOOM = 22.0
private const val SRC_TRAIL = "rover-trail"
