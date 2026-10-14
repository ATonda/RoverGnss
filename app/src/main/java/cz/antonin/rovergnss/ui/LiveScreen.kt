package cz.antonin.rovergnss.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
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
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.data.MeasureCalc
import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.geo.Etrs
import cz.antonin.rovergnss.geo.Geodetic
import cz.antonin.rovergnss.geo.Sjtsk
import cz.antonin.rovergnss.link.Pos
import cz.antonin.rovergnss.link.Quality
import cz.antonin.rovergnss.service.LiveAverage
import cz.antonin.rovergnss.service.RoverController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
fun LiveScreen(c: RoverController, full: Boolean, onTarget: () -> Unit, onFull: (Boolean) -> Unit) {
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
    var surveyName by remember { mutableStateOf<String?>(null) }   // dialog zaměření bodu
    var recDialog by remember { mutableStateOf(false) }             // název trasy před záznamem
    var userMenu by remember { mutableStateOf(false) }
    val user by c.user.collectAsStateWithLifecycle()
    val users by c.users.collectAsStateWithLifecycle()
    val stake by c.stake.collectAsStateWithLifecycle()
    val meas by c.measure.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val onStaked: (Point) -> Unit = { t -> c.startStakeCheck(t) }
    var showCuzk by rememberSaveable { mutableStateOf(c.prefs.showCuzk) }
    var editing by remember { mutableStateOf<Point?>(null) }
    var cuzkAt by remember { mutableStateOf<MapTap?>(null) }
    val job by c.activeJob.collectAsStateWithLifecycle()
    val pointsFlow = remember(job?.id) { job?.let { c.db.points().byJob(it.id) } ?: kotlinx.coroutines.flow.emptyFlow() }
    val points by pointsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val avg by c.liveAvg.collectAsStateWithLifecycle()
    var avgMode by remember { mutableStateOf(c.prefs.liveAverage) }

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
                // uživatelský profil (výška antény, …) – přepnutí jedním klepnutím
                Box {
                    androidx.compose.material3.AssistChip(
                        onClick = { userMenu = true },
                        label = { Text(user.name) },
                        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            containerColor = Color.White.copy(alpha = 0.9f),
                        ),
                    )
                    DropdownMenu(expanded = userMenu, onDismissRequest = { userMenu = false }) {
                        users.forEachIndexed { i, u ->
                            DropdownMenuItem(
                                text = { Text(u.name + " (anténa ${fmt(u.antennaH, 3)} m)" + if (u == user) "  ✓" else "") },
                                onClick = {
                                    userMenu = false
                                    c.selectUser(i)
                                },
                            )
                        }
                    }
                }
                if (full) StatusStrip(
                    p, ni, nav, Modifier,
                    onStaked = nav?.let { t -> { onStaked(t) } }.takeIf { meas == null },
                    onSkip = if (stake != null && meas == null) ({ c.skipStake(); Unit }) else null,
                )
                SmallFloatingActionButton(onClick = { showSats = true }) {
                    Icon(Icons.Filled.SatelliteAlt, contentDescription = "Satelity")
                }
                // zaměření bodu „tady“
                SmallFloatingActionButton(onClick = {
                    if (meas == null) scope.launch { surveyName = c.nextSurveyName() }
                }) {
                    Icon(Icons.Filled.AddLocationAlt, contentDescription = "Zaměřit bod")
                }
                RecButton(rec, onClick = { if (rec == null) recDialog = true else c.stopRecording() })
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
            nav?.let { t ->
                NavPanel(
                    t, ni,
                    onEnd = { if (stake != null) c.stopStake() else c.navigateTo(null) },
                    onStaked = if (meas == null) ({ onStaked(t) }) else null,
                    onSkip = if (stake != null && meas == null) ({ c.skipStake(); Unit }) else null,
                    stakeInfo = stake?.let { s -> "vytyčování: ${s.ids.size} bodů" + if (s.skipped.isNotEmpty()) ", přeskočeno ${s.skipped.size}" else "" },
                )
            }
            if (p == null) {
                Text("Žádná data z roveru.")
                return@Column
            }
            ModeHeader(
                avgMode, { avgMode = it; c.prefs.liveAverage = it },
                avg, p, onReset = { c.resetLiveAverage() }, onTarget = onTarget,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityBadge(
                    p.quality,
                    if (p.quality == Quality.HAS) (if (p.carrSoln == 2) "konv." else "float") else "",
                )
                Text("SV ${p.numSv}  PDOP ${fmt(p.pdop, 1)}")
                if (p.moving) Text("pohyb", color = Color(0xFFEF6C00))
            }
            val ar = avg?.result
            // zobrazená poloha: okamžitá epocha, nebo průměr (poloha antény)
            val gShow = if (avgMode) ar?.let { r -> if (r.lat != null && r.lon != null) Geodetic(r.lat, r.lon, r.h ?: 0.0) else null } else g
            if (avgMode) {
                if (ar?.sN != null && ar.sE != null) {
                    KeyValue("σ N / E / U (rozptyl)", "${fmt(ar.sN, 3)} / ${fmt(ar.sE, 3)} / ${fmt(ar.sU, 3)} m")
                    KeyValue("σ vodorovně", fmt(kotlin.math.hypot(ar.sN, ar.sE), 3, " m"))
                }
                KeyValue("hAcc / vAcc (okamžitě)", "${fmt(p.hAcc, 3)} / ${fmt(p.vAcc, 3)} m")
            } else {
                KeyValue("hAcc / vAcc", "${fmt(p.hAcc, 3)} / ${fmt(p.vAcc, 3)} m")
                KeyValue("σ N / E / U", "${fmt(p.sN, 3)} / ${fmt(p.sE, 3)} / ${fmt(p.sU, 3)} m")
            }
            if (p.corrAge != null) KeyValue("Stáří korekcí", "≤ ${fmt(p.corrAge, 0)} s")
            if (gShow == null && avgMode && g != null) Text("Průměr: čekám na epochu bez pohybu.")
            if (gShow != null) {
                val g = gShow
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
    if (recDialog) TrackNameDialog(avg?.takeIf { it.epochs >= MeasureCalc.MIN_EPOCHS }, onStart = { name, withAvg ->
        recDialog = false
        c.startRecording(name, withAvg)
    }, onDismiss = { recDialog = false })
    surveyName?.let { initial ->
        SurveyDialog(initial, user, avg?.takeIf { it.result.used >= MeasureCalc.MIN_EPOCHS }, onStart = { name, take ->
            surveyName = null
            c.startSurvey(name, take)
        }, onDismiss = { surveyName = null })
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

/** Proužek se stavem v režimu celé obrazovky (při navigaci i Vytyčeno / Přeskočit). */
@Composable
private fun StatusStrip(
    p: Pos?,
    ni: NavInfo?,
    nav: Point?,
    modifier: Modifier,
    onStaked: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
) {
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
        if (nav != null && (onStaked != null || onSkip != null)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onStaked != null) TextButton(onClick = onStaked) { Text("Vytyčeno") }
            if (onSkip != null) TextButton(onClick = onSkip) { Text("Přeskočit") }
        }
    }
}

/**
 * Navigace k bodu v panelu údajů: kam a o kolik se posunout.
 * [onStaked] „Vytyčeno“ – krátké průměrování a uložení odchylky; [onSkip] přeskočit bod (při vytyčování).
 */
@Composable
private fun NavPanel(
    t: Point,
    ni: NavInfo?,
    onEnd: () -> Unit,
    onStaked: (() -> Unit)?,
    onSkip: (() -> Unit)?,
    stakeInfo: String?,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFEBEE), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (stakeInfo != null) "Vytyčit bod " else "Navigace k bodu ") + t.name,
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEnd) { Text("Konec") }
        }
        stakeInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onStaked != null) androidx.compose.material3.Button(onClick = onStaked) { Text("Vytyčeno") }
            if (onSkip != null) androidx.compose.material3.OutlinedButton(onClick = onSkip) { Text("Přeskočit") }
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

private fun duration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
}

/**
 * Hlavička panelu: přepínač Okamžitě | Průměr, co se zobrazuje, proč byl průměr vynulován
 * a čas poslední epochy se stářím (vlastní tikání – nepřekresluje mapu).
 */
@Composable
private fun ModeHeader(
    avgMode: Boolean,
    onMode: (Boolean) -> Unit,
    avg: LiveAverage?,
    p: Pos,
    onReset: () -> Unit,
    onTarget: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    // všechna tlačítka v rámečku na jednom řádku (při úzkém displeji posun prstem)
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(selected = !avgMode, onClick = { onMode(false) }, label = { Text("Okamžitě", maxLines = 1) })
        FilterChip(selected = avgMode, onClick = { onMode(true) }, label = { Text("Průměr", maxLines = 1) })
        if (avgMode) {
            androidx.compose.material3.AssistChip(onClick = onTarget, label = { Text("Terč", maxLines = 1) })
            androidx.compose.material3.AssistChip(onClick = onReset, label = { Text("Vynulovat", maxLines = 1) })
        }
    }
    Text(
        if (!avgMode) "Okamžité hodnoty (poslední epocha)"
        else if (avg == null) "Průměr: čekám na epochy bez pohybu"
        else "Průměr ${clock(avg.startMs, false)} → ${clock(avg.lastMs, false)} (${duration(avg.lastMs - avg.startMs)})",
        style = MaterialTheme.typography.titleSmall,
    )
    if (avgMode && avg != null) Text(
        "${count(avg.epochs)} epoch · použito ${count(avg.result.used)} ${avg.qualityLabel}",
        style = MaterialTheme.typography.bodySmall,
    )
    val reason = avg?.resetReason
    if (avgMode && avg != null && reason != null && avg.resets > 0) Text(
        "vynulováno ${avg.resets}× (poslední ${clock(avg.resetMs, false)} – $reason)",
        color = if (now - avg.resetMs < 10_000) Color(0xFFEF6C00) else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    val age = (now - p.rxMs).coerceAtLeast(0)
    val t = p.timeMs?.let { clock(it, true) } ?: "čas neplatný"
    if (age > 3000) KeyValue("Epocha", "data přestala $t (před ${ago(age)})", valueColor = Color(0xFFC62828))
    else KeyValue("Epocha", "$t · stáří ${fmt(age / 1000.0, 1)} s")
}

/** Místní čas HH:mm(:ss). */
private fun clock(ms: Long, sec: Boolean): String =
    SimpleDateFormat(if (sec) "HH:mm:ss" else "HH:mm", Locale.getDefault()).format(Date(ms))

private fun count(n: Int): String = String.format(Locale("cs", "CZ"), "%,d", n)

private fun ago(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        else -> String.format(Locale.US, "%d h %02d min", s / 3600, s / 60 % 60)
    }
}

/** Zaměření bodu „tady“: název (navržený další v řadě), podmínky konce z profilu, případně převzetí průměru. */
@Composable
private fun SurveyDialog(
    initial: String,
    u: cz.antonin.rovergnss.data.UserProfile,
    avg: LiveAverage?,
    onStart: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zaměřit bod") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text("Název / číslo") }, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Konec po ustálení (σ vodorovně ≤ ${fmt(u.surveySigma * 100, 0)} cm, aspoň ${u.surveyMinEpochs} epoch), " +
                        "nejdéle ${u.surveyMaxSec} s. Kdykoli „Uložit“ – uloží se, co je, s přesností. " +
                        "Anténa ${fmt(u.antennaH, 3)} m (profil ${u.name}).",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (avg != null) {
                    val r = avg.result
                    val hs = if (r.sN != null && r.sE != null) kotlin.math.hypot(r.sN, r.sE) else null
                    Text(
                        "Převzít průměr (${duration(avg.lastMs - avg.startMs)}, ${r.used} epoch, ${avg.qualityLabel}" +
                            (hs?.let { ", σH ${fmt(it, 3)} m" } ?: "") + ")? Měření pak pokračuje do ustálení.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                if (avg != null) {
                    TextButton(onClick = { onStart(name.trim(), false) }, enabled = name.isNotBlank()) { Text("Měřit znovu") }
                    TextButton(onClick = { onStart(name.trim(), true) }, enabled = name.isNotBlank()) { Text("Převzít") }
                } else {
                    TextButton(onClick = { onStart(name.trim(), false) }, enabled = name.isNotBlank()) { Text("Měřit") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

/** Záznam trasy: malé červené REC / STOP s uplynulým časem, bez velkého rámu. */
@Composable
private fun RecButton(rec: cz.antonin.rovergnss.service.RecordingState?, onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rec != null) {
        while (rec != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val red = Color(0xFFC62828)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = Color.White.copy(alpha = 0.85f),
        shadowElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (rec == null) Icons.Filled.FiberManualRecord else Icons.Filled.Stop,
                contentDescription = null, tint = red, modifier = Modifier.size(18.dp),
            )
            Text(
                if (rec == null) " REC" else " STOP ${duration(now - rec.startMs)}",
                color = red, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** Název trasy před startem záznamu; prázdný = automaticky „T číslo datum čas“. */
@Composable
private fun TrackNameDialog(avg: LiveAverage?, onStart: (String?, Boolean) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var withAvg by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Záznam trasy") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Název") },
                    placeholder = { Text("prázdné = T číslo, datum a čas") },
                )
                if (avg != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(withAvg, { withAvg = it })
                    Text(
                        "Zahrnout průměr od ${clock(avg.startMs, false)} (${count(avg.epochs)} epoch)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onStart(name.trim().ifEmpty { null }, withAvg && avg != null) }) { Text("Spustit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}
