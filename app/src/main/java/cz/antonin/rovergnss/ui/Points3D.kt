package cz.antonin.rovergnss.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.antonin.rovergnss.data.MeasureCalc
import cz.antonin.rovergnss.data.Measurement
import cz.antonin.rovergnss.data.Point
import cz.antonin.rovergnss.data.PointResult
import cz.antonin.rovergnss.data.PointSource
import cz.antonin.rovergnss.geo.Angles
import cz.antonin.rovergnss.link.Quality
import cz.antonin.rovergnss.service.RoverController
import kotlinx.coroutines.flow.emptyFlow
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val EL3D_DEFAULT = 0.55f   // naklopení [rad]: 0 = z boku, π/2 = shora

/** Bod ve 3D: místní souřadnice od těžiště (E, N [m]), výška H (Bpv, jinak h elipsoidu) a přesnost z měření. */
private class P3(
    val p: Point,
    val e: Double,
    val n: Double,
    val h: Double,
    val given: Boolean,      // zadaný bod (Vytyčování), ne změřený
    val color: Color,
    val sH: Double?,         // σ vodorovně [m] (σ průměru, jinak σ epoch)
    val sU: Double?,         // σ výšky [m]
    val reliable: Boolean,   // změřen s ověřeným / bezchybným fixem
)

/**
 * Body lokality ve 3D (0.13.0): otáčení, naklopení a zoom jako terč, vlevo stupnice nadmořské výšky (Bpv),
 * převýšení ×1 / ×5 / ×10 (stupnice zůstává ve skutečných metrech). Klepnutí na dva body: ΔY, ΔX (S-JTSK), ΔH,
 * vodorovná a šikmá vzdálenost, azimut, sklon a přesnost z měření obou bodů.
 */
@Composable
fun Points3DScreen(c: RoverController, measured: List<Point>, lastMeas: Map<Long, Measurement>, onClose: () -> Unit) {
    BackHandler { onClose() }
    val job by c.activeJob.collectAsStateWithLifecycle()
    val allFlow = remember(job?.id) { job?.let { c.db.points().byJob(it.id) } ?: emptyFlow() }
    val all by allFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var withGiven by remember { mutableStateOf(false) }
    var exag by remember { mutableIntStateOf(1) }
    var sel by remember { mutableStateOf(listOf<Long>()) }

    val given = remember(all) { all.filter { it.source != PointSource.MEASURED } }
    val pts = remember(measured, given, withGiven, lastMeas) {
        val src = (measured + if (withGiven) given else emptyList()).filter { it.lat != null && it.lon != null && (it.bpv ?: it.hEll) != null }
        if (src.isEmpty()) return@remember emptyList<P3>()
        val lat0 = src.map { it.lat!! }.average()
        val lon0 = src.map { it.lon!! }.average()
        val (kN, kE) = MeasureCalc.metersPerDeg(lat0)
        src.map { p ->
            val m = lastMeas[p.id]
            val isGiven = p.source != PointSource.MEASURED
            val ok = !isGiven && m != null && m.result == PointResult.OK && m.clusters < 2 && m.usedQuality == Quality.FIX.code
            val col = when {
                isGiven -> Color(0xFF616161)
                m == null -> Color(0xFF9E9E9E)
                m.usedQuality != Quality.FIX.code -> Color(0xFFC62828)          // bez fixu
                m.clusters >= 2 || m.verify == 2 -> Color(0xFFEF6C00)          // podezřelý fix
                m.result == PointResult.OK -> Color(0xFF2E7D32)
                else -> Color(0xFFF9A825)                                     // fix, cíl nesplněn
            }
            P3(
                p, (p.lon!! - lon0) * kE, (p.lat!! - lat0) * kN, p.bpv ?: p.hEll!!, isGiven, col,
                sH = m?.sMeanH ?: if (m?.sN != null && m.sE != null) hypot(m.sN, m.sE) else null,
                sU = m?.sMeanU ?: m?.sU,
                reliable = ok,
            )
        }
    }
    val heightName = if (pts.isNotEmpty() && pts.all { it.p.bpv != null }) "Bpv" else "h"

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Body 3D" + (job?.name?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Zavřít") }
            }
            Row(
                Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("převýšení", style = MaterialTheme.typography.bodySmall)
                listOf(1, 5, 10).forEach { k -> FilterChip(exag == k, { exag = k }, label = { Text("×$k") }) }
                FilterChip(withGiven, {
                    withGiven = !withGiven
                    sel = emptyList()
                }, label = { Text("i zadané body") })
            }
            if (pts.isEmpty()) {
                Text("Žádné body s polohou a výškou.", modifier = Modifier.padding(16.dp))
                return@Column
            }
            Scene3D(pts, exag.toFloat(), heightName, sel, Modifier.weight(1f).fillMaxWidth()) { id ->
                sel = when {
                    id == null -> emptyList()
                    id in sel -> sel - id
                    sel.size >= 2 -> listOf(id)
                    else -> sel + id
                }
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val a = pts.firstOrNull { it.p.id == sel.getOrNull(0) }
                val b = pts.firstOrNull { it.p.id == sel.getOrNull(1) }
                when {
                    a != null && b != null -> PairInfo(a, b, heightName)
                    a != null -> Text(
                        "${a.p.name}: $heightName ${fmt(a.h, 3)} m – klepni na druhý bod.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Text(
                        "Klepni na dva body: ΔY, ΔX, ΔH, vzdálenost, azimut a sklon. Jeden prst: otáčení a naklopení · " +
                            "dva prsty: zoom · dvojklep: výchozí pohled.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "zelená ověřený fix · žlutá fix, cíl nesplněn · oranžová podezřelý fix · červená bez fixu" +
                        if (withGiven) " · šedý čtverec zadaný bod" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Výsledek měření mezi body A → B. */
@Composable
private fun PairInfo(a: P3, b: P3, hName: String) {
    val (d, az) = Angles.distanceBearing(a.p.lat!!, a.p.lon!!, b.p.lat!!, b.p.lon!!)
    val dh = b.h - a.h
    val mono = FontFamily.Monospace
    val t = MaterialTheme.typography.bodyMedium
    Text("${a.p.name} → ${b.p.name}", style = MaterialTheme.typography.titleSmall)
    if (a.p.y != null && a.p.x != null && b.p.y != null && b.p.x != null) Text(
        "ΔY ${sgn(b.p.y - a.p.y, 3)} m   ΔX ${sgn(b.p.x - a.p.x, 3)} m (S-JTSK)", fontFamily = mono, style = t,
    )
    Text("Δ$hName ${sgn(dh, 3)} m", fontFamily = mono, style = t)
    Text(
        "vodorovně ${fmt(d, 3)} m   šikmo ${fmt(hypot(d, dh), 3)} m", fontFamily = mono, style = t,
    )
    Text(
        "azimut ${fmt(az, 1)}°   sklon ${if (d > 0.001) sgn(dh / d * 100, 2) + " %" else "–"}", fontFamily = mono, style = t,
    )
    val sh = if (a.sH != null && b.sH != null) sqrt(a.sH * a.sH + b.sH * b.sH) else null
    val su = if (a.sU != null && b.sU != null) sqrt(a.sU * a.sU + b.sU * b.sU) else null
    if (sh != null || su != null) Text(
        "přesnost z měření: vodorovně ±${fmt(sh?.times(100), 1)} cm, výška ±${fmt(su?.times(100), 1)} cm",
        style = MaterialTheme.typography.bodySmall,
    )
    val bad = listOf(a, b).filter { !it.given && !it.reliable }
    if (bad.isNotEmpty()) Text(
        "Pozor: ${bad.joinToString { it.p.name }} bez ověřeného fixu – chyba může být decimetry až metry, σ ji neukáže.",
        color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall,
    )
    if (a.given || b.given) Text(
        "Zadaný bod: přesnost podle zdroje souřadnic (není v σ).",
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun sgn(v: Double, dec: Int): String = (if (v >= 0) "+" else "") + fmt(v, dec)

/** Krok 1–2–5×10ⁿ, aby se rozsah vešel do ~[parts] dílků. */
private fun niceStep3(range: Double, parts: Int): Double {
    if (range <= 0) return 1.0
    val raw = range / parts
    val p = 10.0.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, 5.0, 10.0).map { it * p }.first { it >= raw }
}

@Composable
private fun Scene3D(
    pts: List<P3>,
    exag: Float,
    hName: String,
    sel: List<Long>,
    modifier: Modifier,
    onTap: (Long?) -> Unit,
) {
    var yaw by remember { mutableFloatStateOf(0f) }
    var el by remember { mutableFloatStateOf(EL3D_DEFAULT) }
    var zoom by remember { mutableFloatStateOf(1f) }
    // promítnuté polohy bodů z posledního kreslení (pro klepnutí) – mimo stav, aby nevyvolávaly překreslení
    val hits = remember { ArrayList<Pair<Long, Offset>>() }
    val hMin = pts.minOf { it.h }
    val hMax = pts.maxOf { it.h }
    val hMid = (hMin + hMax) / 2
    val extent = max(pts.maxOf { hypot(it.e, it.n) }, 1.0)
    val ringStep = niceStep3(extent, 3)
    Canvas(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, z, _ ->
                    yaw += pan.x * 0.008f
                    el = (el + pan.y * 0.006f).coerceIn(0f, (PI / 2).toFloat())
                    zoom = (zoom * z).coerceIn(0.2f, 50f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        yaw = 0f
                        el = EL3D_DEFAULT
                        zoom = 1f
                    },
                    onTap = { pos ->
                        val lim = 28.dp.toPx()
                        val best = hits.minByOrNull { (pos - it.second).getDistance() }
                        onTap(best?.takeIf { (pos - it.second).getDistance() <= lim }?.first)
                    },
                )
            }
    ) {
        val axisW = 70.dp.toPx()
        val cx = axisW + (size.width - axisW) / 2
        val cy = size.height / 2
        val s = (min(size.width - axisW, size.height) * 0.40f / extent.toFloat()) * zoom   // px na metr
        val cyw = cos(yaw)
        val syw = sin(yaw)
        val se = sin(el)
        val ce = cos(el)
        fun depth(e: Double, n: Double) = (e * syw + n * cyw).toFloat()
        fun proj(e: Double, n: Double, h: Double): Offset {
            val x = (e * cyw - n * syw).toFloat()
            val u = ((h - hMid) * exag).toFloat()
            return Offset(cx + x * s, cy - (depth(e, n) * se + u * ce) * s)
        }
        val grid = Color(0xFF9E9E9E)
        val ink = Color(0xFF212121)

        // podlaha ve výšce nejnižšího bodu: kružnice od těžiště, osy, světové strany
        for (k in 1..3) {
            val r = ringStep * k
            val path = Path()
            for (i in 0..72) {
                val t = 2 * PI * i / 72
                val p = proj(r * cos(t), r * sin(t), hMin)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, grid, style = Stroke(width = if (k == 3) 2f else 1.2f))
            val lp = proj(r * 0.707, -r * 0.707, hMin)
            text3(fmtLen3(r), lp.x + 4, lp.y - 4, grid, 11f)
        }
        val rr = ringStep * 3.35
        drawLine(grid.copy(alpha = 0.5f), proj(0.0, -rr, hMin), proj(0.0, rr, hMin), 1f)
        drawLine(grid.copy(alpha = 0.5f), proj(-rr, 0.0, hMin), proj(rr, 0.0, hMin), 1f)
        val rl = ringStep * 3.6
        listOf(Triple("S", 0.0, rl), Triple("J", 0.0, -rl), Triple("V", rl, 0.0), Triple("Z", -rl, 0.0)).forEach { (t, e, n) ->
            val p = proj(e, n, hMin)
            text3(t, p.x - 5.dp.toPx(), p.y + 5.dp.toPx(), if (t == "S") Color(0xFFC62828) else ink, 15f, bold = true)
        }

        // stupnice nadmořské výšky vlevo (skutečné metry; při pohledu shora zanikne)
        if (ce > 0.15f && hMax > hMin) {
            val x = axisW - 10.dp.toPx()
            val st = niceStep3(hMax - hMin, 4)
            val y0 = cy - ((hMin - hMid) * exag).toFloat() * ce * s
            val y1 = cy - ((hMax - hMid) * exag).toFloat() * ce * s
            drawLine(ink, Offset(x, y0), Offset(x, y1), 1.5f)
            var v = ceil(hMin / st) * st
            while (v <= hMax + 1e-9) {
                val y = cy - ((v - hMid) * exag).toFloat() * ce * s
                drawLine(ink, Offset(x - 6.dp.toPx(), y), Offset(x, y), 1.2f)
                text3(fmt(v, if (st < 1) 1 else 0), 2.dp.toPx(), y + 4.dp.toPx(), ink, 10f)
                v += st
            }
            text3("$hName [m]", 2.dp.toPx(), min(y0, y1) - 10.dp.toPx(), ink, 10f)
        } else if (ce > 0.15f) {
            text3("$hName ${fmt(hMin, 2)} m", 2.dp.toPx(), cy, ink, 10f)
        }

        // vybraná dvojice: spojnice
        val a = pts.firstOrNull { it.p.id == sel.getOrNull(0) }
        val b = pts.firstOrNull { it.p.id == sel.getOrNull(1) }
        if (a != null && b != null) drawLine(Color(0xFF1565C0), proj(a.e, a.n, a.h), proj(b.e, b.n, b.h), 4f)

        // body: vzdálené první, svislice k podlaze, název
        hits.clear()
        val r = 6.dp.toPx()
        pts.sortedByDescending { depth(it.e, it.n) }.forEach { q ->
            val top = proj(q.e, q.n, q.h)
            if (ce > 0.05f) drawLine(q.color.copy(alpha = 0.45f), proj(q.e, q.n, hMin), top, 1.5f)
            val chosen = q.p.id in sel
            if (chosen) drawCircle(Color(0xFF1565C0), r * 1.8f, top)
            if (q.given) {
                drawRect(Color.White, Offset(top.x - r * 1.1f, top.y - r * 1.1f), Size(r * 2.2f, r * 2.2f))
                drawRect(q.color, Offset(top.x - r * 0.85f, top.y - r * 0.85f), Size(r * 1.7f, r * 1.7f))
            } else {
                drawCircle(Color.White, r * 1.25f, top)
                drawCircle(q.color, r, top)
            }
            text3(q.p.name, top.x + r * 1.5f, top.y - r * 0.6f, ink, 11f, bold = chosen)
            hits.add(q.p.id to top)
        }
    }
}

private fun fmtLen3(m: Double): String = when {
    m >= 1000 -> String.format(Locale.US, "%.1f km", m / 1000)
    m >= 1 -> String.format(Locale.US, "%.0f m", m)
    else -> String.format(Locale.US, "%.0f cm", m * 100)
}.replace('.', ',')

private fun DrawScope.text3(s: String, x: Float, y: Float, color: Color, sizeDp: Float, bold: Boolean = false) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(),
        )
        textSize = sizeDp * density
        isFakeBoldText = bold
    }
    drawContext.canvas.nativeCanvas.drawText(s, x, y, paint)
}
