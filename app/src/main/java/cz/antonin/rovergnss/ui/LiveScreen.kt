package cz.antonin.rovergnss.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.data.MeasureCalc
import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.geo.Etrs
import cz.antonin.rovergnss.geo.Geodetic
import cz.antonin.rovergnss.geo.Sjtsk
import cz.antonin.rovergnss.link.Pos
import cz.antonin.rovergnss.link.Quality
import cz.antonin.rovergnss.service.RoverController

/** Poloha v ETRS89 (HAS/SBAS přepočtené z ITRF2020). */
fun Pos.etrs(): Geodetic? {
    if (!hasFix) return null
    val h = hEll ?: 0.0
    return if (itrf && timeMs != null) Etrs.itrfToEtrs(lat!!, lon!!, h, Etrs.epochYear(timeMs))
    else Geodetic(lat!!, lon!!, h)
}

/**
 * Navigace k bodu: posun z roveru (anténa odečtena) k cíli [m], vzdálenost a směrník.
 * [dH] výška k cíli – v Bpv, když má cíl Bpv a je kvazigeoid ([hBpv] = true), jinak výška elipsoidu.
 */
data class NavInfo(
    val dN: Double, val dE: Double, val dist: Double, val az: Double,
    val dH: Double?, val hBpv: Boolean, val dY: Double?, val dX: Double?,
)

fun navInfo(g: Geodetic, antennaH: Double, t: Point): NavInfo? {
    if (t.lat == null || t.lon == null) return null
    val (kN, kE) = MeasureCalc.metersPerDeg(g.lat)
    val dN = (t.lat - g.lat) * kN
    val dE = (t.lon - g.lon) * kE
    val h = g.h - antennaH
    val j = Sjtsk.fromEtrs(g.lat, g.lon, h)
    val dBpv = if (t.bpv != null && j.bpv != null) t.bpv - j.bpv else null
    val dH = dBpv ?: if (t.hEll != null && !t.etrsDerived) t.hEll - h else null
    var dY: Double? = null
    var dX: Double? = null
    if (t.y != null && t.x != null && !t.jtskDerived) {
        dY = t.y - j.y
        dX = t.x - j.x
    }
    val az = (Math.toDegrees(kotlin.math.atan2(dE, dN)) + 360) % 360
    return NavInfo(dN, dE, kotlin.math.hypot(dN, dE), az, dH, dBpv != null, dY, dX)
}

private fun signed(v: Double?, dec: Int) = if (v == null) "–" else (if (v >= 0) "+" else "") + fmt(v, dec)

/**
 * Karta Živě: mapa a panel údajů.
 * @param full mapa přes celou obrazovku (panel skrytý, nahoře jen proužek se stavem)
 */
@Composable
fun LiveScreen(c: RoverController, full: Boolean, onFull: (Boolean) -> Unit) {
    val pos by c.pos.collectAsStateWithLifecycle()
    val sta by c.sta.collectAsStateWithLifecycle()
    val trail by c.trail.collectAsStateWithLifecycle()
    val rec by c.recording.collectAsStateWithLifecycle()
    val lq by c.linkQuality.collectAsStateWithLifecycle()
    val nav by c.navTarget.collectAsStateWithLifecycle()
    var style by rememberSaveable { mutableIntStateOf(c.prefs.mapStyle) }
    var follow by remember { mutableStateOf(c.prefs.mapFollow) }
    var rotate by remember { mutableIntStateOf(c.prefs.mapRotate) }
    val initialCamera = remember { c.prefs.mapCamera }
    var camZoom by remember { mutableStateOf(initialCamera?.get(2) ?: 6.5) }
    var camLat by remember { mutableStateOf(initialCamera?.get(0) ?: 49.8) }
    var camBearing by remember { mutableStateOf(0.0) }
    var course by remember { mutableStateOf<Double?>(null) }
    var fitTrail by remember { mutableIntStateOf(0) }
    var styleMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var showSats by remember { mutableStateOf(false) }
    var showCuzk by rememberSaveable { mutableStateOf(c.prefs.showCuzk) }
    var editing by remember { mutableStateOf<Point?>(null) }
    var cuzkAt by remember { mutableStateOf<MapTap?>(null) }
    val job by c.activeJob.collectAsStateWithLifecycle()
    val pointsFlow = remember(job?.id) { job?.let { c.db.points().byJob(it.id) } ?: kotlinx.coroutines.flow.emptyFlow() }
    val points by pointsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    val p = pos
    val g = p?.etrs()
    val antH = c.prefs.antennaH
    val ni = if (g != null && nav != null) navInfo(g, antH, nav!!) else null

    // kurz jen za pohybu, při stání drží poslední (jinak by se mapa točila podle šumu)
    LaunchedEffect(p?.timeMs) {
        if (p != null && p.hasFix && p.speed > 0.5) course = p.heading
    }
    BackHandler(enabled = full && editing == null && !showSats) { onFull(false) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MapPane(
                styleIndex = style,
                pos = g?.let { doubleArrayOf(it.lat, it.lon) },
                quality = p?.quality?.code ?: 0,
                trail = trail,
                follow = follow,
                modifier = Modifier.fillMaxSize(),
                points = points,
                showCuzk = showCuzk,
                initialCamera = initialCamera,
                onCamera = { lat, lon, zoom, bearing, idle ->
                    camZoom = zoom
                    camLat = lat
                    camBearing = bearing
                    if (idle) c.prefs.mapCamera = doubleArrayOf(lat, lon, zoom)
                },
                onClick = { tap, pt ->
                    when {
                        pt != null -> editing = pt
                        showCuzk && tap.zoom >= 14.0 -> cuzkAt = tap
                        showCuzk -> c.postMessage("Pro bodová pole přibliž mapu (aspoň 14)")
                    }
                },
                rotate = rotate,
                course = course,
                navTarget = nav?.let { t -> if (t.lat != null && t.lon != null) doubleArrayOf(t.lat, t.lon) else null },
                fitTrail = fitTrail,
            )
            Column(
                Modifier.align(Alignment.TopStart).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (full) StatusStrip(p, ni, nav, Modifier)
                SmallFloatingActionButton(onClick = { showSats = true }) {
                    Icon(Icons.Filled.SatelliteAlt, contentDescription = "Satelity")
                }
            }
            ScaleBar(camZoom, camLat, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 34.dp))
            Column(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box {
                    SmallFloatingActionButton(onClick = { styleMenu = true }) {
                        Icon(Icons.Filled.Layers, contentDescription = "Podklad")
                    }
                    DropdownMenu(expanded = styleMenu, onDismissRequest = { styleMenu = false }) {
                        MapStyles.NAMES.forEachIndexed { i, n ->
                            DropdownMenuItem(text = { Text(n) }, onClick = {
                                styleMenu = false
                                style = i
                                c.prefs.mapStyle = i
                            })
                        }
                    }
                }
                FilledIconToggleButton(checked = follow, onCheckedChange = {
                    follow = it
                    c.prefs.mapFollow = it
                }) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "Sledovat polohu")
                }
                FilledIconToggleButton(checked = showCuzk, onCheckedChange = {
                    showCuzk = it
                    c.prefs.showCuzk = it
                }) {
                    Icon(Icons.Filled.Place, contentDescription = "Bodová pole ČÚZK")
                }
                // natočení: sever → směr pohybu → volně → sever
                SmallFloatingActionButton(onClick = {
                    rotate = (rotate + 1) % 3
                    c.prefs.mapRotate = rotate
                    c.postMessage(
                        when (rotate) {
                            MapRotate.NORTH -> "Sever nahoře"
                            MapRotate.COURSE -> "Směr pohybu nahoře"
                            else -> "Volné otáčení (střelka ukazuje sever)"
                        }
                    )
                }) {
                    when (rotate) {
                        MapRotate.NORTH -> Icon(Icons.Filled.North, contentDescription = "Sever nahoře")
                        MapRotate.COURSE -> Icon(Icons.Filled.Navigation, contentDescription = "Směr pohybu nahoře")
                        else -> Icon(
                            Icons.Filled.Explore, contentDescription = "Volně",
                            modifier = Modifier.rotate(-camBearing.toFloat()),
                        )
                    }
                }
                SmallFloatingActionButton(onClick = { onFull(!full) }) {
                    Icon(
                        if (full) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = "Celá obrazovka",
                    )
                }
                Box {
                    SmallFloatingActionButton(onClick = { moreMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Další")
                    }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(text = { Text("Přiblížit na stopu") }, enabled = trail.isNotEmpty() || g != null, onClick = {
                            moreMenu = false
                            follow = false
                            c.prefs.mapFollow = false
                            fitTrail++
                        })
                        DropdownMenuItem(text = { Text("Smazat stopu") }, enabled = trail.isNotEmpty(), onClick = {
                            moreMenu = false
                            c.clearTrail()
                        })
                        if (nav != null) DropdownMenuItem(text = { Text("Ukončit navigaci") }, onClick = {
                            moreMenu = false
                            c.navigateTo(null)
                        })
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { if (rec == null) c.startRecording() else c.stopRecording() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                containerColor = if (rec == null) MaterialTheme.colorScheme.primaryContainer else Color(0xFFC62828),
                contentColor = if (rec == null) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                icon = {
                    Icon(if (rec == null) Icons.Filled.FiberManualRecord else Icons.Filled.Stop, contentDescription = null)
                },
                text = { Text(rec?.let { "Stop (${it.points})" } ?: "Záznam") },
            )
        }

        // panel s údaji
        if (!full) Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 340.dp)
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            LinkMeter(lq)
            nav?.let { t -> NavPanel(t, ni, onEnd = { c.navigateTo(null) }) }
            if (p == null) {
                Text("Žádná data z roveru.")
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityBadge(
                    p.quality,
                    if (p.quality == Quality.HAS) (if (p.carrSoln == 2) "konv." else "float") else "",
                )
                Text("SV ${p.numSv}  PDOP ${fmt(p.pdop, 1)}")
                if (p.moving) Text("pohyb", color = Color(0xFFEF6C00))
            }
            KeyValue("hAcc / vAcc", "${fmt(p.hAcc, 3)} / ${fmt(p.vAcc, 3)} m")
            KeyValue("σ N / E / U", "${fmt(p.sN, 3)} / ${fmt(p.sE, 3)} / ${fmt(p.sU, 3)} m")
            if (p.corrAge != null) KeyValue("Stáří korekcí", "≤ ${fmt(p.corrAge, 0)} s")
            if (g != null) {
                KeyValue("φ ETRS89", dms(g.lat, true))
                KeyValue("λ ETRS89", dms(g.lon, false))
                KeyValue("h elipsoid", fmt(g.h, 3, " m"))
                val j = Sjtsk.fromEtrs(g.lat, g.lon, g.h - antH)
                KeyValue("S-JTSK Y / X", "${fmt(j.y, 3)} / ${fmt(j.x, 3)}")
                if (j.bpv != null) KeyValue("H Bpv (CR-2005)", fmt(j.bpv, 3, " m"))
                else KeyValue("H (u-blox geoid)", fmt(p.hMsl, 2, " m"))
                if (!j.precise) Text("S-JTSK 7 parametry (~0,4 m) – tabulky ČÚZK nenačtené nebo mimo ČR.", style = MaterialTheme.typography.bodySmall)
                if (p.itrf) Text("Bez RTCM: přepočet ITRF2020 → ETRS89.", style = MaterialTheme.typography.bodySmall)
                if (antH != 0.0) Text("S-JTSK a Bpv na značce (anténa ${fmt(antH, 3)} m odečtena).", style = MaterialTheme.typography.bodySmall)
            }
            KeyValue("Rychlost / kurz", "${fmt(p.speed * 3.6, 1)} km/h / ${fmt(p.heading, 0)}°")
            sta?.let { s -> KeyValue("Baterie roveru", batteryText(s)) }
        }
    }

    editing?.let { pt ->
        PointEditor(
            initial = pt,
            onSave = {
                c.savePoint(it)
                editing = null
            },
            onDelete = {
                c.deletePoint(pt)
                editing = null
            },
            onDismiss = { editing = null },
            extra = { PointExtras(c, pt, onClose = { editing = null }) },
        )
    }
    if (showSats) {
        BackHandler { showSats = false }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Satelity", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showSats = false }) { Text("Zavřít") }
                }
                SatScreen(c)
            }
        }
    }
    cuzkAt?.let { tap ->
        CuzkDialog(tap, onTake = { c.takeCuzkPoint(it) }, onDismiss = { cuzkAt = null })
    }
}

/** Proužek se stavem v režimu celé obrazovky. */
@Composable
private fun StatusStrip(p: Pos?, ni: NavInfo?, nav: Point?, modifier: Modifier) {
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (p == null) {
            Text("Žádná data z roveru", color = Color.Black)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QualityBadge(p.quality)
                Text("±${fmt(p.hAcc, 3)} m  SV ${p.numSv}", color = Color.Black, fontFamily = FontFamily.Monospace)
            }
        }
        if (nav != null) Text(
            if (ni == null) "Cíl ${nav.name}: bez polohy"
            else "${nav.name}: ${fmtDist(ni.dist)}  ${fmt(ni.az, 0)}°  N ${signed(ni.dN, 2)}  E ${signed(ni.dE, 2)}",
            color = Color.Black, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Navigace k bodu v panelu údajů: kam a o kolik se posunout. */
@Composable
private fun NavPanel(t: Point, ni: NavInfo?, onEnd: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFEBEE), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Navigace k bodu ${t.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onEnd) { Text("Konec") }
        }
        if (ni == null) {
            Text(if (t.lat == null) "Bod nemá souřadnice." else "Čekám na polohu roveru.")
            return@Column
        }
        KeyValue("Vzdálenost / směr", "${fmtDist(ni.dist)} / ${fmt(ni.az, 0)}°")
        KeyValue("K cíli sever / východ", "${signed(ni.dN, 3)} / ${signed(ni.dE, 3)} m")
        if (ni.dY != null && ni.dX != null) KeyValue("K cíli ΔY / ΔX", "${signed(ni.dY, 3)} / ${signed(ni.dX, 3)} m")
        if (ni.dH != null) KeyValue(if (ni.hBpv) "K cíli výška Bpv" else "K cíli výška (elipsoid)", "${signed(ni.dH, 3)} m")
    }
}
