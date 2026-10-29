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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.BackHandler
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
import cz.antonin.rovergnss.data.ObjAngle
import cz.antonin.rovergnss.data.ObjRight
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
    // „Vzdálenost“ (dřív Změřit): odměření v náčrtu, neplést s REC (měření GPS)
    val NAMES = listOf("Kreslit", "Body", "Pásmo", "Vzdálenost")
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
    var dragV by remember { mutableStateOf<Map<Int, Offset>?>(null) }    // id vrcholu → poloha v náčrtu během tažení (i otočená stěna)
    var askDelV by remember { mutableStateOf<Int?>(null) }              // id vrcholu ke smazání (s navázanými body)
    var askRecalc by remember { mutableStateOf(false) }                 // potvrzení Přepočítat (náčrt podle výpočtu)
    var stroke by remember { mutableStateOf<List<Offset>>(emptyList()) }  // kreslení prstem (obrazovka)
    var askClear by remember { mutableStateOf(false) }
    var selEdge by remember { mutableStateOf<Int?>(null) }            // vybraná stěna (index)
    var selVertex by remember { mutableStateOf<Int?>(null) }          // vybraný vrchol (index) – pravý úhel, smazání
    var angleMode by remember { mutableStateOf(false) }               // Pásmo: úhel rohu (trojúhelník)
    var angleDialog by remember { mutableStateOf<Int?>(null) }        // index vrcholu pro úhel rohu
    var dragP by remember { mutableStateOf<Pair<Int, Offset>?>(null) } // tažení pomocného bodu po ose stěny (id, náčrt)
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
            drag != null && v.id in drag && !world -> drag.getValue(v.id)
            world -> result.vertices.getOrNull(i)?.let { dOfWorld(it.e, it.n) } ?: dOfSketch(v.x, v.y)
            de != null && (de.first == v.id || de.second == v.id) -> Offset(v.x.toFloat(), v.y.toFloat()) + de.third
            else -> dOfSketch(v.x, v.y)
        }
    }
    if (selEdge != null && selEdge!! >= sk.edges().size) selEdge = null
    /** Změřený bod v náčrtu: s výpočtem z GNSS (≥ 2 body) jeho skutečná poloha převedená do náčrtu, jinak uložená. */
    fun fixedPos(p: ObjPlace): Offset {
        if (result.ok && !result.local) result.places.firstOrNull { it.placeId == p.id }?.let { pr ->
            result.toSketch(pr.e, pr.n)?.let { return Offset(it[0].toFloat(), it[1].toFloat()) }
        }
        return Offset(p.x.toFloat(), p.y.toFloat())
    }
    /** Nezměřený bod na stěně / v ose: stejný poměr podél stěny vůči aktuální (i tažené) poloze vrcholů. */
    fun followPos(p: ObjPlace): Offset? {
        if (p.type != ObjPointType.AXIS && p.type != ObjPointType.WALL) return null
        val (ia, ib) = sk.edges().getOrNull(sk.edgeIndex(p.edge)) ?: return null
        val t = wallParam(sk.vertices[ia], sk.vertices[ib], p.x, p.y) ?: return null
        val a = vPos[ia]
        val b = vPos[ib]
        return Offset(a.x + (b.x - a.x) * t.toFloat(), a.y + (b.y - a.y) * t.toFloat())
    }
    /** Vrchol se změřeným rohem = pevný. */
    fun vertexFixed(i: Int): Boolean {
        val id = sk.vertices.getOrNull(i)?.id ?: return false
        return sk.places.any { it.type == ObjPointType.CORNER && it.vertex == id && it.pointId != 0L }
    }
    /** Změřený bod na stěně nebo v ose (přímka stěny jím musí projít). */
    fun wallAnchor(ei: Int): Offset? {
        val (ia, _) = sk.edges().getOrNull(ei) ?: return null
        val id = sk.vertices[ia].id
        return sk.places.firstOrNull { (it.type == ObjPointType.AXIS || it.type == ObjPointType.WALL) && it.edge == id && it.pointId != 0L }
            ?.let { fixedPos(it) }
    }
    /**
     * Tažení vrcholu i na polohu q (náčrt). Pevné body drží: stěna se změřeným bodem v ose / na stěně se natáčí kolem
     * něj (druhý vrchol si drží vzdálenost od bodu), je-li druhý vrchol pevný, vrchol jede po přímce. Null = vrchol je pevný.
     */
    fun dragVertex(i: Int, q: Offset): Map<Int, Offset>? {
        if (vertexFixed(i)) return null
        val v = sk.vertices[i]
        val es = sk.edges()
        val inc = es.indices.filter { es[it].first == i || es[it].second == i }
        val anch = inc.mapNotNull { ei -> wallAnchor(ei)?.let { ei to it } }
        if (anch.size >= 2) return null
        if (anch.isEmpty()) return mapOf(v.id to q)
        val (ei, a) = anch[0]
        val ui = if (es[ei].first == i) es[ei].second else es[ei].first
        val u = sk.vertices[ui]
        val uo = Offset(u.x.toFloat(), u.y.toFloat())
        if (vertexFixed(ui)) {
            // přímka pevně daná: průmět na přímku u–bod
            val d = a - uo
            val l2 = d.x * d.x + d.y * d.y
            if (l2 < 1e-12f) return mapOf(v.id to q)
            val t = ((q.x - uo.x) * d.x + (q.y - uo.y) * d.y) / l2
            return mapOf(v.id to uo + d * t)
        }
        // otočení stěny kolem bodu: u na stejné straně a ve stejné vzdálenosti od bodu jako dřív
        val vo = Offset(v.x.toFloat(), v.y.toFloat())
        val dv = vo - a
        val lv = dv.getDistance()
        val dq = q - a
        val lq = dq.getDistance()
        if (lv < 1e-6f || lq < 1e-6f) return mapOf(v.id to q)
        val k = ((uo.x - a.x) * dv.x + (uo.y - a.y) * dv.y) / lv   // poloha u podél stěny vůči bodu (ve směru v)
        return mapOf(v.id to q, u.id to a + dq * (k / lq))
    }
    /** Stěnu lze posunout, jen když na ní nic změřeného není. */
    fun edgeMovable(ei: Int): Boolean {
        val (ia, ib) = sk.edges().getOrNull(ei) ?: return false
        return !vertexFixed(ia) && !vertexFixed(ib) && wallAnchor(ei) == null
    }
    fun placePos(p: ObjPlace): Offset {
        dragP?.let { (id, q) -> if (id == p.id && !world) return q }
        if (world) result.places.firstOrNull { it.placeId == p.id }?.let { return dOfWorld(it.e, it.n) }
        if (p.type == ObjPointType.CORNER) sk.vIndex(p.vertex).takeIf { it >= 0 }?.let { return vPos[it] }
        // změřený = pevný (s polohou z GNSS na své skutečné poloze převedené do náčrtu)
        if (p.pointId != 0L) return fixedPos(p)
        // nezměřený na stěně / v ose drží poměr podél své stěny (i během tažení)
        followPos(p)?.let { return it }
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
    LaunchedEffect(fitReq, world, obj?.id, canvasW, canvasH) {
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
        val n = reattach(sk, f(sk))
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

    /** Roh ve vrcholu: umístěný, nebo nový (přiložení podle vypouklosti). */
    fun cornerPlace(i: Int): ObjPlace {
        val v = sk.vertices[i]
        return sk.places.firstOrNull { it.type == ObjPointType.CORNER && it.vertex == v.id }
            ?: ObjPlace(0, 0, ObjPointType.CORNER, vertex = v.id, mount = if (convex(sk, i)) ObjMount.OUTER else ObjMount.INNER, x = v.x, y = v.y)
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
                        if (!sk.closed && h.index == 0 && sk.vertices.size >= 3) {
                            selVertex = null
                            edit { it.copy(closed = true) }
                        } else selVertex = if (selVertex == h.index) null else h.index
                    }
                    is Hit.Edge -> {
                        // výběr stěny: posun tažením, vložit vrchol, smazat
                        selEdge = h.index
                        selEdgePt = q
                        selVertex = null
                    }
                    else -> if (selEdge != null || selVertex != null) {
                        selEdge = null
                        selVertex = null
                    }
                    else if (sk.closed) c.postMessage("Obrys je uzavřený – vrchol vložíš výběrem stěny (klepnutím na ni)")
                    else edit { s0 -> s0.copy(vertices = s0.vertices + ObjVertex(s0.nextId, q.x.toDouble(), q.y.toDouble()), nextId = s0.nextId + 1) }
                }
            }
            ObjMode.POINTS -> {
                val q = toSketch(s)
                when (val h = hitTest(s, wantTapes = false, wantPlaces = true)) {
                    is Hit.Place -> placeDialog = sk.places.firstOrNull { it.id == h.id }
                    is Hit.Vertex -> placeDialog = cornerPlace(h.index)
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
                if (angleMode) {
                    val hv = hitTest(s, wantTapes = false, wantPlaces = false)
                    if (hv is Hit.Vertex) {
                        if (sk.neighbors(hv.index) == null) c.postMessage("Úhel jen ve vrcholu se dvěma stěnami")
                        else angleDialog = hv.index
                    }
                    return
                }
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
                    // mezi dvěma body jen jedna kóta: existující se otevře k přepsání
                    val ex = sk.tapes.lastOrNull { (it.a == f && it.b == r) || (it.a == r && it.b == f) }
                    if (ex != null) tapeEdit = ex else tapeDialog = f to r
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
        // bez zdvojených vrcholů: bod čáry u existujícího vrcholu se vynechá (obrys nejde větvit)
        val n0 = add.size
        add = add.filter { q -> vPos.none { (toScreen(it) - q).getDistance() < hitR * 0.8f } }
        if (add.size < n0) c.postMessage("Vrchol na místě existujícího vynechán – obrys uzavřeš klepnutím na první vrchol")
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
        // dlouhý stisk (každý režim): na bodu / vrcholu = dialog bodu (REC / výběr), na prázdném místě = celé okno / zpět.
        // Nic nemaže – vrchol se maže výběrem (klepnutí) a „Smazat vrchol“.
        val h = hitTest(s, wantTapes = true, wantPlaces = true)
        if (h == null) {
            c.objFull.value = !c.objFull.value
            return
        }
        if (whole) return
        when (h) {
            is Hit.Place -> placeDialog = sk.places.firstOrNull { it.id == h.id }
            is Hit.Vertex -> placeDialog = cornerPlace(h.index)
            else -> {}
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
    val dragFn = rememberUpdatedState({ i: Int, q: Offset -> dragVertex(i, q) })
    val edgeOk = rememberUpdatedState({ ei: Int -> edgeMovable(ei) })
    val msgFn = rememberUpdatedState({ m: String -> c.postMessage(m) })

    val full by c.objFull.collectAsStateWithLifecycle()
    var resOpen by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = full) { c.objFull.value = false }
    DisposableEffect(Unit) { onDispose { c.objFull.value = false } }
    LaunchedEffect(full) { fitReq++ }

    Column(Modifier.fillMaxSize()) {
        // výběr objektu (menu: objekty, nový, přejmenovat, smazat) – v řádku režimů, šetří plochu (0.11.12)
        val objPicker: @Composable () -> Unit = {
            Box {
                TextButton(onClick = { objMenu = true }) { Text("${obj?.name ?: "objekt"} ▾", style = MaterialTheme.typography.titleSmall) }
                DropdownMenu(expanded = objMenu, onDismissRequest = { objMenu = false }) {
                    objects.forEach { o ->
                        DropdownMenuItem(text = { Text(o.name + if (o.id == obj?.id) "  ✓" else "") }, onClick = {
                            objMenu = false
                            selId = o.id
                            fitReq++
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Nový objekt…") }, onClick = {
                        objMenu = false
                        nameText = ""
                        nameDialog = "new"
                    })
                    if (obj != null) {
                        DropdownMenuItem(text = { Text("Přejmenovat…") }, onClick = {
                            objMenu = false
                            nameText = obj.name
                            nameDialog = "rename"
                        })
                        DropdownMenuItem(text = { Text("Smazat objekt…", color = MaterialTheme.colorScheme.error) }, onClick = {
                            objMenu = false
                            askDeleteObj = true
                        })
                    }
                }
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
                if (!full) objPicker()
                ObjMode.NAMES.forEachIndexed { i, n ->
                    FilterChip(selected = mode == i, onClick = {
                        mode = i
                        tapeFirst = null
                        measSel = emptyList()
                        fitReq++
                    }, label = { Text(n) })
                }
                TextButton(onClick = { fitReq++ }) { Text("Celé") }
                TextButton(onClick = { c.objFull.value = !full }) { Text(if (full) "Zmenšit" else "Celé okno") }
                TextButton(onClick = { help = !help }) { Text(if (help) "? ✕" else "?") }
            }
            // návod jen na požádání (?)
            if (!full && help) Text(
                when (mode) {
                    ObjMode.DRAW -> when {
                        whole -> "Celý náčrt: táhni = posun, dvěma prsty = otočení a velikost. Pohled se nemění."
                        selEdge != null -> "Stěna vybrána: táhni ji = posun, nebo Vložit vrchol / Pomocný bod (P v ose stěny) / Smazat stěnu."
                        selVertex != null -> "Vrchol vybrán: pravý úhel auto / ano / ne, nebo smazat (s navázanými body po potvrzení)."
                        else -> "Táhni prstem = čára obrysu, klepni = vrchol, na vrchol / stěnu = výběr, na první vrchol = uzavřít. " +
                            "Vrchol táhni = posun, podrž = bod rohu (REC). Bod P táhni po ose, podrž = změřit / vybrat bod. Změřené body jsou pevné. Posun a zoom dvěma prsty. " +
                            "Pravoúhlá = rohy kolem 90° (±15°) se počítají jako pravé, Přepočítat = náčrt převezme vypočtený tvar."
                    }
                    ObjMode.POINTS -> "Klepni na roh, na stěnu (dveře), na P, nebo mimo obrys (bod v ose stěny / kontrolní): REC změří, nebo vyber změřený bod."
                    ObjMode.TAPE -> if (angleMode) "Klepni na roh, jehož úhel jsi změřil pásmem (a, b od rohu, c mezi značkami)."
                    else if (tapeFirst == null) "Klepni na první bod kóty (vrchol nebo bod), pak na druhý. Mezi dvěma body jedna kóta – znovu = přepsat. Klepnutí na číslo kóty = úprava."
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
                FilterChip(selected = sk.square, onClick = { edit { it.copy(square = !it.square) } },
                    label = { Text("Pravoúhlá") }, enabled = sk.vertices.size >= 3)
                // Přepočítat (dřív Narovnat): náčrt převezme vypočtený tvar; při rozporu zakázáno (náčrt by převzal chybu)
                OutlinedButton(onClick = { askRecalc = true },
                    enabled = result.ok && !result.conflict && result.vertices.size == sk.vertices.size) { Text("Přepočítat") }
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
                            places = s0.places.map { if (it.pointId != 0L) it else it.copy(x = 2 * cx - it.x) },
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
            val sv = selVertex?.takeIf { it < sk.vertices.size }
            if (mode == ObjMode.DRAW && sv != null && !whole) Row(
                Modifier.padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val vid = sk.vertices[sv].id
                Text("V${sv + 1} pravý úhel:", style = MaterialTheme.typography.titleSmall)
                ObjRight.NAMES.forEachIndexed { k, n ->
                    FilterChip(selected = (sk.right[vid] ?: ObjRight.AUTO) == k, onClick = {
                        edit { s0 -> s0.copy(right = if (k == ObjRight.AUTO) s0.right - vid else s0.right + (vid to k)) }
                    }, label = { Text(n + if (k == ObjRight.AUTO) (if (sk.isRight(sv) && (sk.right[vid] ?: 0) == 0) " (90°)" else " (volný)") else "") },
                        enabled = sk.neighbors(sv) != null)
                }
                OutlinedButton(onClick = {
                    if (vertexDeps(sk, vid).first.isEmpty() && vertexDeps(sk, vid).second == 0) {
                        edit { s0 -> removeVertex(s0, vid) }
                        selVertex = null
                    } else askDelV = vid
                }) { Text("Smazat vrchol", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { selVertex = null }) { Text("Zrušit výběr") }
            }
            if (mode == ObjMode.TAPE) Row(
                Modifier.padding(horizontal = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(selected = !angleMode, onClick = { angleMode = false }, label = { Text("Délka") })
                FilterChip(selected = angleMode, onClick = {
                    angleMode = true
                    tapeFirst = null
                }, label = { Text("Úhel rohu pásmem") })
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
                    // pomocný bod P na prodloužení stěny za bližší konec (5 m; bez měřítka 30 % délky stěny), pak táhnout po ose
                    val q = selEdgePt
                    edit { s0 ->
                        val (a, b) = s0.edges().getOrNull(se) ?: return@edit s0
                        val va = s0.vertices[a]
                        val vb = s0.vertices[b]
                        val len = hypot(vb.x - va.x, vb.y - va.y).takeIf { it > 1e-9 } ?: return@edit s0
                        val nearA = q == null || hypot(q.x - va.x, q.y - va.y) <= hypot(q.x - vb.x, q.y - vb.y)
                        val sc = result.scale?.takeIf { result.ok && result.scaleKnown && it > 0 }
                        val dist = if (sc != null) 5.0 / sc else 0.3 * len
                        val ux = (vb.x - va.x) / len
                        val uy = (vb.y - va.y) / len
                        val px = if (nearA) va.x - ux * dist else vb.x + ux * dist
                        val py = if (nearA) va.y - uy * dist else vb.y + uy * dist
                        val pNum = Regex("^P(\\d+)$")
                        val used = s0.places.mapNotNull { pNum.find(it.label)?.groupValues?.get(1)?.toIntOrNull() } +
                            points.mapNotNull { pNum.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
                        s0.copy(
                            places = s0.places + ObjPlace(s0.nextId, 0, ObjPointType.AXIS, edge = va.id, mount = ObjMount.FREE,
                                x = px, y = py, label = "P${(used.maxOrNull() ?: 0) + 1}"),
                            nextId = s0.nextId + 1,
                        )
                    }
                    selEdge = null
                }) { Text("Pomocný bod") }
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
                            // bod v ose (i pomocný P): tažení jen po přímce stěny
                            val ed = md == ObjMode.DRAW && oid != null && !wh
                            // změřené body jsou pevné: tažení za ně (nebo za pevný vrchol / stěnu) jen posune pohled
                            var blocked: String? = null
                            val hp = if (ed) (cb.value.third(start, false, true) as? Hit.Place)?.let { h -> sk0.places.firstOrNull { it.id == h.id } } else null
                            val pGrab = hp?.takeIf { it.type == ObjPointType.AXIS && it.pointId == 0L }?.id
                            if (hp != null && hp.pointId != 0L && hp.type != ObjPointType.CORNER) blocked = "Změřený bod je pevný"
                            val vh = if (ed && pGrab == null && blocked == null) (cb.value.third(start, false, false) as? Hit.Vertex)?.index
                                ?.takeIf { sk0.closed || it != sk0.vertices.lastIndex } else null
                            val grab = vh?.takeIf { dragFn.value(it, Offset.Zero) != null }
                            if (vh != null && grab == null) blocked = "V${vh + 1} je pevný (změřený roh, nebo daný změřenými body v ose)"
                            // Kreslit: tažení jedním prstem mimo vrchol = čára obrysu (posun mapy dvěma prsty)
                            // vybraná stěna: tažení = posun stěny (oba vrcholy), jen bez změřených bodů
                            val eh = if (ed && grab == null && blocked == null && sel != null)
                                (cb.value.third(start, false, false) as? Hit.Edge)?.index?.takeIf { it == sel } else null
                            val edgeGrab = eh?.takeIf { edgeOk.value(it) }
                            if (eh != null && edgeGrab == null) blocked = "Stěnu se změřeným bodem nejde posunout – táhni její vrchol (natočí se kolem bodu)"
                            val wholeDrag = md == ObjMode.DRAW && oid != null && wh
                            val drawing = ed && grab == null && edgeGrab == null && pGrab == null && blocked == null
                            var blockedShown = false
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
                                    if (pGrab != null) {
                                        val d = dragP
                                        if (d != null && moved) editRef.value { s0 ->
                                            s0.copy(places = s0.places.map { if (it.id == d.first) it.copy(x = d.second.x.toDouble(), y = d.second.y.toDouble()) else it })
                                        }
                                        dragP = null
                                        if (!moved && !long) cb.value.first(start)
                                    } else if (wholeDrag) {
                                        val t = xf
                                        if (t != null && oid != null) editRef.value { s0 ->
                                            s0.copy(
                                                vertices = s0.vertices.map { v -> t.apply(Offset(v.x.toFloat(), v.y.toFloat()), cen).let { v.copy(x = it.x.toDouble(), y = it.y.toDouble()) } },
                                                places = s0.places.map { p ->
                                                    if (p.pointId != 0L) p   // změřené zůstávají
                                                    else t.apply(Offset(p.x.toFloat(), p.y.toFloat()), cen).let { p.copy(x = it.x.toDouble(), y = it.y.toDouble()) }
                                                },
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
                                    } else if (drawing && moved && !multi && !long) {
                                        strokeCb.value(stroke)
                                        stroke = emptyList()
                                    } else if (grab != null && moved) {
                                        val v = dragV
                                        if (v != null && oid != null) editRef.value { s0 ->
                                            s0.copy(vertices = s0.vertices.map { w -> v[w.id]?.let { w.copy(x = it.x.toDouble(), y = it.y.toDouble()) } ?: w })
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
                                        grab != null -> if (!long) dragFn.value(grab, toD(ch.position))?.let { dragV = it }
                                        blocked != null -> {
                                            if (!blockedShown) msgFn.value(blocked!!)
                                            blockedShown = true
                                            vOff += ch.position - last
                                        }
                                        pGrab != null && !long -> {
                                            val p = sk0.places.firstOrNull { it.id == pGrab }
                                            val (ia, ib) = sk0.edges().getOrNull(sk0.edgeIndex(p?.edge)) ?: (-1 to -1)
                                            val va = sk0.vertices.getOrNull(ia)
                                            val vb = sk0.vertices.getOrNull(ib)
                                            if (p != null && va != null && vb != null) {
                                                val q = toD(ch.position)
                                                val dx = (vb.x - va.x).toFloat()
                                                val dy = (vb.y - va.y).toFloat()
                                                val l2 = dx * dx + dy * dy
                                                if (l2 > 1e-9f) {
                                                    val t = ((q.x - va.x.toFloat()) * dx + (q.y - va.y.toFloat()) * dy) / l2
                                                    dragP = p.id to Offset(va.x.toFloat() + t * dx, va.y.toFloat() + t * dy)
                                                }
                                            }
                                        }
                                        pGrab != null -> {}
                                        wholeDrag -> xf = (xf ?: SkXf()).let { it.copy(t = it.t + (ch.position - last) / vScale) }
                                        edgeGrab != null -> if (!long) {
                                            val (ia, ib) = sk0.edges().getOrNull(edgeGrab) ?: (0 to 0)
                                            val a = sk0.vertices.getOrNull(ia)?.id
                                            val b = sk0.vertices.getOrNull(ib)?.id
                                            if (a != null && b != null) dragEdge = Triple(a, b, toD(ch.position) - toD(start))
                                        }
                                        drawing && !long -> stroke = if (stroke.isEmpty()) listOf(start, ch.position) else stroke + ch.position
                                        drawing -> {}
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
                        names = obs.mapValues { it.value.name }, selEdge = selEdge.takeIf { mode == ObjMode.DRAW }, selVertex = selVertex.takeIf { mode == ObjMode.DRAW },
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
                if (result.ok && (result.conflict || result.excludedText != null)) Text(
                    result.message.substringAfter("Vypočteno: ").let { if (result.excludedText != null) "Hrubá chyba: ${result.excludedText} – vynechána" else it },
                    style = MaterialTheme.typography.bodySmall, color = BAD,
                    modifier = Modifier.align(Alignment.TopCenter).padding(6.dp)
                        .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(6.dp)).padding(6.dp),
                )
                if (mode != ObjMode.DRAW && !result.ok) Text(
                    result.message, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.TopCenter).padding(6.dp)
                        .background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(6.dp),
                )
            }
            // výsledky (sbalitelné; v celém okně jen měření mezi dvěma body)
            if (full) {
                if (mode == ObjMode.MEASURE && measSel.size == 2) Box(Modifier.padding(horizontal = 8.dp)) { MeasureCard(sk, result, measSel[0], measSel[1]) }
            } else {
                Row(
                    Modifier.fillMaxWidth().clickable { resOpen = !resOpen }.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (resOpen) "▾ " else "▸ ", style = MaterialTheme.typography.titleSmall)
                    Text("Výsledky: " + result.message, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                }
                if (mode == ObjMode.MEASURE && measSel.size == 2) Box(Modifier.padding(horizontal = 8.dp)) { MeasureCard(sk, result, measSel[0], measSel[1]) }
                if (resOpen) Column(
                    Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ResultPanel(c, obj, sk, result, lastMeas, job?.name ?: "lokalita")
                }
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
    if (askRecalc && obj != null) AlertDialog(
        onDismissRequest = { askRecalc = false },
        title = { Text("Přepočítat náčrt?") },
        text = {
            Text("Náčrt převezme tvar z výpočtu (kóty, pravé úhly, změřené body). Změřené body se posunou na svou polohu, " +
                "body na stěnách zůstanou na své stěně. Zpět vrátí původní náčrt.")
        },
        confirmButton = {
            TextButton(onClick = {
                askRecalc = false
                edit { s0 -> straighten(s0, result) }
            }) { Text("Přepočítat") }
        },
        dismissButton = { TextButton(onClick = { askRecalc = false }) { Text("Zrušit") } },
    )
    askDelV?.let { vid ->
        val vi = sk.vIndex(vid)
        val (pls, nt) = vertexDeps(sk, vid)
        val what = pls.map { p -> obs[p.id]?.name ?: p.label.ifEmpty { ObjPointType.NAMES.getOrElse(p.type) { "bod" } } } +
            (if (nt > 0) listOf("$nt ${if (nt == 1) "kóta" else if (nt < 5) "kóty" else "kót"}") else emptyList())
        AlertDialog(
            onDismissRequest = { askDelV = null },
            title = { Text("Smazat V${vi + 1}?") },
            text = { Text("Smaže se i: ${what.joinToString(", ")}. Měření bodů v lokalitě zůstávají. Zpět vrátí.") },
            confirmButton = {
                TextButton(onClick = {
                    askDelV = null
                    selVertex = null
                    edit { s0 -> removeVertex(s0, vid) }
                }) { Text("Smazat", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { askDelV = null }) { Text("Zrušit") } },
        )
    }
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
                    // název: pomocný bod svůj (P1…; je-li v lokalitě obsazený, další volné P), jinak další B
                    val name = if (np.label.isNotEmpty()) {
                        val names = c.db.points().listByJob(j.id).map { it.name }.toSet()
                        if (np.label !in names) np.label else {
                            val used = names.mapNotNull { Regex("^P(\\d+)$").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                            "P${(used.maxOrNull() ?: 0) + 1}"
                        }
                    } else c.nextPointName(j.id)
                    // id nového umístění se přidělí hned; připravené (P) zůstává a REC doplní změřený bod
                    var newId = 0
                    if (np.id == 0) c.updateObject(o.id) { s0 -> newId = s0.nextId; s0.copy(nextId = s0.nextId + 1) }
                    val base = (if (np.id == 0) np.copy(id = newId) else np).let { if (it.label.isNotEmpty()) it.copy(label = name) else it }
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
    angleDialog?.let { vi ->
        val v = sk.vertices.getOrNull(vi)
        if (v != null) AngleDialog(
            name = result.vertices.getOrNull(vi)?.name ?: "V${vi + 1}",
            initial = sk.angles.lastOrNull { it.vertex == v.id },
            defaultMethod = if (convex(sk, vi)) 1 else 0,
            onSave = { m, a, b, cc ->
                angleDialog = null
                edit { s0 -> s0.copy(angles = s0.angles.filter { it.vertex != v.id } + ObjAngle(s0.nextId, v.id, m, a, b, cc), nextId = s0.nextId + 1) }
            },
            onDelete = if (sk.angles.any { it.vertex == v.id }) ({
                angleDialog = null
                edit { s0 -> s0.copy(angles = s0.angles.filter { it.vertex != v.id }) }
            }) else null,
            onDismiss = { angleDialog = null },
        )
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

private val AXIS_COLORS = listOf(
    Color(0xFF1565C0), Color(0xFFD81B60), Color(0xFF00897B), Color(0xFFF4511E), Color(0xFF5E35B1), Color(0xFF7CB342),
)

/** Barvy stěn s body v ose: index stěny → barva (pořadí stěn). */
private fun axisColors(sk: ObjSketch): Map<Int, Color> =
    sk.places.filter { it.type == ObjPointType.AXIS }.map { sk.edgeIndex(it.edge) }.filter { it >= 0 }.distinct().sorted()
        .withIndex().associate { (k, ei) -> ei to AXIS_COLORS[k % AXIS_COLORS.size] }

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
    names: Map<Int, String> = emptyMap(), selEdge: Int? = null, selVertex: Int? = null,
) {
    val sw = 2.5f * dens
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f * dens, 6f * dens))
    // stěny
    // těžiště obrysu na obrazovce: vypočtené délky dovnitř, kóty ven
    val scrV = vPos.map(toScreen)
    val cen = if (scrV.isEmpty()) Offset.Zero else Offset(scrV.map { it.x }.average().toFloat(), scrV.map { it.y }.average().toFloat())
    edges.forEachIndexed { i, (ia, ib) ->
        val a = toScreen(vPos[ia])
        val b = toScreen(vPos[ib])
        val er = if (world) r.edges.getOrNull(i) else null
        val col = if (er != null) SRC_COLORS[er.source] else Color(0xFF424242)
        drawLine(col, a, b, sw, pathEffect = if (er != null && er.source == ObjSource.SKETCH) dash else null)
        // vypočtená délka jen u stěny bez kóty (s kótou je vidět kóta a rozdíl)
        if (er != null && er.tape == null) {
            val m = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
            val d = b - a
            val ld = max(d.getDistance(), 1e-3f)
            var n = Offset(-d.y / ld, d.x / ld)
            if (scrV.size >= 3 && ((m + n) - cen).getDistance() > ((m - n) - cen).getDistance()) n = -n
            val q = m + n * (16f * dens)
            label(String.format(Locale.US, "%.2f", er.len).replace('.', ','), q.x, q.y, col, dens, center = true)
        }
    }
    // prodloužení stěn s body v ose: čárkovaná přímka přes oba vrcholy až za nejvzdálenější bod, barva podle stěny
    val axisCol = axisColors(sk)
    axisCol.forEach { (ei, col) ->
        val (ia, ib) = edges.getOrNull(ei) ?: return@forEach
        val a = toScreen(vPos[ia])
        val b = toScreen(vPos[ib])
        val d = b - a
        val l2 = d.x * d.x + d.y * d.y
        if (l2 < 1f) return@forEach
        var t0 = 0f
        var t1 = 1f
        sk.places.filter { it.type == ObjPointType.AXIS && sk.edgeIndex(it.edge) == ei }.forEach { p ->
            val pp = toScreen(placePos(p))
            val t = ((pp.x - a.x) * d.x + (pp.y - a.y) * d.y) / l2
            t0 = min(t0, t)
            t1 = max(t1, t)
        }
        val ext = 24f * dens / kotlin.math.sqrt(l2)
        val hl = selEdge == ei
        drawLine(
            col.copy(alpha = if (hl) 1f else 0.7f), a + d * (t0 - ext), a + d * (t1 + ext), (if (hl) 3f else 1.8f) * dens,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * dens, 5f * dens)),
        )
    }
    // kóty
    sk.tapes.forEach { t ->
        val a = refD(t.a)?.let(toScreen) ?: return@forEach
        val b = refD(t.b)?.let(toScreen) ?: return@forEach
        val tr = r.tapes.firstOrNull { it.id == t.id }
        val col = if (tr?.bad == true) BAD else Color(0xFF8E24AA)
        val tapeDash = PathEffect.dashPathEffect(floatArrayOf(4f * dens, 4f * dens))
        // kóta po stěně: odsazená ven od obrysu (s vynášecími čarami), aby nesplývala se stěnou
        val ia = ObjCalc.refVertex(sk, t.a)
        val ib = ObjCalc.refVertex(sk, t.b)
        val onEdge = ia >= 0 && ib >= 0 && edges.any { (x, y) -> (x == ia && y == ib) || (x == ib && y == ia) }
        val d = b - a
        val ld = d.getDistance()
        if (onEdge && ld > 1f) {
            var n = Offset(-d.y / ld, d.x / ld)
            val m0 = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
            if (scrV.size >= 3 && ((m0 + n) - cen).getDistance() < ((m0 - n) - cen).getDistance()) n = -n
            val off = 14f * dens
            drawLine(col.copy(alpha = 0.6f), a + n * (3f * dens), a + n * (off + 4f * dens), 1f * dens)
            drawLine(col.copy(alpha = 0.6f), b + n * (3f * dens), b + n * (off + 4f * dens), 1f * dens)
            drawLine(col, a + n * off, b + n * off, 1.5f * dens, pathEffect = tapeDash)
            // text ven od kóty: u svislé stěny zarovnaný od čáry (nepřekrývá stěnu), u vodorovné nad / pod
            val txt = tapeText(t.len, tr?.diff, tr?.excluded == true)
            if (kotlin.math.abs(n.x) > 0.7f) {
                val lm = m0 + n * (off + 5f * dens)
                label(txt, lm.x, lm.y, col, dens, align = if (n.x > 0) 0 else 2)
            } else {
                val lm = m0 + n * (off + 10f * dens)
                label(txt, lm.x, lm.y, col, dens, center = true)
            }
        } else {
            drawLine(col, a, b, 1.5f * dens, pathEffect = tapeDash)
            val m = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
            label(tapeText(t.len, tr?.diff, tr?.excluded == true), m.x, m.y + 14 * dens, col, dens, center = true)
        }
    }
    // vrcholy
    vPos.forEachIndexed { i, p ->
        val s = toScreen(p)
        val vr = if (world) r.vertices.getOrNull(i) else null
        val col = vr?.let { SRC_COLORS[it.source] } ?: Color(0xFF424242)
        val sel = sk.vertices.getOrNull(i)?.let { v -> tapeFirst == "v:${v.id}" || "v:${v.id}" in measSel } == true || selVertex == i
        drawCircle(col, radius = (if (sel) 9f else 6f) * dens, center = s)
        if (sel) drawCircle(Color(0xFFFFA000), radius = 12f * dens, center = s, style = Stroke(2.5f * dens))
        // osa úhlu rohu: jméno vrcholu ven, úhel dovnitř (mimo stěny a kóty)
        val nbv = sk.neighbors(i)
        val bi = nbv?.let {
            val u1 = (toScreen(vPos[it.first]) - s).let { o -> o / max(o.getDistance(), 1e-3f) }
            val u2 = (toScreen(vPos[it.second]) - s).let { o -> o / max(o.getDistance(), 1e-3f) }
            (u1 + u2).let { o -> if (o.getDistance() < 0.05f) Offset(-u1.y, u1.x) else o / o.getDistance() }
        }
        if (bi != null) (s - bi * (16f * dens)).let { label(vr?.name ?: "V${i + 1}", it.x, it.y, labelColor, dens, center = true) }
        else label(vr?.name ?: "V${i + 1}", s.x + 8 * dens, s.y - 8 * dens, labelColor, dens)
        // úhel: ∟ předpoklad 90°, ∠ pásmem; ve skutečných souřadnicích vypočtený úhel mezi stěnami
        val tapeAng = sk.vertices.getOrNull(i)?.let { v -> sk.angles.lastOrNull { it.vertex == v.id } }
        val fix = if (tapeAng != null) 2 else if (sk.isRight(i)) 1 else 0
        // vypočtený úhel i v Kreslit (rozpor červeně); bez výpočtu v závorce úhel, jak je nakreslený
        val rc = if (r.ok) r.vertices.getOrNull(i) else null
        if (fix != 0 || (vr?.wallAngle != null && world)) {
            val txt = (if (fix == 2) "∠" else if (fix == 1) "∟" else "") + when {
                rc?.angleExcluded == true -> String.format(Locale.US, "%.1f° vynechán", rc.wallAngle ?: 0.0).replace('.', ',')
                rc?.wallAngle != null -> String.format(Locale.US, "%.1f°", rc.wallAngle).replace('.', ',')
                fix == 2 -> tapeAng?.wallAngle?.let { String.format(Locale.US, "%.1f°", it).replace('.', ',') } ?: ""
                else -> sk.sketchAngle(i)?.let { String.format(Locale.US, " (%.1f°)", it).replace('.', ',') } ?: ""
            }
            val ac = if (rc?.angleBad == true) BAD else if (fix == 2) Color(0xFF8E24AA) else if (fix == 1) Color(0xFF00897B) else labelColor
            // dovnitř rohu (osa úhlu mezi stěnami), mimo čáry stěn a kót
            val q = if (bi != null) s + bi * (30f * dens) else Offset(s.x + 30f * dens, s.y + 10f * dens)
            label(txt, q.x, q.y, ac, dens, center = true)
        }
    }
    // umístěné body
    sk.places.forEach { p ->
        val s = toScreen(placePos(p))
        val pr = r.places.firstOrNull { it.placeId == p.id }
        val wallCol = if (p.type == ObjPointType.AXIS) axisCol[sk.edgeIndex(p.edge)] else null
        val col = when {
            pr == null && p.pointId == 0L -> Color(0xFF9E9E9E)
            pr?.bad == true -> BAD
            pr?.suspect == true -> Color(0xFFEF6C00)
            wallCol != null -> wallCol
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
        val hlAxis = p.type == ObjPointType.AXIS && selEdge != null && sk.edgeIndex(p.edge) == selEdge
        if (sel || hlAxis) drawCircle(if (hlAxis && !sel) (wallCol ?: Color(0xFFFFA000)) else Color(0xFFFFA000), radius = 13f * dens,
            center = s, style = Stroke(2.5f * dens))
        val nm = pr?.name ?: names[p.id] ?: if (p.pointId == 0L) (if (p.label.isNotEmpty()) "${p.label} ?" else "?") else ""
        // na ploše krátce (barva osy ukazuje stěnu); celý popisek u vybrané stěny / bodu nebo chybného bodu
        val full = sel || hlAxis || pr?.bad == true
        val txt = when (p.type) {
            ObjPointType.AXIS -> if (!full) nm.replace(" ?", "?") else {
                val ei = sk.edgeIndex(p.edge)
                val en = edges.getOrNull(ei)?.let { (a, b) -> "V${a + 1}–V${b + 1}" } ?: "?"
                "$nm ⟂ $en" + (pr?.residual?.let { " · " + String.format(Locale.US, "%.1f cm", it * 100).replace('.', ',') } ?: "")
            }
            else -> nm
        }
        // roh: název bodu (B…) pod kroužkem, vrchol má své V…
        label(txt, s.x + 9 * dens, s.y + (if (p.type == ObjPointType.CORNER) 26f else 14f) * dens, col, dens)
    }
    // měření mezi dvěma body
    if (mode == ObjMode.MEASURE && measSel.size == 2) {
        val a = refD(measSel[0])?.let(toScreen)
        val b = refD(measSel[1])?.let(toScreen)
        if (a != null && b != null) drawLine(Color(0xFFFFA000), a, b, 2f * dens)
    }
}

/** Popisek: [center] na střed x i y; [align] 0 od x doprava, 2 do x zleva (y na střed písma). */
private fun DrawScope.label(s: String, x: Float, y: Float, color: Color, dens: Float, center: Boolean = false, align: Int = -1) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = 12f * dens
        this.color = android.graphics.Color.argb(255, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt())
        textAlign = when {
            center -> android.graphics.Paint.Align.CENTER
            align == 2 -> android.graphics.Paint.Align.RIGHT
            else -> android.graphics.Paint.Align.LEFT
        }
    }
    val mid = center || align >= 0
    drawContext.canvas.nativeCanvas.drawText(s, x, if (mid) y + paint.textSize * 0.35f else y, paint)
}

/** Znaménkové číslo bez „-0,0“ (desetinná čárka). */
private fun signed(v: Double, dec: Int): String {
    val f = Math.pow(10.0, dec.toDouble())
    val x = Math.round(v * f) / f
    return if (x == 0.0) String.format(Locale.US, "%.${dec}f", 0.0).replace('.', ',')
    else String.format(Locale.US, "%+.${dec}f", x).replace('.', ',')
}

/** Popisek kóty: délka a rozdíl od výpočtu v cm. */
private fun tapeText(len: Double, diff: Double?, excluded: Boolean = false): String =
    "⟷" + String.format(Locale.US, "%.2f", len).replace('.', ',') +
        if (excluded) " vynechána" else (diff?.let { " (" + signed(it * 100, 1) + ")" } ?: "")

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
                String.format(Locale.US, "%-6s %11.3f %12.3f ±%4.1f cm %s", v.label, v.y, v.x, (v.sigma ?: 0.0) * 100,
                    v.angle?.let { String.format(Locale.US, "%6.1f°", it) } ?: "") + "  " + ObjSource.NAMES[v.source] +
                    (v.dh?.let { "  Δh " + signed(it, 2) } ?: ""),
                fontFamily = FontFamily.Monospace, style = mono, color = SRC_COLORS[v.source],
            )
        }
        if (r.vertices.any { it.wallAngle != null }) {
            Text("Úhly mezi stěnami (∟ předpoklad 90°, ∠ pásmem)", style = MaterialTheme.typography.titleSmall)
            r.vertices.filter { it.wallAngle != null }.forEach { v ->
                Text(
                    String.format(Locale.US, "%-6s %6.2f°", v.name, v.wallAngle) +
                        when (v.angleFix) {
                            1 -> "  ∟ předpoklad, odchylka " + signed(v.wallAngle!! - 90, 2) + "°"
                            2 -> String.format(Locale.US, "  ∠ pásmem %.2f°, rozdíl ", v.angleObs) + signed(v.wallAngle!! - (v.angleObs ?: 0.0), 2) + "°"
                            else -> "  z měření"
                        } + if (v.angleBad) "  – měření si odporují" else "",
                    fontFamily = FontFamily.Monospace, style = mono, color = if (v.angleBad) BAD else MaterialTheme.colorScheme.onSurface,
                )
            }
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
                    (p.dh?.let { " · Δh " + signed(it, 2) + " m" } ?: "") +
                    (if (p.suspect) " · podezřelý / bez fixu" else "") + (if (p.excluded) " · VYNECHÁN (hrubá chyba)" else if (p.bad) " · CHYBNÝ" else ""),
                style = mono, color = if (p.bad) BAD else if (p.suspect) Color(0xFFEF6C00) else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    if (r.tapes.isNotEmpty()) {
        Text("Kóty (zadaná − vypočtená)", style = MaterialTheme.typography.titleSmall)
        r.tapes.forEach { t ->
            Text(
                String.format(Locale.US, "%.3f m", t.len) + (t.calc?.let { String.format(Locale.US, " / %.3f m", it) } ?: "") +
                    (t.diff?.let { " · rozdíl ${PointEvaluator.cm(it)} cm" } ?: "") +
                    if (t.excluded) " · VYNECHÁNA (hrubá chyba)" else if (t.bad) " · CHYBNÁ" else "",
                style = mono, color = if (t.bad) BAD else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    HorizontalDivider()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = {
            pendingSrs = Gpkg.SRS_5514
            gpkgLauncher.launch(FileNames.job(jobName, obj.name, System.currentTimeMillis(), "gpkg"))
        }, enabled = !r.local) { Text("GeoPackage 5514") }
        OutlinedButton(onClick = {
            pendingSrs = Gpkg.SRS_25833
            gpkgLauncher.launch(FileNames.job(jobName, obj.name + "_utm33", System.currentTimeMillis(), "gpkg"))
        }, enabled = !r.local) { Text("GeoPackage UTM 33N") }
        OutlinedButton(onClick = {
            csvLauncher.launch(FileNames.job(jobName, obj.name + "_vrcholy", System.currentTimeMillis(), "csv"))
        }, enabled = r.vertices.isNotEmpty() && !r.local) { Text("Vrcholy CSV") }
    }
    if (r.local) Text("Export až s polohou: umísti aspoň 2 změřené body (režim Body). Místní výpočet je jen pro tvar a rozměry.",
        style = MaterialTheme.typography.bodySmall, color = BAD)
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
                    (if (ha != null && hb != null) " · Δh " + signed(hb - ha, 2) + " m" else ""),
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
        title = {
            Text(
                if (initial.label.isNotEmpty() && initial.pointId == 0L) "Pomocný bod ${initial.label}"
                else if (initial.id == 0) "Umístit bod" else "Bod v náčrtu"
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // REC nahoře: změřit tento bod hned (pomocný P pod svým jménem, ostatní jako další B)
                Button(onClick = { onMeasure(pl) }, enabled = canMeasure, modifier = Modifier.fillMaxWidth()) {
                    Text(if (initial.label.isNotEmpty()) "Změřit ${initial.label} (REC)" else "REC – změřit nový bod")
                }
                if (!canMeasure) Text("REC: připoj rover a dokonči běžící měření.", style = MaterialTheme.typography.bodySmall)
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
                Text("… nebo vyber změřený bod (nejnovější nahoře)", style = MaterialTheme.typography.titleSmall)
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
                // pomocný bod lze uložit i nezměřený (připravený v náčrtu)
                TextButton(onClick = { onSave(pl) }, enabled = pl.pointId > 0 || (initial.label.isNotEmpty() && initial.id != 0)) { Text("Uložit") }
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

/**
 * Úhel rohu pásmem (trojúhelník): a, b od rohu, c mezi konci; způsob podél obou stěn / jedna stěna prodloužená.
 * Ukazuje změřený úhel a úhel mezi stěnami.
 */
@Composable
private fun AngleDialog(
    name: String, initial: ObjAngle?, defaultMethod: Int,
    onSave: (Int, Double, Double, Double) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit,
) {
    fun t(v: Double?) = v?.let { String.format(Locale.US, "%.3f", it).trimEnd('0').trimEnd('.').replace('.', ',') } ?: ""
    var method by remember { mutableIntStateOf(initial?.method ?: defaultMethod) }
    var ta by remember { mutableStateOf(t(initial?.a)) }
    var tb by remember { mutableStateOf(t(initial?.b)) }
    var tc by remember { mutableStateOf(t(initial?.c)) }
    fun num(s: String) = s.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it < 100 }
    val a = num(ta)
    val b = num(tb)
    val cc = num(tc)
    val ang = if (a != null && b != null && cc != null) ObjAngle(0, 0, method, a, b, cc) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Úhel rohu $name pásmem") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = method == 1, onClick = { method = 1 }, label = { Text("vnější roh: 1 stěna prodloužená") })
                    FilterChip(selected = method == 0, onClick = { method = 0 }, label = { Text("podél obou stěn") })
                }
                Text(
                    "Nepovinné – jen když roh není pravý a chceš jeho úhel. Od rohu odměř a, udělej značku, po druhé stěně b, " +
                        "druhá značka, pak změř c mezi značkami. " +
                        if (method == 1) "Vnější roh: a po prodloužení jedné stěny za roh." else "a, b podél obou stěn (vnitřní roh, uvnitř budovy).",
                    style = MaterialTheme.typography.bodySmall,
                )
                AngleSketch(method)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(ta, { ta = it }, singleLine = true, label = { Text("a [m]") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(tb, { tb = it }, singleLine = true, label = { Text("b [m]") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(tc, { tc = it }, singleLine = true, label = { Text("c [m]") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Text(
                    when {
                        ang == null -> "Zadej a, b, c (např. a 3, b 4: u pravého úhlu c 5,00)."
                        ang.measured == null -> "Délky netvoří trojúhelník."
                        else -> String.format(Locale.US, "změřený úhel %.2f° → úhel mezi stěnami %.2f° (±%.1f°)",
                            ang.measured, ang.wallAngle, ObjCalc.angleSigma(ang)).replace('.', ',')
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (a != null && b != null && cc != null) onSave(method, a, b, cc) }, enabled = ang?.measured != null) { Text("Uložit") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Smazat", color = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = onDismiss) { Text("Zrušit") }
            }
        },
    )
}

/** Obrázek k úhlu rohu pásmem: stěny, značky a, b a spojnice c. [method] 1 = vnější roh (jedna stěna prodloužená). */
@Composable
private fun AngleSketch(method: Int) {
    val wallC = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val dn = density
        val k = Offset(size.width * 0.45f, size.height * 0.82f)           // roh
        val up = Offset(k.x - size.height * 0.12f, size.height * 0.08f)    // konec stěny 2 (mírně šikmo – obecný úhel)
        val sw = 3f * dn
        // stěna 1 vodorovně doleva od rohu, stěna 2 nahoru
        drawLine(wallC, Offset(size.width * 0.05f, k.y), k, sw)
        drawLine(wallC, k, up, sw)
        val purple = Color(0xFF8E24AA)
        val dash = PathEffect.dashPathEffect(floatArrayOf(5f * dn, 4f * dn))
        val pa = if (method == 1) {
            // prodloužení stěny 1 za roh doprava
            drawLine(wallC.copy(alpha = 0.5f), k, Offset(size.width * 0.85f, k.y), 1.5f * dn, pathEffect = dash)
            Offset(size.width * 0.75f, k.y)
        } else Offset(size.width * 0.15f, k.y)
        val pb = k + (up - k) * 0.7f
        drawLine(purple, k, pa, 2f * dn)
        drawLine(purple, k, pb, 2f * dn)
        drawLine(purple, pa, pb, 2f * dn, pathEffect = dash)
        drawCircle(purple, 4f * dn, pa)
        drawCircle(purple, 4f * dn, pb)
        drawCircle(wallC, 5f * dn, k)
        label("roh", k.x + 6 * dn, k.y + 14 * dn, wallC, dn)
        label("a", (k.x + pa.x) / 2, k.y + 12 * dn, purple, dn, center = true)
        label("b", (k.x + pb.x) / 2 - 10 * dn, (k.y + pb.y) / 2, purple, dn, center = true)
        label("c", (pa.x + pb.x) / 2 + 10 * dn, (pa.y + pb.y) / 2 - 6 * dn, purple, dn, center = true)
    }
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

/** Náčrt podle výpočtu: vrcholy na vypočtené tvary; body v ose / na stěně si drží poměr podél své stěny. */
private fun straighten(s0: ObjSketch, r: ObjResult): ObjSketch {
    if (r.vertices.size != s0.vertices.size) return s0
    val nv = s0.vertices.mapIndexed { i, v ->
        val w = r.vertices[i]
        val q = r.toSketch(w.e, w.n)
        if (q != null) v.copy(x = q[0], y = q[1]) else v
    }
    val edges = s0.edges()
    val np = s0.places.map { p ->
        // změřené na svou skutečnou polohu (výpočet s GNSS)
        if (p.pointId != 0L && !r.local) r.places.firstOrNull { it.placeId == p.id }?.let { pr ->
            r.toSketch(pr.e, pr.n)?.let { return@map p.copy(x = it[0], y = it[1]) }
        }
        if (p.type != ObjPointType.AXIS && p.type != ObjPointType.WALL) return@map p
        val (ia, ib) = edges.getOrNull(s0.edgeIndex(p.edge)) ?: return@map p
        val a0 = s0.vertices[ia]; val b0 = s0.vertices[ib]
        val dx = b0.x - a0.x; val dy = b0.y - a0.y
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-12) return@map p
        val t = ((p.x - a0.x) * dx + (p.y - a0.y) * dy) / l2
        val a1 = nv[ia]; val b1 = nv[ib]
        p.copy(x = a1.x + t * (b1.x - a1.x), y = a1.y + t * (b1.y - a1.y))
    }
    return s0.copy(vertices = nv, places = np)
}

/** Poměr t polohy (x, y) podél stěny a→b (průmět); null pro nulovou stěnu. */
private fun wallParam(a: ObjVertex, b: ObjVertex, x: Double, y: Double): Double? {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val l2 = dx * dx + dy * dy
    if (l2 < 1e-12) return null
    return ((x - a.x) * dx + (y - a.y) * dy) / l2
}

/**
 * Po změně vrcholů: nezměřené body na stěně / v ose (i P), které se samy nezměnily, se přesunou se svou stěnou
 * (stejný poměr podél stěny vůči původním vrcholům). Změřené zůstávají.
 */
private fun reattach(old: ObjSketch, new: ObjSketch): ObjSketch {
    if (old.vertices == new.vertices || new.places.isEmpty()) return new
    val oldById = old.vertices.associateBy { it.id }
    val newById = new.vertices.associateBy { it.id }
    val oldEdges = old.edges()
    val newEdges = new.edges()
    val places = new.places.map { p ->
        if (p.pointId != 0L || (p.type != ObjPointType.AXIS && p.type != ObjPointType.WALL)) return@map p
        val op = old.places.firstOrNull { it.id == p.id } ?: return@map p
        if (op.x != p.x || op.y != p.y) return@map p
        val (oa, ob) = oldEdges.getOrNull(old.edgeIndex(p.edge)) ?: return@map p
        val a0 = old.vertices[oa]
        val b0 = old.vertices[ob]
        val a1 = newById[a0.id] ?: return@map p
        val b1 = newById[b0.id] ?: newEdges.getOrNull(new.edgeIndex(p.edge))?.let { new.vertices[it.second] } ?: return@map p
        if (a1 == oldById[a0.id] && b1 == oldById[b0.id]) return@map p
        val t = wallParam(a0, b0, p.x, p.y) ?: return@map p
        p.copy(x = a1.x + t * (b1.x - a1.x), y = a1.y + t * (b1.y - a1.y))
    }
    return new.copy(places = places)
}

/** Co visí na vrcholu: body (roh, body na stěně z vrcholu) a počet kót, které se smažou s ním. */
private fun vertexDeps(s: ObjSketch, id: Int): Pair<List<ObjPlace>, Int> {
    val pls = s.places.filter { it.vertex == id || it.edge == id }
    val refs = setOf("v:$id") + pls.map { "p:${it.id}" }
    return pls to s.tapes.count { it.a in refs || it.b in refs }
}
