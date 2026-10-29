package cz.antonin.rovergnss.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.data.Measurement
import cz.antonin.rovergnss.data.ObjCalc
import cz.antonin.rovergnss.data.ObjMount
import cz.antonin.rovergnss.data.ObjObs
import cz.antonin.rovergnss.data.ObjPlace
import cz.antonin.rovergnss.data.ObjPointType
import cz.antonin.rovergnss.data.ObjResult
import cz.antonin.rovergnss.data.ObjSketch
import cz.antonin.rovergnss.data.ObjSource
import cz.antonin.rovergnss.data.ObjTape
import cz.antonin.rovergnss.data.ObjVertex
import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.data.PointEvaluator
import cz.antonin.rovergnss.data.PointQuality
import cz.antonin.rovergnss.data.PointResult
import cz.antonin.rovergnss.data.PointSource
import cz.antonin.rovergnss.data.SiteObject
import cz.antonin.rovergnss.data.sketch
import cz.antonin.rovergnss.export.FileNames
import cz.antonin.rovergnss.export.Gpkg
import cz.antonin.rovergnss.export.ObjExport
import cz.antonin.rovergnss.link.LinkState
import cz.antonin.rovergnss.link.Quality
import cz.antonin.rovergnss.service.RoverController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private object ObjMode {
    const val DRAW = 0
    const val POINTS = 1
    const val TAPE = 2
    const val MEASURE = 3
    val NAMES = listOf("Kreslit", "Body", "Pásmo", "Změřit")
}

private val SRC_COLORS = listOf(Color(0xFF2E7D32), Color(0xFF1565C0), Color(0xFF8E24AA), Color(0xFF9E9E9E))
private val BAD = Color(0xFFC62828)

/** Prvek pod prstem. */
private sealed class Hit {
    data class Vertex(val index: Int) : Hit()
    data class Edge(val index: Int) : Hit()
    data class Place(val id: Int) : Hit()
    data class Tape(val id: Int) : Hit()
}

/**
 * Body → Objekt (0.11.0): náčrt půdorysu, umístění změřených bodů, kóty pásmem, výpočet a export.
 * Bez měřítka se kreslí v náčrtu; po 2 změřených bodech se ostatní režimy zobrazují ve skutečných souřadnicích.
 */
@Composable
fun ObjectScreen(c: RoverController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val job by c.activeJob.collectAsStateWithLifecycle()
    val objFlow = remember(job?.id) { job?.let { c.db.objects().byJob(it.id) } ?: emptyFlow() }
    val objects by objFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val pointsFlow = remember(job?.id) { job?.let { c.db.points().byJob(it.id) } ?: emptyFlow() }
    val points by pointsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val measFlow = remember(job?.id) { job?.let { c.db.measurements().byJob(it.id) } ?: emptyFlow() }
    val meas by measFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val link by c.linkState.collectAsStateWithLifecycle()
    val running by c.measure.collectAsStateWithLifecycle()

    var selId by rememberSaveable { mutableStateOf<Long?>(null) }
    val obj = objects.firstOrNull { it.id == selId } ?: objects.firstOrNull()
    var mode by rememberSaveable { mutableIntStateOf(ObjMode.DRAW) }
    var objMenu by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf<String?>(null) }   // "new" / "rename"
    var nameText by remember { mutableStateOf("") }
    var askDeleteObj by remember { mutableStateOf(false) }

    val dbSk = remember(obj?.data) { obj?.sketch ?: ObjSketch() }
    // změny se zobrazí hned (místní stav), uložení do databáze běží na pozadí; nová data z databáze místní stav nahradí
    var localSk by remember { mutableStateOf<Pair<Long, ObjSketch>?>(null) }
    LaunchedEffect(obj?.data) { localSk = null }
    val sk = localSk?.takeIf { it.first == obj?.id }?.second ?: dbSk
    val lastMeas = remember(meas) { meas.filter { it.endMs != null }.groupBy { it.pointId }.mapValues { it.value.last() } }
    val pointById = remember(points) { points.associateBy { it.id } }
    val obs = remember(sk, pointById, lastMeas) { obsOf(sk, pointById, lastMeas) }
    val controlH = remember(points) { points.filter { it.control && it.hEll != null }.minByOrNull { it.createdMs }?.hEll }
    val result = remember(sk, obs, controlH) { ObjCalc.compute(sk, obs, controlH) }
    val world = mode != ObjMode.DRAW && result.ok

    // výběry v režimech
    var tapeFirst by remember { mutableStateOf<String?>(null) }
    var tapeDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var tapeEdit by remember { mutableStateOf<ObjTape?>(null) }
    var measSel by remember { mutableStateOf<List<String>>(emptyList()) }
    var placeDialog by remember { mutableStateOf<ObjPlace?>(null) }
    var dragV by remember { mutableStateOf<Pair<Int, Offset>?>(null) }   // id vrcholu, poloha v náčrtu během tažení
    var stroke by remember { mutableStateOf<List<Offset>>(emptyList()) }  // kreslení prstem (obrazovka)
    var askClear by remember { mutableStateOf(false) }
    var selEdge by remember { mutableStateOf<Int?>(null) }            // vybraná stěna (index)
    var selEdgePt by remember { mutableStateOf<Offset?>(null) }       // místo klepnutí na stěnu (náčrt)
    var dragEdge by remember { mutableStateOf<Triple<Int, Int, Offset>?>(null) }  // id vrcholů stěny a posun (náčrt)
    var whole by remember { mutableStateOf(false) }                   // úprava celého náčrtu
    var xf by remember { mutableStateOf<SkXf?>(null) }                // probíhající posun / otočení / zvětšení celého náčrtu
    val undoVer by c.undoVersion.collectAsStateWithLifecycle()

    // pohled
    var vScale by remember { mutableFloatStateOf(1f) }
    var vOff by remember { mutableStateOf(Offset.Zero) }
    var fitReq by remember { mutableIntStateOf(1) }
    var canvasW by remember { mutableFloatStateOf(0f) }
    var canvasH by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val hitR = with(density) { 22.dp.toPx() }

    // počátek skutečných souřadnic (stabilní pro objekt)
    val origin = remember(result.vertices.firstOrNull()?.e, result.places.firstOrNull()?.e, world) {
        result.vertices.firstOrNull()?.let { doubleArrayOf(it.e, it.n) }
            ?: result.places.firstOrNull()?.let { doubleArrayOf(it.e, it.n) } ?: doubleArrayOf(0.0, 0.0)
    }
    // těžiště náčrtu (střed otáčení a zvětšení celého náčrtu)
    val skC = remember(sk) {
        if (sk.vertices.isEmpty()) Offset.Zero
        else Offset(sk.vertices.map { it.x }.average().toFloat(), sk.vertices.map { it.y }.average().toFloat())
    }
    // souřadnice zobrazení D (y dolů): náčrt přímo, skutečné e, n vůči počátku
    fun dOfSketch(x: Double, y: Double): Offset {
        if (!world) return xf?.apply(Offset(x.toFloat(), y.toFloat()), skC) ?: Offset(x.toFloat(), y.toFloat())
        val w = result.toWorld(x, y) ?: return Offset(x.toFloat(), y.toFloat())
        return Offset((w[0] - origin[0]).toFloat(), (-(w[1] - origin[1])).toFloat())
    }
    fun dOfWorld(e: Double, n: Double) = Offset((e - origin[0]).toFloat(), (-(n - origin[1])).toFloat())
    fun toScreen(d: Offset) = Offset(d.x * vScale + vOff.x, d.y * vScale + vOff.y)
    fun toD(s: Offset) = Offset((s.x - vOff.x) / vScale, (s.y - vOff.y) / vScale)
    /** Obrazovka → souřadnice náčrtu. */
    fun toSketch(s: Offset): Offset {
        val d = toD(s)
        if (!world) return d
        val q = result.toSketch(d.x + origin[0], -d.y + origin[1]) ?: return d
        return Offset(q[0].toFloat(), q[1].toFloat())
    }

    // polohy prvků v souřadnicích D
    val vPos: List<Offset> = sk.vertices.mapIndexed { i, v ->
        val drag = dragV
        val de = dragEdge
        when {
            drag != null && drag.first == v.id && !world -> drag.second
            world -> result.vertices.getOrNull(i)?.let { dOfWorld(it.e, it.n) } ?: dOfSketch(v.x, v.y)
            de != null && (de.first == v.id || de.second == v.id) -> Offset(v.x.toFloat(), v.y.toFloat()) + de.third
            else -> dOfSketch(v.x, v.y)
        }
    }
    if (selEdge != null && selEdge!! >= sk.edges().size) selEdge = null
    fun placePos(p: ObjPlace): Offset {
        if (world) result.places.firstOrNull { it.placeId == p.id }?.let { return dOfWorld(it.e, it.n) }
        if (p.type == ObjPointType.CORNER) sk.vIndex(p.vertex).takeIf { it >= 0 }?.let { return vPos[it] }
        return dOfSketch(p.x, p.y)
    }
    fun refD(ref: String): Offset? {
        val vi = ObjCalc.refVertex(sk, ref)
        if (vi >= 0) return vPos.getOrNull(vi)
        val id = ref.substring(2).toIntOrNull()
        return sk.places.firstOrNull { it.id == id }?.let { placePos(it) }
    }
    val edges = sk.edges()

    // přizpůsobení pohledu obsahu
    LaunchedEffect(fitReq, world, obj?.id, canvasW) {
        if (canvasW <= 0f) return@LaunchedEffect
        val pts = vPos + sk.places.map { placePos(it) }
        if (pts.isEmpty()) {
            vScale = 1f
            vOff = Offset(0f, 0f)
            return@LaunchedEffect
        }
        val minX = pts.minOf { it.x }
        val maxX = pts.maxOf { it.x }
        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val w = max(maxX - minX, 1e-3f)
        val h = max(maxY - minY, 1e-3f)
        val s = min((canvasW - 2 * hitR * 2) / w, (canvasH - 2 * hitR * 2) / h).coerceIn(1e-3f, 1e5f)
        vScale = s
        vOff = Offset(canvasW / 2 - (minX + maxX) / 2 * s, canvasH / 2 - (minY + maxY) / 2 * s)
    }

    /** Změna náčrtu: hned na obrazovce, uložení (krok pro Zpět) na pozadí. */
    fun edit(f: (ObjSketch) -> ObjSketch) {
        val o = obj ?: return
        val n = f(sk)
        if (n == sk) return
        localSk = o.id to n
        c.setSketch(o.id, n)
    }

    fun hitTest(s: Offset, wantTapes: Boolean, wantPlaces: Boolean): Hit? {
        var best: Hit? = null
        var bd = hitR
        if (wantPlaces) sk.places.forEach { p ->
            if (p.type == ObjPointType.CORNER && mode == ObjMode.POINTS) return@forEach  // roh = vrchol
            val d = (toScreen(placePos(p)) - s).getDistance()
            if (d < bd) { bd = d; best = Hit.Place(p.id) }
        }
        vPos.forEachIndexed { i, p ->
            val d = (toScreen(p) - s).getDistance()
            if (d < bd) { bd = d; best = Hit.Vertex(i) }
        }
        if (best != null) return best
        if (wantTapes) sk.tapes.forEach { t ->
            val a = refD(t.a) ?: return@forEach
            val b = refD(t.b) ?: return@forEach
            val m = toScreen(Offset((a.x + b.x) / 2, (a.y + b.y) / 2))
            val d = (m - s).getDistance()
            if (d < bd) { bd = d; best = Hit.Tape(t.id) }
        }
        if (best != null) return best
        edges.forEachIndexed { i, (ia, ib) ->
            val d = segDist(s, toScreen(vPos[ia]), toScreen(vPos[ib]))
            if (d < bd) { bd = d; best = Hit.Edge(i) }
        }
        return best
    }

    fun refOfHit(h: Hit?): String? = when (h) {
        is Hit.Vertex -> "v:${sk.vertices[h.index].id}"
        is Hit.Place -> sk.places.firstOrNull { it.id == h.id }?.let { p ->
            if (p.type == ObjPointType.CORNER) p.vertex?.let { "v:$it" } else "p:${p.id}"
        }
        else -> null
    }

    fun onTap(s: Offset) {
        val o = obj ?: return
        when (mode) {
            ObjMode.DRAW -> {
                val h = hitTest(s, wantTapes = false, wantPlaces = false)
                val q = toSketch(s)
                if (whole) return
                when (h) {
                    is Hit.Vertex -> {
                        selEdge = null
                        if (!sk.closed && h.index == 0 && sk.vertices.size >= 3) edit { it.copy(closed = true) }
                    }
                    is Hit.Edge -> {
                        // výběr stěny: posun tažením, vložit vrchol, smazat
                        selEdge = h.index
                        selEdgePt = q
                    }
                    else -> if (selEdge != null) selEdge = null
                    else if (sk.closed) c.postMessage("Obrys je uzavřený – vrchol vložíš výběrem stěny (klepnutím na ni)")
                    else edit { s0 -> s0.copy(vertices = s0.vertices + ObjVertex(s0.nextId, q.x.toDouble(), q.y.toDouble()), nextId = s0.nextId + 1) }
                }
            }
            ObjMode.POINTS -> {
                val q = toSketch(s)
                when (val h = hitTest(s, wantTapes = false, wantPlaces = true)) {
                    is Hit.Place -> placeDialog = sk.places.firstOrNull { it.id == h.id }
                    is Hit.Vertex -> {
                        val v = sk.vertices[h.index]
                        placeDialog = sk.places.firstOrNull { it.type == ObjPointType.CORNER && it.vertex == v.id }
                            ?: ObjPlace(0, 0, ObjPointType.CORNER, vertex = v.id, mount = if (convex(sk, h.index)) ObjMount.OUTER else ObjMount.INNER, x = v.x, y = v.y)
                    }
                    is Hit.Edge -> placeDialog = ObjPlace(0, 0, ObjPointType.WALL, edge = sk.vertices[edges[h.index].first].id,
                        mount = ObjMount.WALL, x = q.x.toDouble(), y = q.y.toDouble())
                    else -> {
                        // mimo obrys: v ose nejbližší stěny (přímka), nebo kontrolní
                        val ne = edges.indices.minByOrNull { lineDistScreen(s, toScreen(vPos[edges[it].first]), toScreen(vPos[edges[it].second])) }
                        placeDialog = if (ne != null) ObjPlace(0, 0, ObjPointType.AXIS, edge = sk.vertices[edges[ne].first].id, x = q.x.toDouble(), y = q.y.toDouble())
                        else ObjPlace(0, 0, ObjPointType.CONTROL, x = q.x.toDouble(), y = q.y.toDouble())
                    }
                }
            }
            ObjMode.TAPE -> {
                val h = hitTest(s, wantTapes = true, wantPlaces = true)
                if (h is Hit.Tape) {
                    tapeEdit = sk.tapes.firstOrNull { it.id == h.id }
                    tapeFirst = null
                    return
                }
                val r = refOfHit(h)
                if (r == null) {
                    tapeFirst = null
                    return
                }
                val f = tapeFirst
                if (f == null || f == r) tapeFirst = r else {
                    tapeDialog = f to r
                    tapeFirst = null
                }
            }
            ObjMode.MEASURE -> {
                val r = refOfHit(hitTest(s, wantTapes = false, wantPlaces = true))
                measSel = if (r == null) emptyList() else if (measSel.size >= 2) listOf(r) else measSel + r
            }
        }
    }

    /**
     * Čára prstem → vrcholy (zjednodušení Douglas–Peucker, tolerance 12 dp). Navazuje na konec otevřeného obrysu,
     * když začne u posledního vrcholu; skončí-li u prvního vrcholu, obrys se uzavře.
     */
    fun onStroke(pts: List<Offset>) {
        val o = obj ?: return
        if (pts.size < 2) return
        // tolerance ~20 dp: nerovná čára prstem se narovná na úsečky, vrcholy jen v lomech
        val tol = hitR * 0.9f
        val simp = rdp(pts, tol)
        if (sk.closed) {
            c.postMessage("Obrys je uzavřený – otevři ho, nebo smaž náčrt")
            return
        }
        val startNearLast = vPos.lastOrNull()?.let { (toScreen(it) - simp.first()).getDistance() < hitR * 1.5f } == true
        val endNearFirst = vPos.firstOrNull()?.let { (toScreen(it) - simp.last()).getDistance() < hitR * 1.5f } == true ||
            (vPos.isEmpty() && simp.size >= 4 && (simp.first() - simp.last()).getDistance() < hitR * 1.5f)
        var add = simp
        if (startNearLast) add = add.drop(1)
        if (endNearFirst && add.isNotEmpty()) add = add.dropLast(1)
        val sketchPts = add.map { toSketch(it) }
        edit { s0 ->
            var id = s0.nextId
            val nv = sketchPts.map { q -> ObjVertex(id++, q.x.toDouble(), q.y.toDouble()) }
            val all = s0.vertices + nv
            s0.copy(vertices = all, nextId = id, closed = endNearFirst && all.size >= 3)
        }
    }

    fun onLongPress(s: Offset) {
        val o = obj ?: return
        if (mode != ObjMode.DRAW) return
        val h = hitTest(s, wantTapes = false, wantPlaces = false)
        if (h is Hit.Vertex && !whole) {
            selEdge = null
            edit { s0 -> removeVertex(s0, s0.vertices.getOrNull(h.index)?.id ?: -1) }
        }
    }

    // Pozor: odkazy ::funkce jsou si rovny i s jiným zachyceným stavem → rememberUpdatedState by držel první
    // (obj = null). Proto vždy nové lambdy (0.11.5 – kreslení „bez objektu“).
    val cb = rememberUpdatedState(Triple(
        { p: Offset -> onTap(p) },
        { p: Offset -> onLongPress(p) },
        { p: Offset, t: Boolean, pl: Boolean -> hitTest(p, t, pl) },
    ))
    val strokeCb = rememberUpdatedState({ pts: List<Offset> -> onStroke(pts) })
    val editRef = rememberUpdatedState({ f: (ObjSketch) -> ObjSketch -> edit(f) })
    val stateRef = rememberUpdatedState(Triple(mode, obj?.id, sk))
    val drawRef = rememberUpdatedState(Triple(whole, selEdge, skC))

    Column(Modifier.fillMaxSize()) {
        // objekt
        Row(Modifier.padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { objMenu = true }) { Text("Objekt: ${obj?.name ?: "žádný"} ▾", style = MaterialTheme.typography.titleSmall) }
                DropdownMenu(expanded = objMenu, onDismissRequest = { objMenu = false }) {
                    objects.forEach { o ->
                        DropdownMenuItem(text = { Text(o.name + if (o.id == obj?.id) "  ✓" else "") }, onClick = {
                            objMenu = false
                            selId = o.id
                            fitReq++
                        })
                    }
                    DropdownMenuItem(text = { Text("Nový objekt…") }, onClick = {
                        objMenu = false
                        nameText = ""
                        nameDialog = "new"
                    })
                }
            }
            if (obj != null) {
                TextButton(onClick = {
                    nameText = obj.name
                    nameDialog = "rename"
                }) { Text("Přejmenovat") }
                TextButton(onClick = { askDeleteObj = true }) { Text("Smazat", color = MaterialTheme.colorScheme.error) }
            }
        }
        if (obj == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lokalita nemá žádný objekt.")
                OutlinedButton(onClick = {
                    nameText = ""
                    nameDialog = "new"
                }) { Text("Nový objekt") }
                Text(
                    "Objekt = náčrt půdorysu (bez měřítka), změřené body a kóty pásmem. Po 2 změřených bodech se náčrt " +
                        "převede na skutečné souřadnice a postupně zpřesňuje.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            Row(Modifier.padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ObjMode.NAMES.forEachIndexed { i, n ->
                    FilterChip(selected = mode == i, onClick = {
                        mode = i
                        tapeFirst = null
                        measSel = emptyList()
                        fitReq++
                    }, label = { Text(n) })
                }
                TextButton(onClick = { fitReq++ }) { Text("Celé") }
            }
            Text(
                when (mode) {
                    ObjMode.DRAW -> when {
                        whole -> "Celý náčrt: táhni = posun, dvěma prsty = otočení a velikost. Pohled se nemění."
                        selEdge != null -> "Stěna vybrána: táhni ji = posun, nebo Vložit vrchol / Smazat stěnu."
                        else -> "Táhni prstem = čára obrysu, klepni = vrchol, na stěnu = výběr stěny, na první vrchol = uzavřít. " +
                            "Vrchol táhni = posun, podrž = smazat. Posun a zoom dvěma prsty."
                    }
                    ObjMode.POINTS -> "Klepni na roh, na stěnu (dveře), nebo mimo obrys (bod v ose stěny / kontrolní) a vyber změřený bod."
                    ObjMode.TAPE -> if (tapeFirst == null) "Klepni na první bod kóty (vrchol nebo bod), pak na druhý. Klepnutí na číslo kóty = úprava."
                    else "Klepni na druhý bod kóty."
                    else -> "Klepni na dva body – vzdálenost, směr, rozdíl výšek a přesnost."
                },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp),
            )
            if (mode == ObjMode.DRAW) Row(Modifier.padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { edit { it.copy(closed = !it.closed) } }, enabled = sk.vertices.size >= 3 || sk.closed) {
                    Text(if (sk.closed) "Otevřít obrys" else "Uzavřít obrys")
                }
                key(undoVer) {
                    OutlinedButton(onClick = { c.undoObject(obj.id, true); selEdge = null }, enabled = c.canUndo(obj.id)) { Text("Zpět") }
                    OutlinedButton(onClick = { c.undoObject(obj.id, false); selEdge = null }, enabled = c.canRedo(obj.id)) { Text("Znovu") }
                }
                FilterChip(selected = whole, onClick = {
                    whole = !whole
                    selEdge = null
                }, label = { Text("Upravit celý") }, enabled = sk.vertices.isNotEmpty())
                if (whole) OutlinedButton(onClick = {
                    // zrcadlení podle svislé osy těžiště
                    edit { s0 ->
                        val cx = s0.vertices.map { it.x }.average()
                        s0.copy(
                            vertices = s0.vertices.map { it.copy(x = 2 * cx - it.x) },
                            places = s0.places.map { it.copy(x = 2 * cx - it.x) },
                        )
                    }
                }) { Text("Zrcadlit") }
                OutlinedButton(onClick = { askClear = true }, enabled = sk.vertices.isNotEmpty()) {
                    Text("Smazat náčrt", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "vrcholů ${sk.vertices.size}" + if (sk.closed) ", uzavřeno" else "",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            val se = selEdge
            if (mode == ObjMode.DRAW && se != null && !whole) Row(
                Modifier.padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val (ia, ib) = edges.getOrNull(se) ?: (0 to 0)
                Text("Stěna V${ia + 1}–V${ib + 1}:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.CenterVertically))
                OutlinedButton(onClick = {
                    val q = selEdgePt
                    if (q != null) edit { s0 ->
                        val (a, b) = s0.edges().getOrNull(se) ?: return@edit s0
                        val v = ObjVertex(s0.nextId, q.x.toDouble(), q.y.toDouble())
                        val l = s0.vertices.toMutableList()
                        if (b == 0) l.add(v) else l.add(a + 1, v)
                        s0.copy(vertices = l, nextId = s0.nextId + 1)
                    }
                    selEdge = null
                }) { Text("Vložit vrchol") }
                OutlinedButton(onClick = {
                    if (!sk.closed && ia != 0 && ib != sk.vertices.lastIndex)
                        c.postMessage("Prostřední stěnu otevřeného obrysu nejde smazat – smaž vrchol, nebo obrys uzavři")
                    else edit { s0 -> deleteEdge(s0, se) }
                    selEdge = null
                }) { Text("Smazat stěnu", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { selEdge = null }) { Text("Zrušit výběr") }
            }
            // plátno
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(4.dp)
                    .background(Color(0xFFF7F7F2), RoundedCornerShape(6.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = down.position
                            val t0 = down.uptimeMillis
                            val (md, oid, sk0) = stateRef.value
                            val (wh, sel, cen) = drawRef.value
                            // tažení vrcholu jen v režimu Kreslit
                            // (od posledního vrcholu otevřeného obrysu se kreslí dál, ne táhne)
                            val grab = if (md == ObjMode.DRAW && oid != null && !wh)
                                (cb.value.third(start, false, false) as? Hit.Vertex)?.index
                                    ?.takeIf { sk0.closed || it != sk0.vertices.lastIndex } else null
                            // Kreslit: tažení jedním prstem mimo vrchol = čára obrysu (posun mapy dvěma prsty)
                            // vybraná stěna: tažení = posun stěny (oba vrcholy)
                            val edgeGrab = if (md == ObjMode.DRAW && oid != null && !wh && grab == null && sel != null)
                                (cb.value.third(start, false, false) as? Hit.Edge)?.index?.takeIf { it == sel } else null
                            val wholeDrag = md == ObjMode.DRAW && oid != null && wh
                            val drawing = md == ObjMode.DRAW && oid != null && grab == null && edgeGrab == null && !wh
                            var moved = false
                            var multi = false
                            var long = false
                            var last = start
                            var nowMs = t0
                            while (true) {
                                val elapsed = nowMs - t0
                                val ev = if (!moved && !multi && !long) withTimeoutOrNull(max(1L, 550L - elapsed)) { awaitPointerEvent() }
                                else awaitPointerEvent()
                                if (ev == null) {
                                    long = true
                                    cb.value.second(start)
                                    continue
                                }
                                ev.changes.firstOrNull()?.let { nowMs = it.uptimeMillis }
                                val pressed = ev.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    if (wholeDrag) {
                                        val t = xf
                                        if (t != null && oid != null) editRef.value { s0 ->
                                            s0.copy(
                                                vertices = s0.vertices.map { v -> t.apply(Offset(v.x.toFloat(), v.y.toFloat()), cen).let { v.copy(x = it.x.toDouble(), y = it.y.toDouble()) } },
                                                places = s0.places.map { p -> t.apply(Offset(p.x.toFloat(), p.y.toFloat()), cen).let { p.copy(x = it.x.toDouble(), y = it.y.toDouble()) } },
                                            )
                                        }
                                        xf = null
                                    } else if (edgeGrab != null && moved) {
                                        val d = dragEdge
                                        if (d != null && oid != null) editRef.value { s0 ->
                                            s0.copy(vertices = s0.vertices.map {
                                                if (it.id == d.first || it.id == d.second) it.copy(x = it.x + d.third.x, y = it.y + d.third.y) else it
                                            })
                                        }
                                        dragEdge = null
                                    } else if (drawing && moved && !multi) {
                                        strokeCb.value(stroke)
                                        stroke = emptyList()
                                    } else if (grab != null && moved) {
                                        val v = dragV
                                        if (v != null && oid != null) editRef.value { s0 ->
                                            s0.copy(vertices = s0.vertices.map { if (it.id == v.first) it.copy(x = v.second.x.toDouble(), y = v.second.y.toDouble()) else it })
                                        }
                                        dragV = null
                                    } else if (!moved && !multi && !long) cb.value.first(start)
                                    break
                                }
                                if (pressed.size >= 2) {
                                    multi = true
                                    if (stroke.isNotEmpty()) stroke = emptyList()
                                    val z = ev.calculateZoom()
                                    val p = ev.calculatePan()
                                    val cc = ev.calculateCentroid()
                                    if (wholeDrag) {
                                        // celý náčrt: otočení, velikost a posun (pohled zůstává)
                                        val t = xf ?: SkXf()
                                        xf = t.copy(
                                            t = t.t + p / vScale,
                                            rot = t.rot + Math.toRadians(ev.calculateRotation().toDouble()).toFloat(),
                                            s = (t.s * z).coerceIn(0.05f, 20f),
                                        )
                                        ev.changes.forEach { it.consume() }
                                        continue
                                    }
                                    vOff = Offset(cc.x - (cc.x - vOff.x) * z, cc.y - (cc.y - vOff.y) * z) + p
                                    vScale *= z
                                    ev.changes.forEach { it.consume() }
                                    continue
                                }
                                val ch = pressed.first()
                                if (!moved && (ch.position - start).getDistance() > viewConfiguration.touchSlop) moved = true
                                if (moved && !multi) {
                                    when {
                                        grab != null -> {
                                            val vid = sk0.vertices.getOrNull(grab)?.id
                                            if (vid != null) dragV = vid to toD(ch.position)
                                        }
                                        wholeDrag -> xf = (xf ?: SkXf()).let { it.copy(t = it.t + (ch.position - last) / vScale) }
                                        edgeGrab != null -> {
                                            val (ia, ib) = sk0.edges().getOrNull(edgeGrab) ?: (0 to 0)
                                            val a = sk0.vertices.getOrNull(ia)?.id
                                            val b = sk0.vertices.getOrNull(ib)?.id
                                            if (a != null && b != null) dragEdge = Triple(a, b, toD(ch.position) - toD(start))
                                        }
                                        drawing -> stroke = if (stroke.isEmpty()) listOf(start, ch.position) else stroke + ch.position
                                        else -> vOff += ch.position - last
                                    }
                                    ch.consume()
                                }
                                last = ch.position
                            }
                        }
                    },
            ) {
                val labelColor = MaterialTheme.colorScheme.onSurface
                Canvas(Modifier.fillMaxSize()) {
                    canvasW = size.width
                    canvasH = size.height
                    drawObject(
                        sk, result, world, mode, vPos, ::placePos, ::refD, ::toScreen, edges,
                        tapeFirst, measSel, labelColor, density.density,
                    )
                    selEdge?.takeIf { mode == ObjMode.DRAW }?.let { ei ->
                        edges.getOrNull(ei)?.let { (ia, ib) ->
                            drawLine(Color(0xFFFFA000), toScreen(vPos[ia]), toScreen(vPos[ib]), 6f * density.density)
                        }
                    }
                    for (i in 1 until stroke.size) drawLine(Color(0xFFFFA000), stroke[i - 1], stroke[i], 3f * density.density)
                }
                if (mode == ObjMode.DRAW) Text(
                    "vrcholů ${sk.vertices.size}" + (if (sk.closed) ", uzavřeno" else "") +
                        (cz.antonin.rovergnss.data.ObjSketchJson.lastError?.let { " · chyba čtení $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = Color(0xFF616161),
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                )
                if (mode == ObjMode.DRAW && sk.vertices.isEmpty() && stroke.isEmpty()) Text(
                    "Táhni prstem obrys budovy (lomy = vrcholy), nebo klepej vrcholy jeden po druhém. " +
                        "Konec čáry u začátku obrys uzavře. Posun a zoom dvěma prsty.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                if (mode != ObjMode.DRAW && !result.ok) Text(
                    result.message, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.TopCenter).padding(6.dp)
                        .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(6.dp),
                )
            }
            // výsledky
            Column(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (mode == ObjMode.MEASURE && measSel.size == 2) MeasureCard(sk, result, measSel[0], measSel[1])
                ResultPanel(c, obj, sk, result, lastMeas, job?.name ?: "lokalita")
            }
        }
    }

    // ---------------------------------------------------------------- dialogy
    nameDialog?.let { kind ->
        AlertDialog(
            onDismissRequest = { nameDialog = null },
            title = { Text(if (kind == "new") "Nový objekt" else "Přejmenovat objekt") },
            text = { OutlinedTextField(nameText, { nameText = it }, singleLine = true, label = { Text("Název, např. kostel") }) },
            confirmButton = {
                TextButton(onClick = {
                    val j = job
                    if (kind == "new" && j != null) c.createObject(j.id, nameText) { id ->
                        selId = id
                        mode = ObjMode.DRAW
                        fitReq++
                    } else if (obj != null) c.renameObject(obj, nameText)
                    nameDialog = null
                }, enabled = nameText.isNotBlank()) { Text("Uložit") }
            },
            dismissButton = { TextButton(onClick = { nameDialog = null }) { Text("Zrušit") } },
        )
    }
    if (askClear && obj != null) AlertDialog(
        onDismissRequest = { askClear = false },
        title = { Text("Smazat náčrt?") },
        text = { Text("Všechny vrcholy a s nimi rohy, body na stěnách, body v ose a kóty. Kontrolní body a body lokality zůstávají.") },
        confirmButton = {
            TextButton(onClick = {
                askClear = false
                edit { s0 ->
                    val keep = s0.places.filter { it.type == ObjPointType.CONTROL }
                    val keepIds = keep.map { "p:${it.id}" }.toSet()
                    s0.copy(vertices = emptyList(), closed = false, places = keep, tapes = s0.tapes.filter { it.a in keepIds && it.b in keepIds })
                }
            }) { Text("Smazat", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { askClear = false }) { Text("Zrušit") } },
    )
    if (askDeleteObj && obj != null) AlertDialog(
        onDismissRequest = { askDeleteObj = false },
        title = { Text("Smazat objekt ${obj.name}?") },
        text = { Text("Náčrt, umístění bodů a kóty. Body lokality a jejich měření zůstávají.") },
        confirmButton = {
            TextButton(onClick = {
                askDeleteObj = false
                c.deleteObject(obj)
                selId = null
            }) { Text("Smazat", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { askDeleteObj = false }) { Text("Zrušit") } },
    )
    placeDialog?.let { pl ->
        val o = obj
        if (o != null) PlaceDialog(
            c, sk, pl, points, lastMeas, edges,
            canMeasure = link is LinkState.Connected && running == null,
            onSave = { np ->
                placeDialog = null
                edit { s0 ->
                    if (np.id == 0) s0.copy(places = s0.places + np.copy(id = s0.nextId), nextId = s0.nextId + 1)
                    else s0.copy(places = s0.places.map { if (it.id == np.id) np else it })
                }
            },
            onMeasure = { np ->
                placeDialog = null
                val j = job ?: return@PlaceDialog
                scope.launch {
                    val name = c.nextPointName(j.id)
                    // id umístění se přidělí hned (pointId doplní REC)
                    var newId = 0
                    c.updateObject(o.id) { s0 -> newId = s0.nextId; s0.copy(nextId = s0.nextId + 1) }
                    val base = if (np.id == 0) np.copy(id = newId) else np
                    if (np.id != 0) c.updateObject(o.id) { s0 -> s0.copy(places = s0.places.filter { it.id != np.id }) }
                    c.startPointMeasure(j.id, null, name, o.id to base)
                }
            },
            onRemove = if (pl.id != 0) ({
                placeDialog = null
                edit { s0 -> removePlace(s0, pl.id) }
            }) else null,
            onDismiss = { placeDialog = null },
        )
    }
    tapeDialog?.let { (a, b) ->
        val o = obj
        TapeDialog(null, onSave = { len ->
            tapeDialog = null
            if (o != null) edit { s0 -> s0.copy(tapes = s0.tapes + ObjTape(s0.nextId, a, b, len), nextId = s0.nextId + 1) }
        }, onDelete = null, onDismiss = { tapeDialog = null })
    }
    tapeEdit?.let { t ->
        val o = obj
        TapeDialog(t.len, onSave = { len ->
            tapeEdit = null
            if (o != null) edit { s0 -> s0.copy(tapes = s0.tapes.map { if (it.id == t.id) it.copy(len = len) else it }) }
        }, onDelete = {
            tapeEdit = null
            if (o != null) edit { s0 -> s0.copy(tapes = s0.tapes.filter { it.id != t.id }) }
        }, onDismiss = { tapeEdit = null })
    }
}

/** Polohy bodů pro výpočet: S-JTSK bodu, přesnost a podezření z posledního měření. */
private fun obsOf(sk: ObjSketch, points: Map<Long, Point>, lastMeas: Map<Long, Measurement>): Map<Int, ObjObs> =
    sk.places.filter { it.pointId > 0 }.mapNotNull { pl ->
        val p = points[pl.pointId] ?: return@mapNotNull null
        val y = p.y ?: return@mapNotNull null
        val x = p.x ?: return@mapNotNull null
        val m = lastMeas[p.id]
        val (sig, susp) = if (m != null && m.result != PointResult.NONE) {
            val s = m.sMeanH ?: if (m.sN != null && m.sE != null) hypot(m.sN, m.sE) else 0.02
            max(s, 0.01) to (m.clusters >= 2 || m.usedQuality != Quality.FIX.code || m.result == PointResult.FAILED)
        } else if (p.quality == PointQuality.EXACT) 0.02 to false else 0.10 to (p.source == PointSource.MEASURED)
        pl.id to ObjObs(p.name, y, x, p.hEll, sig, susp)
    }.toMap()

/** Vrchol i je v náčrtu vypuklý (pro výchozí přiložení „vnější roh“). */
private fun convex(sk: ObjSketch, i: Int): Boolean {
    val n = sk.vertices.size
    if (n < 3) return true
    val pv = sk.vertices[(i - 1 + n) % n]
    val v = sk.vertices[i]
    val nx = sk.vertices[(i + 1) % n]
    // náčrt má y dolů → převod na y nahoru
    var area = 0.0
    for (k in 0 until n) {
        val a = sk.vertices[k]
        val b = sk.vertices[(k + 1) % n]
        area += a.x * (-b.y) - b.x * (-a.y)
    }
    val cross = (v.x - pv.x) * (-(nx.y) + v.y) - (-(v.y) + pv.y) * (nx.x - v.x)
    return if (area >= 0) cross >= 0 else cross <= 0
}

/** Smaže vrchol a vše, co na něm visí (rohy, body na jeho stěnách, kóty). */
private fun removeVertex(s: ObjSketch, id: Int): ObjSketch {
    val i = s.vIndex(id)
    if (i < 0) return s
    val placesGone = s.places.filter { it.vertex == id || it.edge == id }.map { it.id }.toSet()
    return s.copy(
        vertices = s.vertices.filter { it.id != id },
        places = s.places.filter { it.id !in placesGone },
        tapes = s.tapes.filter { t -> listOf(t.a, t.b).none { it == "v:$id" || placesGone.any { p -> it == "p:$p" } } },
        closed = s.closed && s.vertices.size > 3,
    )
}

/** Posun [t], otočení [rot] (rad) a zvětšení [s] celého náčrtu kolem těžiště. */
private data class SkXf(val t: Offset = Offset.Zero, val rot: Float = 0f, val s: Float = 1f) {
    fun apply(p: Offset, c: Offset): Offset {
        val dx = p.x - c.x
        val dy = p.y - c.y
        val co = kotlin.math.cos(rot)
        val si = kotlin.math.sin(rot)
        return Offset(c.x + s * (co * dx - si * dy) + t.x, c.y + s * (si * dx + co * dy) + t.y)
    }
}

/**
 * Smaže stěnu [ei]: uzavřený obrys se na ní rozpojí (pořadí vrcholů začne za ní), u otevřeného jen krajní stěna
 * (odebere koncový vrchol). Body a kóty na smazané stěně se odeberou.
 */
private fun deleteEdge(s: ObjSketch, ei: Int): ObjSketch {
    val e = s.edges().getOrNull(ei) ?: return s
    val (ia, ib) = e
    val startId = s.vertices[ia].id
    if (s.closed) {
        val n = s.vertices.size
        val order = (0 until n).map { (ib + it) % n }
        val gone = s.places.filter { it.edge == startId }.map { it.id }.toSet()
        return s.copy(
            vertices = order.map { s.vertices[it] }, closed = false,
            places = s.places.filter { it.id !in gone },
            tapes = s.tapes.filter { t -> gone.none { g -> t.a == "p:$g" || t.b == "p:$g" } },
        )
    }
    return when {
        ia == 0 -> removeVertex(s, s.vertices[0].id)
        ib == s.vertices.lastIndex -> removeVertex(s, s.vertices[ib].id)
        else -> s
    }
}

private fun removePlace(s: ObjSketch, id: Int): ObjSketch =
    s.copy(places = s.places.filter { it.id != id }, tapes = s.tapes.filter { it.a != "p:$id" && it.b != "p:$id" })

/** Zjednodušení čáry (Douglas–Peucker). */
private fun rdp(pts: List<Offset>, eps: Float): List<Offset> {
    if (pts.size < 3) return pts
    var idx = -1
    var dmax = 0f
    for (i in 1 until pts.size - 1) {
        val d = lineDistScreen(pts[i], pts.first(), pts.last()).let { if ((pts.first() - pts.last()).getDistance() < 1f) (pts[i] - pts.first()).getDistance() else it }
        if (d > dmax) {
            dmax = d
            idx = i
        }
    }
    if (dmax <= eps || idx < 0) return listOf(pts.first(), pts.last())
    return rdp(pts.subList(0, idx + 1), eps).dropLast(1) + rdp(pts.subList(idx, pts.size), eps)
}

private fun segDist(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val l2 = ab.x * ab.x + ab.y * ab.y
    if (l2 < 1e-6f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / l2).coerceIn(0f, 1f)
    return (p - Offset(a.x + t * ab.x, a.y + t * ab.y)).getDistance()
}

private fun lineDistScreen(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val l = ab.getDistance()
    if (l < 1e-3f) return (p - a).getDistance()
    return abs(ab.x * (p.y - a.y) - ab.y * (p.x - a.x)) / l
}

private fun DrawScope.drawObject(
    sk: ObjSketch, r: ObjResult, world: Boolean, mode: Int, vPos: List<Offset>, placePos: (ObjPlace) -> Offset,
    refD: (String) -> Offset?, toScreen: (Offset) -> Offset, edges: List<Pair<Int, Int>>,
    tapeFirst: String?, measSel: List<String>, labelColor: Color, dens: Float,
) {
    val sw = 2.5f * dens
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f * dens, 6f * dens))
    // stěny
    edges.forEachIndexed { i, (ia, ib) ->
        val a = toScreen(vPos[ia])
        val b = toScreen(vPos[ib])
        val er = if (world) r.edges.getOrNull(i) else null
        val col = if (er != null) SRC_COLORS[er.source] else Color(0xFF424242)
        drawLine(col, a, b, sw, pathEffect = if (er != null && er.source == ObjSource.SKETCH) dash else null)
        if (er != null) {
            val m = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
            label(String.format(Locale.US, "%.2f", er.len).replace('.', ','), m.x + 4 * dens, m.y - 4 * dens, col, dens)
        }
    }
    // osy stěn k bodům v ose
    if (world) sk.places.filter { it.type == ObjPointType.AXIS }.forEach { p ->
        val ei = sk.edgeIndex(p.edge)
        if (ei < 0) return@forEach
        val (ia, ib) = edges[ei]
        val pp = toScreen(placePos(p))
        val a = toScreen(vPos[ia])
        val b = toScreen(vPos[ib])
        val near = if ((pp - a).getDistance() < (pp - b).getDistance()) a else b
        drawLine(Color(0xFF1565C0).copy(alpha = 0.5f), near, pp, 1.5f * dens, pathEffect = dash)
    }
    // kóty
    sk.tapes.forEach { t ->
        val a = refD(t.a)?.let(toScreen) ?: return@forEach
        val b = refD(t.b)?.let(toScreen) ?: return@forEach
        val tr = r.tapes.firstOrNull { it.id == t.id }
        val col = if (tr?.bad == true) BAD else Color(0xFF8E24AA)
        drawLine(col, a, b, 1.5f * dens, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * dens, 4f * dens)))
        val m = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
        val diff = tr?.diff?.let { String.format(Locale.US, " (%+.1f)", it * 100).replace('.', ',') } ?: ""
        label("⟷" + String.format(Locale.US, "%.2f", t.len).replace('.', ',') + diff, m.x + 4 * dens, m.y + 14 * dens, col, dens)
    }
    // vrcholy
    vPos.forEachIndexed { i, p ->
        val s = toScreen(p)
        val vr = if (world) r.vertices.getOrNull(i) else null
        val col = vr?.let { SRC_COLORS[it.source] } ?: Color(0xFF424242)
        val sel = sk.vertices.getOrNull(i)?.let { v -> tapeFirst == "v:${v.id}" || "v:${v.id}" in measSel } == true
        drawCircle(col, radius = (if (sel) 9f else 6f) * dens, center = s)
        if (sel) drawCircle(Color(0xFFFFA000), radius = 12f * dens, center = s, style = Stroke(2.5f * dens))
        label(vr?.name ?: "V${i + 1}", s.x + 8 * dens, s.y - 8 * dens, labelColor, dens)
    }
    // umístěné body
    sk.places.forEach { p ->
        val s = toScreen(placePos(p))
        val pr = r.places.firstOrNull { it.placeId == p.id }
        val col = when {
            pr == null -> Color(0xFF9E9E9E)
            pr.bad -> BAD
            pr.suspect -> Color(0xFFEF6C00)
            else -> Color(0xFF2E7D32)
        }
        val sel = tapeFirst == "p:${p.id}" || "p:${p.id}" in measSel
        when (p.type) {
            ObjPointType.CORNER -> drawCircle(col, radius = 10f * dens, center = s, style = Stroke(2.5f * dens))
            ObjPointType.WALL -> drawRect(col, topLeft = Offset(s.x - 6 * dens, s.y - 6 * dens), size = androidx.compose.ui.geometry.Size(12 * dens, 12 * dens))
            ObjPointType.AXIS -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(s.x, s.y - 8 * dens); lineTo(s.x + 7 * dens, s.y + 6 * dens); lineTo(s.x - 7 * dens, s.y + 6 * dens); close()
                }
                drawPath(path, col)
            }
            else -> {
                drawCircle(col, radius = 7f * dens, center = s)
                drawCircle(Color.White, radius = 3f * dens, center = s)
            }
        }
        if (sel) drawCircle(Color(0xFFFFA000), radius = 13f * dens, center = s, style = Stroke(2.5f * dens))
        if (p.type != ObjPointType.CORNER || !world) label(pr?.name ?: if (p.pointId == 0L) "?" else "", s.x + 9 * dens, s.y + 14 * dens, col, dens)
    }
    // měření mezi dvěma body
    if (mode == ObjMode.MEASURE && measSel.size == 2) {
        val a = refD(measSel[0])?.let(toScreen)
        val b = refD(measSel[1])?.let(toScreen)
        if (a != null && b != null) drawLine(Color(0xFFFFA000), a, b, 2f * dens)
    }
}

private fun DrawScope.label(s: String, x: Float, y: Float, color: Color, dens: Float) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = 12f * dens
        this.color = android.graphics.Color.argb(255, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
    }
    drawContext.canvas.nativeCanvas.drawText(s, x, y, paint)
}

/** Výsledky výpočtu, kontrola a export. */
@Composable
private fun ResultPanel(c: RoverController, obj: SiteObject, sk: ObjSketch, r: ObjResult, lastMeas: Map<Long, Measurement>, jobName: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingSrs by remember { mutableStateOf<Gpkg.Srs?>(null) }
    val fixPct = remember(sk, lastMeas) { sk.places.associate { it.id to lastMeas[it.pointId]?.fixPct } }
    val gpkgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/geopackage+sqlite3")) { uri: Uri? ->
        val srs = pendingSrs
        pendingSrs = null
        if (uri == null || srs == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val tmp = File(context.cacheDir, "objekt_export.gpkg")
                    ObjExport.gpkg(tmp, srs, obj.name, sk, r, fixPct)
                    context.contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    tmp.delete()
                    true
                } catch (e: Exception) {
                    false
                }
            }
            c.postMessage(if (ok) "GeoPackage uložen (EPSG:${srs.id})" else "GeoPackage nelze zapsat")
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { ObjExport.verticesCsv(r, it) } ?: -1
                } catch (e: Exception) {
                    -1
                }
            }
            c.postMessage(if (n >= 0) "Exportováno $n vrcholů" else "Soubor nelze zapsat")
        }
    }
    val mono = MaterialTheme.typography.bodySmall
    Text(r.message, style = MaterialTheme.typography.bodyMedium)
    if (!r.ok) return
    r.sigma0?.let { s0 ->
        Text(
            String.format(Locale.US, "kvalita vyrovnání σ0 %.2f (nadbytečných měření %d)", s0, r.redundancy).replace('.', ',') +
                if (s0 > 2.5) " – měření si odporují, zkontroluj červené" else "",
            style = mono, color = if (s0 > 2.5) BAD else MaterialTheme.colorScheme.onSurface,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ObjSource.NAMES.forEachIndexed { i, n -> Text("● $n", color = SRC_COLORS[i], style = mono) }
    }
    if (r.vertices.isNotEmpty()) {
        HorizontalDivider()
        Text("Vrcholy (Y / X S-JTSK, přesnost, vnitřní úhel)", style = MaterialTheme.typography.titleSmall)
        r.vertices.forEach { v ->
            Text(
                String.format(Locale.US, "%-6s %11.3f %12.3f ±%4.1f cm %s", v.name, v.y, v.x, (v.sigma ?: 0.0) * 100,
                    v.angle?.let { String.format(Locale.US, "%6.1f°", it) } ?: "") + "  " + ObjSource.NAMES[v.source] +
                    (v.dh?.let { String.format(Locale.US, "  Δh %+.2f", it) } ?: ""),
                fontFamily = FontFamily.Monospace, style = mono, color = SRC_COLORS[v.source],
            )
        }
        Text("Stěny", style = MaterialTheme.typography.titleSmall)
        r.edges.forEach { e ->
            Text(
                String.format(Locale.US, "%s–%s  %.3f m ±%.1f cm  %s", r.vertices[e.a].name, r.vertices[e.b].name, e.len, (e.sigma ?: 0.0) * 100,
                    ObjSource.NAMES[e.source]) + (e.tape?.let { String.format(Locale.US, "  pásmo %.3f", it) } ?: ""),
                fontFamily = FontFamily.Monospace, style = mono, color = SRC_COLORS[e.source],
            )
        }
        Text(
            listOfNotNull(
                r.perimeter?.let { String.format(Locale.US, "obvod %.2f m", it) },
                r.area?.let { String.format(Locale.US, "plocha %.2f m²", it) },
                r.axisAz?.let { String.format(Locale.US, "azimut osy %.1f°", it) },
            ).joinToString(" · ").replace('.', ','),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (r.places.isNotEmpty()) {
        Text("Body (odchylka od výsledku)", style = MaterialTheme.typography.titleSmall)
        r.places.forEach { p ->
            Text(
                "${p.name} · ${ObjPointType.NAMES.getOrElse(p.type) { "" }}" +
                    (p.residual?.let { " · odchylka ${PointEvaluator.cm(it)} cm" } ?: "") +
                    (p.dh?.let { String.format(Locale.US, " · Δh %+.2f m", it) } ?: "") +
                    (if (p.suspect) " · podezřelý / bez fixu" else "") + (if (p.bad) " · CHYBNÝ" else ""),
                style = mono, color = if (p.bad) BAD else if (p.suspect) Color(0xFFEF6C00) else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    if (r.tapes.isNotEmpty()) {
        Text("Kóty (zadaná − vypočtená)", style = MaterialTheme.typography.titleSmall)
        r.tapes.forEach { t ->
            Text(
                String.format(Locale.US, "%.3f m", t.len) + (t.calc?.let { String.format(Locale.US, " / %.3f m", it) } ?: "") +
                    (t.diff?.let { " · rozdíl ${PointEvaluator.cm(it)} cm" } ?: "") + if (t.bad) " · CHYBNÁ" else "",
                style = mono, color = if (t.bad) BAD else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    HorizontalDivider()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = {
            pendingSrs = Gpkg.SRS_5514
            gpkgLauncher.launch(FileNames.job(jobName, obj.name, System.currentTimeMillis(), "gpkg"))
        }) { Text("GeoPackage 5514") }
        OutlinedButton(onClick = {
            pendingSrs = Gpkg.SRS_25833
            gpkgLauncher.launch(FileNames.job(jobName, obj.name + "_utm33", System.currentTimeMillis(), "gpkg"))
        }) { Text("GeoPackage UTM 33N") }
        OutlinedButton(onClick = {
            csvLauncher.launch(FileNames.job(jobName, obj.name + "_vrcholy", System.currentTimeMillis(), "csv"))
        }, enabled = r.vertices.isNotEmpty()) { Text("Vrcholy CSV") }
    }
    Text(
        "QGIS: projekt v EPSG:5514, vrstvy body, vrcholy, steny, pudorys, koty. Poloha bodů opravená o posun krabičky.",
        style = mono,
    )
}

/** Nástroj Změřit: vzdálenost, azimut, přesnost a rozdíl výšek mezi dvěma prvky. */
@Composable
private fun MeasureCard(sk: ObjSketch, r: ObjResult, a: String, b: String) {
    val m = if (r.ok) ObjCalc.measure(sk, r, a, b) else null
    fun name(ref: String): String {
        val vi = ObjCalc.refVertex(sk, ref)
        if (vi >= 0) return r.vertices.getOrNull(vi)?.name ?: "V${vi + 1}"
        val id = ref.substring(2).toIntOrNull()
        return r.places.firstOrNull { it.placeId == id }?.name ?: "?"
    }
    fun h(ref: String): Double? {
        val vi = ObjCalc.refVertex(sk, ref)
        if (vi >= 0) return r.vertices.getOrNull(vi)?.h
        val id = ref.substring(2).toIntOrNull()
        return r.places.firstOrNull { it.placeId == id }?.h
    }
    val tape = sk.tapes.firstOrNull { (it.a == a && it.b == b) || (it.a == b && it.b == a) }
    Column(
        Modifier.fillMaxWidth().background(Color(0xFFFFF3E0), RoundedCornerShape(6.dp)).padding(8.dp),
    ) {
        Text("${name(a)} → ${name(b)}", style = MaterialTheme.typography.titleSmall)
        if (m == null) Text("Bez výpočtu (aspoň 2 změřené body).", style = MaterialTheme.typography.bodySmall)
        else {
            val ha = h(a)
            val hb = h(b)
            Text(
                String.format(Locale.US, "%.3f m ±%.1f cm · směr %.1f°", m.first, (m.third ?: 0.0) * 100, m.second).replace('.', ',') +
                    (if (ha != null && hb != null) String.format(Locale.US, " · Δh %+.2f m", hb - ha).replace('.', ',') else ""),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
            )
            tape?.let { t ->
                Text(
                    String.format(Locale.US, "pásmo %.3f m, rozdíl %.1f cm", t.len, (t.len - m.first) * 100).replace('.', ','),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Umístění bodu: typ, stěna (u bodu v ose), přiložení krabičky a výběr změřeného bodu (nejnovější nahoře). */
@Composable
private fun PlaceDialog(
    c: RoverController,
    sk: ObjSketch,
    initial: ObjPlace,
    points: List<Point>,
    lastMeas: Map<Long, Measurement>,
    edges: List<Pair<Int, Int>>,
    canMeasure: Boolean,
    onSave: (ObjPlace) -> Unit,
    onMeasure: (ObjPlace) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var pl by remember { mutableStateOf(initial) }
    var edgeMenu by remember { mutableStateOf(false) }
    val df = remember { SimpleDateFormat("d. M. HH:mm", Locale.getDefault()) }
    // nejnovější nahoře: čas posledního měření, jinak založení bodu
    val sorted = remember(points, lastMeas) { points.sortedByDescending { lastMeas[it.id]?.startMs ?: it.createdMs } }
    val used = sk.places.filter { it.id != initial.id }.map { it.pointId }.toSet()
    fun edgeName(startId: Int?): String {
        val ei = sk.edgeIndex(startId)
        if (ei < 0) return "?"
        return "V${edges[ei].first + 1}–V${edges[ei].second + 1}"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0) "Umístit bod" else "Bod v náčrtu") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pl.type == ObjPointType.AXIS || pl.type == ObjPointType.CONTROL) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = pl.type == ObjPointType.AXIS, onClick = {
                        pl = pl.copy(type = ObjPointType.AXIS, edge = pl.edge ?: sk.vertices.firstOrNull()?.id, mount = ObjMount.FREE)
                    }, label = { Text("v ose stěny") }, enabled = edges.isNotEmpty())
                    FilterChip(selected = pl.type == ObjPointType.CONTROL, onClick = {
                        pl = pl.copy(type = ObjPointType.CONTROL, mount = ObjMount.FREE)
                    }, label = { Text("kontrolní") })
                } else Text(ObjPointType.NAMES[pl.type] + if (pl.type == ObjPointType.WALL) " ${edgeName(pl.edge)}" else "",
                    style = MaterialTheme.typography.titleSmall)
                if (pl.type == ObjPointType.AXIS) Box {
                    OutlinedButton(onClick = { edgeMenu = true }) { Text("Stěna ${edgeName(pl.edge)} ▾") }
                    DropdownMenu(expanded = edgeMenu, onDismissRequest = { edgeMenu = false }) {
                        edges.forEach { (ia, ib) ->
                            DropdownMenuItem(text = { Text("V${ia + 1}–V${ib + 1}") }, onClick = {
                                edgeMenu = false
                                pl = pl.copy(edge = sk.vertices[ia].id)
                            })
                        }
                    }
                }
                val mounts = when (pl.type) {
                    ObjPointType.CORNER -> listOf(ObjMount.OUTER, ObjMount.INNER, ObjMount.FREE)
                    ObjPointType.WALL -> listOf(ObjMount.WALL, ObjMount.FREE)
                    else -> emptyList()
                }
                if (mounts.isNotEmpty()) {
                    Text("Přiložení krabičky (posun ${PointEvaluator.cm(ObjMount.OFFSET_M)} cm od hran)", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        mounts.forEach { mt -> FilterChip(selected = pl.mount == mt, onClick = { pl = pl.copy(mount = mt) }, label = { Text(ObjMount.NAMES[mt]) }) }
                    }
                }
                Text("Změřený bod (nejnovější nahoře)", style = MaterialTheme.typography.titleSmall)
                if (sorted.isEmpty()) Text("Lokalita nemá body – změř REC.", style = MaterialTheme.typography.bodySmall)
                sorted.forEach { p ->
                    val m = lastMeas[p.id]
                    val warn = m != null && m.result != PointResult.NONE && (m.result != PointResult.OK || m.clusters >= 2)
                    val noXy = p.y == null || p.x == null
                    Column(
                        Modifier.fillMaxWidth()
                            .background(if (pl.pointId == p.id) Color(0xFFE3F2FD) else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable(enabled = !noXy) { pl = pl.copy(pointId = p.id) }
                            .padding(4.dp),
                    ) {
                        Text(
                            p.name + (if (p.id in used) "  (už v náčrtu)" else "") + if (noXy) "  (bez souřadnic)" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            listOfNotNull(
                                (m?.startMs ?: p.createdMs).let { df.format(Date(it)) },
                                m?.fixPct?.let { "fix ${it.toInt()} %" },
                                m?.sMeanH?.let { "σ ${PointEvaluator.cm(it)} cm" },
                                m?.takeIf { it.result != PointResult.NONE }?.let { PointResult.name(it.result) + if (it.clusters >= 2) ", podezřelý" else "" },
                                if (m == null) PointSource.NAMES.getOrElse(p.source) { "" } else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = if (warn) BAD else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onSave(pl) }, enabled = pl.pointId > 0) { Text("Uložit") }
                TextButton(onClick = { onMeasure(pl) }, enabled = canMeasure) { Text("Změřit nový (REC)") }
            }
        },
        dismissButton = {
            Row {
                onRemove?.let { TextButton(onClick = it) { Text("Odebrat", color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text("Zrušit") }
            }
        },
    )
}

/** Kóta pásmem: délka v metrech (čárka i tečka). */
@Composable
private fun TapeDialog(initial: Double?, onSave: (Double) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial?.let { String.format(Locale.US, "%.3f", it).trimEnd('0').trimEnd('.').replace('.', ',') } ?: "") }
    val v = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it < 1000 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Kóta pásmem" else "Upravit kótu") },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true, label = { Text("Délka [m]") }, isError = text.isNotEmpty() && v == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = { TextButton(onClick = { v?.let(onSave) }, enabled = v != null) { Text("Uložit") } },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Smazat", color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text("Zrušit") }
            }
        },
    )
}
