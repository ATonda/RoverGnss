package cz.antonin.rovergnss.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import cz.antonin.rovergnss.data.MeasureEpoch
import cz.antonin.rovergnss.data.MeasureResult
import cz.antonin.rovergnss.link.Quality
import cz.antonin.rovergnss.service.RoverController
import cz.antonin.rovergnss.service.TargetSource
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Nejvýš tolik posledních epoch se kreslí (výpočet průměru a σ je ze všech). */
private const val MAX_DRAW = 3000
private const val EL_DEFAULT = 0.6f   // naklopení [rad]: 0 = z boku, π/2 = shora

/** Epocha v místních souřadnicích kolem průměru [m]: východ, sever, výška; kvalita. */
private class Enu(val e: Float, val n: Float, val u: Float, val q: Int)

/**
 * Terč přes celou obrazovku: epochy ve 3D kolem průměru, obarvené podle kvality.
 * Data se obnovují 1× za sekundu; po konci měření zůstane poslední stav.
 */
@Composable
fun TargetScreen(c: RoverController, source: Int, onClose: () -> Unit) {
    BackHandler { onClose() }
    val live by c.liveAvg.collectAsStateWithLifecycle()
    val meas by c.measure.collectAsStateWithLifecycle()
    var eps by remember { mutableStateOf(c.targetEpochs(source)) }
    var startMs by remember { mutableStateOf<Long?>(null) }
    var title by remember { mutableStateOf(if (source == TargetSource.LIVE) "Terč – průměr" else "Terč – měření") }
    LaunchedEffect(source) {
        while (true) {
            val l = c.targetEpochs(source)
            // po konci měření (nebo vynulování) držet poslední epochy
            if (l.isNotEmpty()) eps = l
            delay(1000)
        }
    }
    LaunchedEffect(live, meas) {
        if (source == TargetSource.LIVE) {
            live?.let { startMs = it.startMs }
        } else {
            meas?.let {
                startMs = it.startMs
                title = "Terč – ${it.point.name}"
            }
        }
    }
    val res = remember(eps) { MeasureCalc.compute(eps) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Zavřít") }
            }
            if (res.lat == null || res.lon == null || res.h == null) {
                Text("Zatím žádné epochy bez pohybu.", modifier = Modifier.padding(16.dp))
                return@Column
            }
            Target3D(eps, res, Modifier.weight(1f).fillMaxWidth())
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // legenda jen pro kvality, které v datech jsou
                val present = remember(eps) { eps.filter { !it.moving }.map { it.q }.distinct().sorted() }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    present.forEach { q ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(qualityColor(Quality.of(q)), CircleShape))
                            Text(" " + Quality.of(q).label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val hs = if (res.sN != null && res.sE != null) hypot(res.sN, res.sE) else null
                val dur = startMs?.let { (System.currentTimeMillis() - it) / 1000 }
                Text(
                    "σ N/E/U ${fmt(res.sN, 3)} / ${fmt(res.sE, 3)} / ${fmt(res.sU, 3)} m   σH ${fmt(hs, 3)} m",
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "epoch v průměru ${res.used} / ${res.epochs}" +
                        (if (res.usedQuality != 0) " (${Quality.of(res.usedQuality).label})" else "") +
                        (dur?.let { String.format(Locale.US, " · %d:%02d", it / 60, it % 60) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Jeden prst: otáčení a naklopení · dva prsty: zoom · dvojklep: výchozí pohled. Kříž = průměr.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Krok kružnic 1–2–5×10ⁿ: tři kružnice pokryjí ~95 % epoch. */
private fun ringStep(extent: Double): Double {
    val want = max(extent, 0.003) / 3
    val p = 10.0.pow(floor(log10(want)))
    for (k in listOf(1.0, 2.0, 5.0, 10.0)) if (k * p >= want) return k * p
    return 10 * p
}

private fun fmtLen(v: Double, signed: Boolean = false): String {
    val a = abs(v)
    val s = if (signed) (if (v > 0) "+" else if (v < 0) "−" else "") else (if (v < 0) "−" else "")
    return s + when {
        a < 0.01 -> String.format(Locale.US, "%.0f mm", a * 1000)
        a < 1.0 -> String.format(Locale.US, if (a * 100 % 1.0 == 0.0) "%.0f cm" else "%.1f cm", a * 100)
        else -> String.format(Locale.US, if (a % 1.0 == 0.0) "%.0f m" else "%.1f m", a)
    }
}

private fun percentile95(v: List<Double>): Double {
    if (v.isEmpty()) return 0.0
    val s = v.sorted()
    return s[((s.size - 1) * 0.95).toInt()]
}

@Composable
private fun Target3D(eps: List<MeasureEpoch>, res: MeasureResult, modifier: Modifier) {
    var yaw by remember { mutableFloatStateOf(0f) }
    var el by remember { mutableFloatStateOf(EL_DEFAULT) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val pts = remember(eps, res) {
        val (kN, kE) = MeasureCalc.metersPerDeg(res.lat!!)
        eps.filter { !it.moving }.takeLast(MAX_DRAW).map {
            Enu(((it.lon - res.lon!!) * kE).toFloat(), ((it.lat - res.lat) * kN).toFloat(), (it.h - res.h!!).toFloat(), it.q)
        }
    }
    val step = remember(pts) {
        val hor = percentile95(pts.map { hypot(it.e.toDouble(), it.n.toDouble()) })
        val ver = percentile95(pts.map { abs(it.u.toDouble()) })
        ringStep(max(hor, ver))
    }
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, z, _ ->
                    yaw += pan.x * 0.008f
                    el = (el + pan.y * 0.006f).coerceIn(0f, (PI / 2).toFloat())
                    zoom = (zoom * z).coerceIn(0.2f, 30f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    yaw = 0f
                    el = EL_DEFAULT
                    zoom = 1f
                })
            }
    ) {
        val axisW = 64.dp.toPx()
        val cx = axisW + (size.width - axisW) / 2
        val cy = size.height / 2
        val s = (min(size.width - axisW, size.height) * 0.42f / (3 * step).toFloat()) * zoom   // px na metr
        val cy0 = cos(yaw)
        val sy0 = sin(yaw)
        val se = sin(el)
        val ce = cos(el)
        fun depth(e: Float, n: Float) = e * sy0 + n * cy0
        fun proj(e: Float, n: Float, u: Float): Offset {
            val x = e * cy0 - n * sy0
            val d = depth(e, n)
            return Offset(cx + x * s, cy - (d * se + u * ce) * s)
        }
        val grid = Color(0xFF9E9E9E)
        val ink = Color(0xFF212121)

        // kružnice na podlaze (nejvýš tři) s popisky
        for (k in 1..3) {
            val r = (step * k).toFloat()
            val path = Path()
            for (i in 0..72) {
                val t = (2 * PI * i / 72).toFloat()
                val p = proj(r * cos(t), r * sin(t), 0f)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, grid, style = Stroke(width = if (k == 3) 2f else 1.2f))
            val lp = proj(r * 0.707f, -r * 0.707f, 0f)
            label(fmtLen(step * k), lp.x + 4, lp.y - 4, grid, 11f)
        }
        // osy sever–jih, východ–západ a světové strany
        val R = (step * 3.35).toFloat()
        drawLine(grid.copy(alpha = 0.5f), proj(0f, -R, 0f), proj(0f, R, 0f), 1f)
        drawLine(grid.copy(alpha = 0.5f), proj(-R, 0f, 0f), proj(R, 0f, 0f), 1f)
        val Rl = (step * 3.6).toFloat()
        listOf(Triple("S", 0f, Rl), Triple("J", 0f, -Rl), Triple("V", Rl, 0f), Triple("Z", -Rl, 0f)).forEach { (t, e, n) ->
            val p = proj(e, n, 0f)
            label(t, p.x - 5.dp.toPx(), p.y + 5.dp.toPx(), if (t == "S") Color(0xFFC62828) else ink, 15f, bold = true)
        }

        // svislá stupnice výšky vlevo (při pohledu shora zanikne)
        if (ce > 0.15f) {
            val x = axisW - 10.dp.toPx()
            val top = cy - (3 * step).toFloat() * ce * s
            val bot = cy + (3 * step).toFloat() * ce * s
            drawLine(ink, Offset(x, top), Offset(x, bot), 1.5f)
            for (k in -3..3) {
                val y = cy - (k * step).toFloat() * ce * s
                drawLine(ink, Offset(x - 6.dp.toPx(), y), Offset(x, y), if (k == 0) 2.5f else 1.2f)
                label(if (k == 0) "0" else fmtLen(k * step, signed = true), 2.dp.toPx(), y + 4.dp.toPx(), ink, 10f)
            }
            label("výška", 2.dp.toPx(), top - 8.dp.toPx(), ink, 10f)
        }

        // epochy: vzdálené první, svislice k podlaze
        val r = 3.5.dp.toPx()
        pts.sortedByDescending { depth(it.e, it.n) }.forEach { p ->
            val col = qualityColor(Quality.of(p.q))
            val top = proj(p.e, p.n, p.u)
            if (ce > 0.05f) drawLine(col.copy(alpha = 0.25f), proj(p.e, p.n, 0f), top, 1f)
            drawCircle(col.copy(alpha = 0.85f), r, top)
        }
        // poslední epocha zvýrazněná
        pts.lastOrNull()?.let { p ->
            val o = proj(p.e, p.n, p.u)
            drawCircle(Color.White, r * 1.9f, o)
            drawCircle(qualityColor(Quality.of(p.q)), r * 1.4f, o)
        }
        // průměr: kříž na podlaze a svislá čárka
        val c0 = proj(0f, 0f, 0f)
        val a = (step * 0.35).toFloat()
        drawLine(ink, proj(-a, 0f, 0f), proj(a, 0f, 0f), 3f)
        drawLine(ink, proj(0f, -a, 0f), proj(0f, a, 0f), 3f)
        if (ce > 0.05f) drawLine(ink, proj(0f, 0f, -a), proj(0f, 0f, a), 3f)
        drawCircle(ink, 3f, c0)
    }
}

private fun DrawScope.label(s: String, x: Float, y: Float, color: Color, sizeDp: Float, bold: Boolean = false) {
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
