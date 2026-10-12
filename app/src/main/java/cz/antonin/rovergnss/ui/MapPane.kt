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

/** Mapové podklady. */
object MapStyles {
    val NAMES = listOf("Mapa OSM", "Ortofoto ČÚZK", "ZTM ČÚZK")

    private const val OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    private const val ORTO = "https://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO_WM/MapServer/tile/{z}/{y}/{x}"
    private const val ZTM = "https://ags.cuzk.gov.cz/arcgis1/rest/services/ZTM_WM/MapServer/tile/{z}/{y}/{x}"

    private fun raster(url: String, maxZoom: Int, attribution: String) = """
        {"version":8,
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
 * Mapa MapLibre s polohou roveru (barva podle kvality) a stopou.
 * @param pos [lat, lon] v ETRS89, null = bez polohy
 */
@Composable
fun MapPane(
    styleIndex: Int,
    pos: DoubleArray?,
    quality: Int,
    trail: List<DoubleArray>,
    follow: Boolean,
    modifier: Modifier = Modifier,
) {
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
private const val START_ZOOM = 21.0  // po prvním fixu
private const val MAX_ZOOM = 22.0
private const val SRC_TRAIL = "rover-trail"
